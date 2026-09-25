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

import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures each handler's baseline (pre-LogAperture) level once, lazily —
 * the {@link BaselineRegistry} counterpart for handlers (doc/specs/
 * handler-floor-control.md "Baseline capture"). Without this, {@code
 * resetHandler} is undefined, exactly the reasoning {@link BaselineRegistry}
 * documents for loggers.
 *
 * <p>Deliberately does <em>not</em> follow {@link BaselineRegistry}'s
 * {@code computeIfAbsent} shortcut: for a logger, {@code
 * adapter.configuredLevel(name)} returning empty is a stable fact ("no
 * explicit level of its own") worth caching permanently. For a handler, an
 * empty {@code adapter.handlerLevel(ref)} means "doesn't resolve right now"
 * — a real {@code java.util.logging.Handler} always has a level once it
 * resolves at all, so empty here is transient (the ref simply hasn't been
 * seen by this adapter yet, e.g. a multi-context capability pre-check
 * running against a context that lacks the handler). Caching that {@code
 * empty} would wrongly lock a ref out of ever getting a real baseline, and
 * {@code resetHandler} would later hand the adapter a bare {@code null} —
 * which, unlike a logger's "clear to inherited", a handler has no concept
 * of ({@code Handler#setLevel} throws on {@code null}). So only a genuinely
 * resolved level is ever cached; an empty result is returned but not
 * remembered, and the next call tries again.
 *
 * <p>Also holds the vendor defaults layer (doc/specs/vendor-defaults.md "Handlers"): a handler
 * the vendor defaults file gives a fixed level has that level as its <em>effective</em> baseline
 * ({@link #get}); one it sets to {@code AUTO} keeps its native value here and is tracked by
 * {@link HandlerLevelControlService} instead (Decision M3). {@link #nativeLevel} is always the
 * handler's own captured value.
 */
public final class HandlerBaselineRegistry {

    private final Map<HandlerRef, Optional<Level>> captured = new ConcurrentHashMap<>();
    private final Map<HandlerRef, VendorDefaults.HandlerDefault> vendorDefaults;

    public HandlerBaselineRegistry() {
        this(Map.of());
    }

    /**
     * @param vendorDefaults the vendor defaults file's handler entries, by name
     */
    public HandlerBaselineRegistry(Map<HandlerRef, VendorDefaults.HandlerDefault> vendorDefaults) {
        // Insertion-ordered copy, not Map.copyOf: vendorDefaults() promises file order.
        this.vendorDefaults = Collections.unmodifiableMap(new LinkedHashMap<>(vendorDefaults));
    }

    /**
     * Captures {@code ref}'s baseline from {@code adapter} the first time it
     * resolves to a real level; subsequent calls return the already-captured
     * value without touching the adapter again. A {@code ref} that doesn't
     * resolve yet returns empty every time, uncached, until it does.
     */
    public Optional<Level> captureIfAbsent(HandlerRef ref, LoggingAdapter adapter) {
        Optional<Level> existing = captured.get(ref);
        if (existing != null) {
            return existing;
        }
        Optional<Level> resolved = adapter.handlerLevel(ref);
        if (resolved.isPresent()) {
            captured.putIfAbsent(ref, resolved);
        }
        return resolved;
    }

    public boolean isCaptured(HandlerRef ref) {
        return captured.containsKey(ref);
    }

    /**
     * The effective baseline: the vendor defaults file's fixed level for {@code ref} if it has
     * one, else the captured native value (also for a vendor {@code AUTO} handler, whose
     * tracking {@link HandlerLevelControlService} does on top of it).
     *
     * @throws IllegalStateException if {@code ref} has no fixed vendor level and its native
     *                               baseline was never captured
     */
    public Optional<Level> get(HandlerRef ref) {
        VendorDefaults.HandlerDefault vendor = vendorDefaults.get(ref);
        if (vendor != null && vendor.level() != null) {
            return Optional.of(vendor.level());
        }
        return nativeLevel(ref);
    }

    /**
     * The handler's own captured value, ignoring the vendor layer.
     *
     * @throws IllegalStateException if {@code ref}'s baseline was never captured
     */
    public Optional<Level> nativeLevel(HandlerRef ref) {
        Optional<Level> value = captured.get(ref);
        if (value == null) {
            throw new IllegalStateException("baseline not captured for " + ref);
        }
        return value;
    }

    /** The vendor defaults file's entry for {@code ref}, if it names one. */
    public Optional<VendorDefaults.HandlerDefault> vendorDefault(HandlerRef ref) {
        return Optional.ofNullable(vendorDefaults.get(ref));
    }

    /** Every handler the vendor defaults file names, in file order. */
    public Collection<VendorDefaults.HandlerDefault> vendorDefaults() {
        return vendorDefaults.values();
    }

    /**
     * Moves {@code oldRef}'s captured baseline, if any, to {@code newRef} —
     * for a handler renamed in place by the adapter (doc/specs/
     * handler-floor-control.md "Resume resilience and baseline-key
     * migration", issue #29). A no-op if {@code oldRef} was never captured.
     * If {@code newRef} already has a captured baseline, that one wins and
     * {@code oldRef}'s is simply dropped, same {@link KeyedRegistry#migrateKey}
     * "current key wins" rule.
     */
    public void migrateKey(HandlerRef oldRef, HandlerRef newRef) {
        Optional<Level> oldValue = captured.remove(oldRef);
        if (oldValue != null) {
            captured.putIfAbsent(newRef, oldValue);
        }
    }
}
