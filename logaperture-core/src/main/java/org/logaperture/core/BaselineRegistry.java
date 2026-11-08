/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.logaperture.core;

import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Captures each logger's baseline (framework-configured) level once,
 * lazily — doc/specs/level-control.md: "Without this, {@code
 * resetLogger}/{@code resetAllLoggers} are undefined, and there is no way to
 * distinguish 'the operator set INFO' from 'the app was already INFO'."
 *
 * <p>Presence of a key distinguishes "captured" from "not yet captured";
 * the stored {@link Optional} distinguishes "captured as an explicit
 * level" from "captured as inherited" (no explicit level at capture time).
 *
 * <p>Also holds the vendor defaults layer (doc/specs/vendor-defaults.md "Loggers"): a logger
 * the vendor defaults file names has that level as its <em>effective</em> baseline, which is
 * what {@link #get} returns and so what every reset, expiry and undo path lands on. The captured
 * application value stays available as {@link #nativeLevel}.
 *
 * <p>A vendor-named logger can be <em>reset to native</em> (doc/specs/reset-to-native.md): until
 * restart the vendor layer is ignored for it, so {@link #get} returns the native value and the
 * vendor-layer passes leave it alone ({@link #isResetToNative}).
 */
public final class BaselineRegistry {

    /**
     * A captured baseline: the level, and the adapter's {@link LoggingAdapter#nativeLevelToken} read with it
     * ({@code null} for most loggers). One value per name, so a capture, a rebase and a refresh of the token
     * each replace the pair atomically (doc/specs/native-level-baseline.md N3).
     */
    private record Baseline(Optional<Level> level, Object token) {
    }

    private final Map<String, Baseline> captured = new ConcurrentHashMap<>();
    private final Map<String, Level> vendorLevels;
    private final Map<String, String> vendorReasons;
    private final Set<String> resetToNative = ConcurrentHashMap.newKeySet();

    public BaselineRegistry() {
        this(Map.of());
    }

    /**
     * @param vendorLevels the vendor defaults file's logger levels, by exact name
     */
    public BaselineRegistry(Map<String, Level> vendorLevels) {
        this(vendorLevels, Map.of());
    }

    /**
     * @param vendorLevels  the vendor defaults file's logger levels, by exact name
     * @param vendorReasons the vendor defaults file's logger {@code reason}s, by exact name
     *                      (loggers without one are simply absent)
     */
    public BaselineRegistry(Map<String, Level> vendorLevels, Map<String, String> vendorReasons) {
        this.vendorLevels = Map.copyOf(vendorLevels);
        this.vendorReasons = Map.copyOf(vendorReasons);
    }

    /**
     * Captures {@code name}'s native baseline from {@code adapter} the first time
     * it's asked about; subsequent calls return the already-captured value
     * without touching the adapter again. Always the application's own value, never the
     * vendor layer -- so it must run before a vendor level is applied to {@code name}.
     */
    public Optional<Level> captureIfAbsent(String name, LoggingAdapter adapter) {
        Function<String, Baseline> captureWithToken = key -> {
            Optional<Level> level = adapter.configuredLevel(key);
            Object token = level.isPresent() ? adapter.nativeLevelToken(key) : null; // level first, then its token
            return new Baseline(level, token);
        };
        return captured.computeIfAbsent(name, captureWithToken).level();
    }

    /**
     * Replaces {@code name}'s captured native value with {@code level}: the level the native source
     * (the application's configuration, Spring's {@code logging.level}, an Actuator call) last set,
     * read at the moment an override against it was put back (doc/specs/log4j2-adapter.md A2), with the
     * {@code token} read alongside it. Only for a value someone else wrote: never for one this process
     * applied itself. The vendor layer is untouched.
     */
    public void rebaseNative(String name, Optional<Level> level, Object token) {
        BiFunction<String, Baseline, Baseline> replace =
                (key, previous) -> new Baseline(level, level.isPresent() ? token : null);
        captured.computeIfPresent(name, replace); // never creates a baseline nobody captured
    }

    /**
     * After a reconfiguration: re-reads every baseline's token from {@code adapter} and holds it only if the
     * logger still declares the level the baseline holds -- also for a baseline that had none, so a token
     * dropped earlier comes back when the application declares the level again. A reload of an unchanged
     * configuration keeps every token; one that changed what the application declares drops that logger's token, so a reset writes the
     * captured API level as for a logger with no token (doc/specs/native-level-baseline.md N3).
     */
    public void refreshNativeTokens(LoggingAdapter adapter) {
        for (String name : captured.keySet()) {
            BiFunction<String, Baseline, Baseline> refresh = (key, held) -> {
                try {
                    Object fresh = adapter.nativeLevelToken(key);
                    boolean matches = fresh != null && adapter.configuredLevel(key).equals(held.level());
                    return new Baseline(held.level(), matches ? fresh : null); // a dropped token can come back
                } catch (RuntimeException unreadable) {
                    return new Baseline(held.level(), null);
                }
            };
            captured.computeIfPresent(name, refresh);
        }
    }

    /**
     * Puts {@code name}'s baseline back on {@code adapter}: the effective baseline, carrying the adapter's
     * native-level token only when that baseline is the native value, never over a vendor level
     * (doc/specs/native-level-baseline.md N3).
     *
     * @throws IllegalStateException if {@code name} has no vendor level and was never captured
     */
    public void restore(String name, LoggingAdapter adapter) {
        Baseline held = captured.get(name); // one snapshot: a concurrent rebase must not split level from token
        if (vendorLevelInEffect(name)) {
            adapter.restoreLevel(name, vendorLevels.get(name), null); // never written with the native token
            return;
        }
        if (held == null) {
            throw new IllegalStateException("baseline not captured for " + name);
        }
        adapter.restoreLevel(name, held.level().orElse(null), held.token());
    }

    /** As {@link #restore}, but always the application's own captured value (stop). */
    public void restoreNative(String name, LoggingAdapter adapter) {
        Baseline held = captured.get(name);
        if (held == null) {
            throw new IllegalStateException("baseline not captured for " + name);
        }
        adapter.restoreLevel(name, held.level().orElse(null), held.token());
    }

    public boolean isCaptured(String name) {
        return captured.containsKey(name);
    }

    /**
     * The effective baseline: the vendor level if the vendor defaults file names {@code name} and
     * it isn't reset to native, else the captured native value.
     *
     * @throws IllegalStateException if {@code name} has no vendor level and its native baseline
     *                               was never captured
     */
    public Optional<Level> get(String name) {
        if (vendorLevelInEffect(name)) {
            return Optional.of(vendorLevels.get(name));
        }
        return nativeLevel(name);
    }

    /** Whether the vendor defaults file names {@code name} and its level is in effect (not reset to native). */
    public boolean vendorLevelInEffect(String name) {
        return vendorLevels.containsKey(name) && !resetToNative.contains(name);
    }

    /**
     * The application's own captured value, ignoring the vendor layer.
     *
     * @throws IllegalStateException if {@code name}'s baseline was never captured
     */
    public Optional<Level> nativeLevel(String name) {
        Baseline held = captured.get(name);
        Optional<Level> value = held == null ? null : held.level();
        if (value == null) {
            throw new IllegalStateException("baseline not captured for " + name);
        }
        return value;
    }

    /** The vendor defaults file's level for {@code name}, if it names one. */
    public Optional<Level> vendorLevel(String name) {
        return Optional.ofNullable(vendorLevels.get(name));
    }

    /** The vendor defaults file's {@code reason} for {@code name}, if it gives one. */
    public Optional<String> vendorReason(String name) {
        return Optional.ofNullable(vendorReasons.get(name));
    }

    /** Every logger the vendor defaults file names. */
    public Set<String> vendorLoggerNames() {
        return vendorLevels.keySet();
    }

    /** The vendor-named loggers whose vendor level is in effect -- every one not reset to native. */
    public Set<String> activeVendorLoggerNames() {
        Set<String> active = new TreeSet<>(vendorLevels.keySet());
        active.removeAll(resetToNative);
        return active;
    }

    /** The loggers currently reset to native, sorted. */
    public Set<String> resetToNativeNames() {
        return new TreeSet<>(resetToNative);
    }

    /** Whether {@code name}'s vendor level is being ignored until restart. */
    public boolean isResetToNative(String name) {
        return resetToNative.contains(name);
    }

    /**
     * Ignores {@code name}'s vendor level until restart.
     *
     * @return {@code true} if this changed anything -- {@code false} if the vendor defaults file
     *         doesn't name {@code name}, or it was already reset to native
     */
    public boolean markResetToNative(String name) {
        return vendorLevels.containsKey(name) && resetToNative.add(name);
    }

    /**
     * Puts {@code name}'s vendor level back in effect.
     *
     * @return {@code true} if {@code name} was reset to native
     */
    public boolean clearResetToNative(String name) {
        return resetToNative.remove(name);
    }
}
