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
 * A single logger's state, as returned by {@code listLoggers} — see
 * doc/specs/level-control.md "Data model", extended by
 * doc/specs/persistence.md so a caller can see an active override's
 * durability tier and, for a timed one, when it reverts, without a second
 * surface (persistence.md's "JMX surface changes").
 *
 * @param name             the logger's name
 * @param configuredLevel  the framework's own baseline value, captured on
 *                         adapter install; {@code null} if never captured
 *                         (the logger was inherited, with no explicit level
 *                         of its own, at capture time)
 * @param effectiveLevel   the level actually in effect right now, after
 *                         hierarchy and any active override — never {@code
 *                         null}
 * @param overrideActive   whether a {@link LevelOverride} is currently
 *                         applied to this logger
 * @param overrideSource   the override's source (e.g. {@code "jmx"}); {@code
 *                         null} if {@code overrideActive} is {@code false}
 * @param overrideReason   the override's recorded reason; {@code null} if
 *                         {@code overrideActive} is {@code false} or no
 *                         reason was given
 * @param overrideTier     the override's durability tier; {@code null} if
 *                         {@code overrideActive} is {@code false}
 * @param overrideExpiresAt the override's revert deadline; {@code null}
 *                         unless {@code overrideActive} is {@code true} and
 *                         {@code overrideTier} is {@link
 *                         PersistenceTier#FOR}
 * @param context          the owning logging context's stable key (e.g.
 *                         {@code "system"}) — see doc/specs/wildfly-support.md.
 *                         {@code null} on a row produced by a single-context
 *                         service directly; {@code AggregateLevelControl}
 *                         stamps the real key ({@link #withContext}) on every
 *                         row it returns, so it is never {@code null} on the
 *                         control-plane path
 * @param cascading        whether this override is governed by a live
 *                         standing rule — it will keep propagating to
 *                         newly-discovered descendants until the rule is
 *                         retired or excluded from (doc/specs/
 *                         reset-command-surface.md "Marking a rule-governed
 *                         override"). Deliberately never names <em>which</em>
 *                         rule — that's exactly the provenance-recall a
 *                         caller shouldn't need (Decision #2/#6); {@code
 *                         false} whenever {@code overrideActive} is
 *                         {@code false}
 */
public record LoggerInfo(
        String name,
        Level configuredLevel,
        Level effectiveLevel,
        boolean overrideActive,
        String overrideSource,
        String overrideReason,
        PersistenceTier overrideTier,
        Instant overrideExpiresAt,
        String context,
        boolean cascading) {

    public LoggerInfo {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be null or empty");
        }
        if (effectiveLevel == null) {
            throw new IllegalArgumentException("effectiveLevel must not be null");
        }
    }

    /**
     * A single-context service builds its rows without a context key or a
     * cascading flag; {@code AggregateLevelControl} fills the context in
     * afterwards ({@link #withContext}). Keeps every pre-{@code cascading}
     * {@code new LoggerInfo(...)} call site unchanged.
     */
    public LoggerInfo(
            String name,
            Level configuredLevel,
            Level effectiveLevel,
            boolean overrideActive,
            String overrideSource,
            String overrideReason,
            PersistenceTier overrideTier,
            Instant overrideExpiresAt) {
        this(name, configuredLevel, effectiveLevel, overrideActive, overrideSource, overrideReason,
                overrideTier, overrideExpiresAt, null, false);
    }

    /**
     * As above, plus whether this row's override is rule-governed
     * (doc/specs/reset-command-surface.md) — the constructor a single-context
     * service actually uses once it has that answer to give.
     */
    public LoggerInfo(
            String name,
            Level configuredLevel,
            Level effectiveLevel,
            boolean overrideActive,
            String overrideSource,
            String overrideReason,
            PersistenceTier overrideTier,
            Instant overrideExpiresAt,
            boolean cascading) {
        this(name, configuredLevel, effectiveLevel, overrideActive, overrideSource, overrideReason,
                overrideTier, overrideExpiresAt, null, cascading);
    }

    /** This same row, tagged with its owning context's stable key. */
    public LoggerInfo withContext(String context) {
        return new LoggerInfo(name, configuredLevel, effectiveLevel, overrideActive, overrideSource,
                overrideReason, overrideTier, overrideExpiresAt, context, cascading);
    }
}
