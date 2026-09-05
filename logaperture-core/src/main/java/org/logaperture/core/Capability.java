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
 * and doc/logaperture-spec.md §9.3 for the full set this is drawn from.
 * {@code capture}/{@code rules.*}/{@code suppress}/{@code guard.override}
 * remain out of scope here.
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
     * (or {@link #HANDLER_RAISE}/{@link #HANDLER_LOWER}) whenever a {@code
     * setLevel}/{@code setHandlerLevel} call's tier isn't {@code SESSION}
     * (doc/specs/persistence.md).
     */
    PERSIST,
    /**
     * Making a handler's own level more permissive (revealing output) via
     * {@code logctl handler <name> <level>} — granted independently of
     * {@link #LEVEL_RAISE}/{@link #LEVEL_LOWER}: a handler floor governs
     * every logger routing through it, a wider blast radius than one
     * logger's level (doc/specs/handler-floor-control.md "Capability and
     * audit").
     */
    HANDLER_LOWER,
    /** Making a handler's own level stricter (squelching output). */
    HANDLER_RAISE
}
