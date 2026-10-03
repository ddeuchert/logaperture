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

import java.util.Objects;

/**
 * A logger under a {@code set logger} target that doesn't follow the target's new level --
 * doc/specs/set-logger-force.md. Reported so the caller can say which loggers stayed behind and
 * why.
 *
 * @param loggerName the descendant
 * @param level      the level it keeps: its own (native or vendor) level, or its override's
 * @param kind       why it doesn't follow
 */
public record DescendantLevel(String loggerName, Level level, Kind kind) {

    public DescendantLevel {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(kind, "kind");
    }

    /** Why a descendant doesn't follow its ancestor's new level. */
    public enum Kind {
        /** It has a level of its own (native config or vendor defaults); {@code --force} would set it. */
        OWN_LEVEL,
        /** An earlier {@code --force} on the same target set it, and it stays tied to that target (F5). */
        FORCED_EARLIER,
        /** It carries an override the operator (or a recipe) made; {@code --force} leaves it alone (F3). */
        OPERATOR_OVERRIDE
    }
}
