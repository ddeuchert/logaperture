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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One value per key, idempotent by construction ({@link #put} replaces any
 * existing entry under that value's key rather than accumulating a second
 * one), with an atomic compare-and-remove for guarding a stale caller (a
 * sweep that read a snapshot and may act on it after the registry has moved
 * on) from clobbering a newer entry set concurrently. {@link OverrideRegistry}
 * and {@link HandlerOverrideRegistry} are both exactly this shape — one keyed
 * by logger name, the other by {@code HandlerRef} — so both delegate to one
 * instance of this rather than each hand-rolling the same {@code
 * ConcurrentHashMap} wrapper (a code-review finding).
 */
final class KeyedRegistry<K, V> {

    private final Map<K, V> entries = new ConcurrentHashMap<>();
    private final Function<V, K> keyOf;

    KeyedRegistry(Function<V, K> keyOf) {
        this.keyOf = keyOf;
    }

    void put(V value) {
        entries.put(keyOf.apply(value), value);
    }

    Optional<V> get(K key) {
        return Optional.ofNullable(entries.get(key));
    }

    void remove(K key) {
        entries.remove(key);
    }

    boolean removeIfCurrent(K key, V expected) {
        return entries.remove(key, expected);
    }

    /** A point-in-time snapshot, safe to iterate while the registry is concurrently mutated. */
    Map<K, V> all() {
        return Map.copyOf(entries);
    }

    /**
     * Moves {@code oldKey}'s entry, if any, to {@code newKey} — for a caller
     * whose key identity itself changed underneath it (doc/specs/
     * handler-floor-control.md "Resume resilience and baseline-key
     * migration", issue #29: a {@code HandlerRef} renamed in place by the
     * adapter). A no-op if {@code oldKey} has no entry. If {@code newKey}
     * already holds one, that entry wins — it reflects the current key more
     * directly than whatever was captured under the stale one — and the
     * {@code oldKey} entry is simply dropped rather than overwriting it.
     */
    void migrateKey(K oldKey, K newKey) {
        entries.computeIfPresent(oldKey, (k, oldValue) -> {
            entries.putIfAbsent(newKey, oldValue);
            return null; // removes oldKey
        });
    }
}
