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
package org.logaperture.api;

import java.time.Instant;

/**
 * A standing rule — doc/specs/pattern-level-targeting.md: a segment-anchored
 * pattern with a declared level, persisted per its tier and (re-)applied by
 * the periodic sweep to every logger it matches, including one discovered
 * after the rule was created. Pure state, like {@link LevelOverride} —
 * applying it to a matched logger produces that logger's own independent
 * {@link LevelOverride}, tagged via {@link LevelOverride#originPattern()}.
 *
 * @param pattern   the segment-anchored pattern, verbatim (doc/specs/
 *                  level-control.md's grammar)
 * @param level     the level every currently- and future-matching logger
 *                  gets
 * @param reason    human-readable justification; {@code null} if none was
 *                  given
 * @param appliedAt when this rule was created or last replaced — the newest
 *                  {@code appliedAt} among rules matching a given logger
 *                  wins (doc/specs/pattern-level-targeting.md "Precedence")
 * @param source    the control surface that created it (e.g. {@code "jmx"})
 * @param tier      the durability tier this rule was set at — same
 *                  vocabulary as {@link LevelOverride#tier()}, reused
 *                  as-is rather than inventing pattern-specific durability
 *                  language
 * @param expiresAt the absolute deadline this rule (and every override it
 *                  produced) reverts and retires at; {@code null} unless
 *                  {@code tier} is {@link PersistenceTier#FOR}
 */
public record PatternRule(
        String pattern,
        Level level,
        String reason,
        Instant appliedAt,
        String source,
        PersistenceTier tier,
        Instant expiresAt) {

    public PatternRule {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be null or empty");
        }
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        if (appliedAt == null) {
            throw new IllegalArgumentException("appliedAt must not be null");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source must not be null or empty");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        if (tier == PersistenceTier.FOR) {
            if (expiresAt == null) {
                throw new IllegalArgumentException("tier FOR requires a non-null expiresAt");
            }
        } else if (expiresAt != null) {
            throw new IllegalArgumentException("expiresAt must be null unless tier is FOR");
        }
    }
}
