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

import org.logaperture.api.LevelOverride;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link LevelOverride} per logger name — idempotent by construction:
 * {@link #put} replaces any existing entry for that logger rather than
 * accumulating a second one (doc/specs/level-control.md's idempotency
 * semantics).
 */
public final class OverrideRegistry {

    private final Map<String, LevelOverride> overrides = new ConcurrentHashMap<>();

    public void put(LevelOverride override) {
        overrides.put(override.loggerName(), override);
    }

    public Optional<LevelOverride> get(String loggerName) {
        return Optional.ofNullable(overrides.get(loggerName));
    }

    public void remove(String loggerName) {
        overrides.remove(loggerName);
    }

    /**
     * Removes {@code loggerName} only if its current value still equals
     * {@code expected} — an atomic compare-and-remove that guards a stale
     * caller (e.g. the expiry sweep, which reads a snapshot and may act on
     * it after the registry has moved on) from clobbering a newer override
     * for the same logger set concurrently.
     *
     * @return {@code true} if the removal happened, {@code false} if the
     *         current value had already changed (or the entry was already gone)
     */
    public boolean removeIfCurrent(String loggerName, LevelOverride expected) {
        return overrides.remove(loggerName, expected);
    }

    /** A point-in-time snapshot, safe to iterate while the registry is concurrently mutated. */
    public Map<String, LevelOverride> all() {
        return Map.copyOf(overrides);
    }
}
