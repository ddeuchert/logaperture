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
package org.logaperture.core.spi;

import org.logaperture.api.Level;

import java.util.List;
import java.util.Optional;

/**
 * The one extension point {@code core} defines into a real logging
 * framework — see doc/logaperture-spec.md §4.6. Everything in {@code
 * logaperture-core} is written and tested against this interface alone;
 * {@code logaperture-adapter-logback} is the only module allowed to
 * implement it with real framework calls.
 *
 * <p>Deliberately four methods, no {@code includeChildren} parameter here:
 * fan-out to descendants is {@code core}'s job (pure string logic over
 * already-known names via {@code LoggerHierarchy}), not the adapter's —
 * keeps the adapter a dumb per-logger getter/setter.
 */
public interface LoggingAdapter {

    /** Every logger name the underlying framework currently knows about. */
    List<String> knownLoggerNames();

    /**
     * The framework's own configured level for this logger, as a
     * side-effecting "observe" (matching frameworks like Logback, where
     * asking about a logger by name creates it if it doesn't exist yet —
     * see doc/specs/level-control.md's "Known" state).
     *
     * @return the explicit level, or empty if the logger inherits (has no
     *         explicit level of its own)
     */
    Optional<Level> configuredLevel(String loggerName);

    /** The level actually in effect right now, after hierarchy resolution. */
    Level effectiveLevel(String loggerName);

    /**
     * Applies a level to this logger. {@code null} clears back to
     * inherited. Must be safely re-invokable — see the re-appliability note
     * in doc/specs/level-control.md.
     */
    void applyLevel(String loggerName, Level level);
}
