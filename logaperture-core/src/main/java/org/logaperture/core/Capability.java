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

/**
 * The capability slice implemented so far — see doc/specs/level-control.md
 * "Capability and audit", doc/specs/persistence.md "Capability and audit",
 * doc/specs/rule-pipeline-foundation.md "Capability and audit", and
 * doc/logaperture-spec.md §9.3 for the full set this is drawn from.
 * {@code capture}/{@code suppress}/{@code guard.override} remain out of
 * scope here.
 */
public enum Capability {
    /** Reading logger names and levels. Low risk. */
    VIEW,
    /** Making a logger more verbose. Data-exposure risk (§9.3). */
    LEVEL_RAISE,
    /** Making a logger less verbose. Evidence-loss risk (§9.3). */
    LEVEL_LOWER,
    /**
     * Making a change outlive the process, rather than expiring with it —
     * required in addition to {@link #LEVEL_RAISE}/{@link #LEVEL_LOWER}
     * (or {@link #HANDLER_RAISE}/{@link #HANDLER_LOWER}, or {@link
     * #RULES_AUTHOR}) whenever a {@code setLogger}/{@code setHandlerLevel}/
     * {@code add rule} call's tier isn't {@code SESSION}
     * (doc/specs/persistence.md).
     */
    PERSIST,
    /**
     * Making a handler's own level more permissive (revealing output) via
     * {@code logctl set handler <name> <level>} — granted independently of
     * {@link #LEVEL_RAISE}/{@link #LEVEL_LOWER}: a handler floor governs
     * every logger routing through it, a wider blast radius than one
     * logger's level (doc/specs/handler-floor-control.md "Capability and
     * audit").
     */
    HANDLER_LOWER,
    /** Making a handler's own level stricter (squelching output). */
    HANDLER_RAISE,
    /**
     * Attaching a content-based {@link org.logaperture.api.LogRule} at
     * runtime — §9.3's "writing arbitrary new rules at runtime... a much
     * larger grant than applying one", required for {@code add rule}
     * regardless of action. Distinct from §9.3's {@code suppress}, which
     * gates the drop/trim actions themselves (doc/specs/
     * rule-pipeline-foundation.md "Capability and audit", Decision #2) and
     * is not yet in this enum — this slice never denies an event on its
     * own, so nothing needs it yet.
     */
    RULES_AUTHOR,
    /**
     * The drop/trim actions themselves, separable from {@link
     * #RULES_AUTHOR} — §9.3 "drop/trim actions specifically, separable
     * from rate limiting". Required in addition to {@link #RULES_AUTHOR}
     * to attach a {@link org.logaperture.api.Drop} (doc/specs/drop-rule.md
     * "Capability and audit", supersedes rule-pipeline-foundation.md's own
     * deferral of this capability to here).
     */
    SUPPRESS
}
