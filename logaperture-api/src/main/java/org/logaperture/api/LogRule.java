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
 * The shared shape every content-based rule implements — doc/specs/
 * rule-pipeline-foundation.md "Data model". No concrete implementation
 * ships in that foundation slice; {@code Drop} (issue #72) and {@code Trim}
 * (issue #34) are its first two, each adding its own action-specific
 * fields on top of this common set. Deliberately an interface, not a
 * record: unlike {@link LevelOverride} (one concrete shape for every
 * override), a rule has more than one concrete shape sharing this base.
 *
 * <p>Hit counting and the compiled evaluation plan live in {@code core},
 * not here — this type carries only what every rule needs regardless of
 * action, matching the {@code api}/{@code core} split every other
 * framework-independent model in this codebase already follows.
 */
public interface LogRule {

    /** This rule's short, JVM/context-scoped id (e.g. {@code "r1"}) — doc/specs/rule-pipeline-foundation.md "Rule identity". */
    String id();

    /** The logger this rule is attached to — identity, not a per-event check. See "Logger scope and inheritance". */
    String loggerName();

    /** The compiled matcher set every candidate event is checked against. */
    CompiledMatchers matchers();

    /** Propagated to the audit log at attach time; {@code null} if none was given. Mirrors {@code LevelOverride#reason()}. */
    String reason();

    /** How long this rule lasts — {@code SESSION}/{@code FOR}/{@code STICKY}, reused as-is from {@link PersistenceTier}. */
    PersistenceTier tier();

    /** Non-null iff {@link #tier()} is {@link PersistenceTier#FOR}. */
    Instant expiresAt();

    /** When this rule was attached. */
    Instant createdAt();

    /**
     * A short, human-readable name for the action this rule performs (e.g.
     * {@code "drop"}, {@code "trim"}) — rendered by {@code logctl list
     * rules}. Defaults to the concrete class's simple name, which is
     * adequate for this slice's own test double; a real action type
     * (#72/#34) is expected to override it with its own spelling.
     */
    default String actionName() {
        return getClass().getSimpleName();
    }
}
