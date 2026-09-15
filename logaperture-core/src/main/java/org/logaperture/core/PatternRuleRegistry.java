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

import org.logaperture.api.PatternRule;

import java.util.Map;
import java.util.Optional;

/**
 * One {@link PatternRule} per pattern string — idempotent by construction,
 * same as {@link OverrideRegistry}: {@link #put} replaces any existing rule
 * for that exact pattern rather than accumulating a second one. Two
 * <em>different</em> pattern strings whose match sets overlap for some
 * logger are a separate concern — doc/specs/pattern-level-targeting.md
 * "Precedence" (the newest {@code appliedAt} wins), not this registry's. A
 * thin, typed wrapper over {@link KeyedRegistry}, keyed by {@link
 * PatternRule#pattern()}.
 */
final class PatternRuleRegistry {

    private final KeyedRegistry<String, PatternRule> registry = new KeyedRegistry<>(PatternRule::pattern);

    void put(PatternRule rule) {
        registry.put(rule);
    }

    Optional<PatternRule> get(String pattern) {
        return registry.get(pattern);
    }

    void remove(String pattern) {
        registry.remove(pattern);
    }

    /**
     * Removes {@code pattern} only if its current value still equals
     * {@code expected} — an atomic compare-and-remove guarding a stale
     * caller (the expiry sweep) from clobbering a newer rule for the same
     * pattern, same discipline as {@link OverrideRegistry#removeIfCurrent}.
     */
    boolean removeIfCurrent(String pattern, PatternRule expected) {
        return registry.removeIfCurrent(pattern, expected);
    }

    /**
     * Atomic compare-and-swap for an in-place update — a partial {@code
     * resetLevel} carving a new exclusion into an existing rule (doc/specs/
     * reset-command-surface.md "Partial reset — scoped exclusions") rather
     * than replacing it with an unrelated new one via {@link #put}.
     */
    boolean replaceIfCurrent(String pattern, PatternRule expected, PatternRule replacement) {
        return registry.replaceIfCurrent(pattern, expected, replacement);
    }

    /** A point-in-time snapshot, safe to iterate while the registry is concurrently mutated. */
    Map<String, PatternRule> all() {
        return registry.all();
    }
}
