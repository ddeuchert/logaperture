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

    private final Map<String, Optional<Level>> captured = new ConcurrentHashMap<>();
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
        return captured.computeIfAbsent(name, adapter::configuredLevel);
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
        Level vendor = vendorLevels.get(name);
        if (vendor != null && !resetToNative.contains(name)) {
            return Optional.of(vendor);
        }
        return nativeLevel(name);
    }

    /**
     * The application's own captured value, ignoring the vendor layer.
     *
     * @throws IllegalStateException if {@code name}'s baseline was never captured
     */
    public Optional<Level> nativeLevel(String name) {
        Optional<Level> value = captured.get(name);
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
