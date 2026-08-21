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
 * A recorded level override — pure state, deliberately. This type
 * describes an override; it is not itself an install action. The adapter
 * that applies a {@code LevelOverride} to a live logging framework (see
 * {@code logaperture-core}'s {@code OverrideApplier}) can be invoked more
 * than once for the same instance, safely — first application and
 * re-application after a framework reset look identical from here. See
 * doc/specs/level-control.md's re-appliability note.
 *
 * @param loggerName      the logger this override targets
 * @param level           the level to apply
 * @param includeChildren whether this override was also fanned out to
 *                        descendants at the time it was created (each
 *                        descendant gets its own independent {@code
 *                        LevelOverride}, not a shared one — see {@code
 *                        LevelControlService})
 * @param reason          human-readable justification; {@code null} if none
 *                        was given
 * @param appliedAt       when this override was created
 * @param source          the control surface that created it (e.g. {@code
 *                        "jmx"})
 */
public record LevelOverride(
        String loggerName,
        Level level,
        boolean includeChildren,
        String reason,
        Instant appliedAt,
        String source) {

    public LevelOverride {
        if (loggerName == null || loggerName.isEmpty()) {
            throw new IllegalArgumentException("loggerName must not be null or empty");
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
    }
}
