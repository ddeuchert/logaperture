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

import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;

import java.util.Map;
import java.util.Optional;

/**
 * One {@link HandlerLevelOverride} per handler — the {@link OverrideRegistry}
 * counterpart for handlers. Idempotent by construction: {@link #put}
 * replaces any existing entry for that handler (a second {@code logctl
 * handler CONSOLE ...} supersedes the first, keeping the original baseline —
 * doc/specs/handler-floor-control.md "Baseline capture"). A thin, typed
 * wrapper over {@link KeyedRegistry}, keyed by {@link
 * HandlerLevelOverride#handlerRef()} — same shape as {@link OverrideRegistry},
 * and now {@link KeyedRegistry}'s second real consumer (a code-review
 * finding: this class used to hand-roll its own {@code ConcurrentHashMap}
 * wrapper alongside {@code KeyedRegistry}, which existed for exactly this
 * kind of duplication).
 */
public final class HandlerOverrideRegistry {

    private final KeyedRegistry<HandlerRef, HandlerLevelOverride> registry =
            new KeyedRegistry<>(HandlerLevelOverride::handlerRef);

    public void put(HandlerLevelOverride override) {
        registry.put(override);
    }

    public Optional<HandlerLevelOverride> get(HandlerRef ref) {
        return registry.get(ref);
    }

    public void remove(HandlerRef ref) {
        registry.remove(ref);
    }

    /**
     * Removes {@code ref} only if its current value still equals {@code
     * expected} — same atomic compare-and-remove guard as {@link
     * OverrideRegistry#removeIfCurrent}.
     */
    public boolean removeIfCurrent(HandlerRef ref, HandlerLevelOverride expected) {
        return registry.removeIfCurrent(ref, expected);
    }

    /** A point-in-time snapshot, safe to iterate while the registry is concurrently mutated. */
    public Map<HandlerRef, HandlerLevelOverride> all() {
        return registry.all();
    }
}
