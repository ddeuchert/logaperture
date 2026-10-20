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
 * A level that was set but cannot take effect, because the logging backend enforces a floor less
 * verbose than it -- doc/specs/quarkus-support.md, "Min-level honesty". Quarkus builds a
 * {@code quarkus.log.min-level} into the application: JBoss LogManager then lets nothing more
 * verbose through, whatever a logger's own level says. Advice, not an error: the override is
 * recorded and the logger reads back at the level that was asked for.
 *
 * @param loggerName the logger the level was set on
 * @param requested  the level that was asked for
 * @param lowest     the most verbose level the backend will actually emit for this logger
 * @param hint       what to change to lower the floor, or {@code null} when the backend can't say
 */
public record LevelFloor(String loggerName, Level requested, Level lowest, String hint) {

    public LevelFloor {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(lowest, "lowest");
    }
}
