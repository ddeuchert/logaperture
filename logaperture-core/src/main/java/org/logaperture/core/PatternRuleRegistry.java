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
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link PatternRule} per pattern string — idempotent by construction,
 * same as {@link OverrideRegistry}: {@link #put} replaces any existing rule
 * for that exact pattern rather than accumulating a second one. Two
 * <em>different</em> pattern strings whose match sets overlap for some
 * logger are a separate concern — doc/specs/pattern-level-targeting.md
 * "Precedence" (the newest {@code appliedAt} wins), not this registry's.
 */
final class PatternRuleRegistry {

    private final Map<String, PatternRule> rules = new ConcurrentHashMap<>();

    void put(PatternRule rule) {
        rules.put(rule.pattern(), rule);
    }

    Optional<PatternRule> get(String pattern) {
        return Optional.ofNullable(rules.get(pattern));
    }

    void remove(String pattern) {
        rules.remove(pattern);
    }

    /**
     * Removes {@code pattern} only if its current value still equals
     * {@code expected} — an atomic compare-and-remove guarding a stale
     * caller (the expiry sweep) from clobbering a newer rule for the same
     * pattern, same discipline as {@link OverrideRegistry#removeIfCurrent}.
     */
    boolean removeIfCurrent(String pattern, PatternRule expected) {
        return rules.remove(pattern, expected);
    }

    /** A point-in-time snapshot, safe to iterate while the registry is concurrently mutated. */
    Map<String, PatternRule> all() {
        return Map.copyOf(rules);
    }
}
