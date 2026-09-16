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

import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;

import java.util.List;

/**
 * Feature 1's public contract — see doc/specs/level-control.md
 * "Operations". Every control surface (JMX now, others later) is a client
 * of this interface; no surface is a privileged path
 * (doc/logaperture-spec.md §8.1).
 */
public interface LevelControlOperations {

    List<LoggerInfo> listLoggers(String filter);

    /**
     * @return the created override, plus any handler on {@code loggerName}'s
     *         path that will still swallow records at {@code level} — doc/specs/
     *         handler-floor-control.md "Warning on level commands"
     */
    SetLevelResult setLevel(String loggerName, Level level, SetLevelOptions options);

    /**
     * {@code target} is an exact logger name or a pattern (doc/specs/
     * pattern-selection-semantics.md). Renamed from {@code resetLevel}
     * (doc/specs/reset-command-surface.md, Decision #2a) now that the CLI
     * verb is {@code reset logger}, never a generic {@code reset}.
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place — new
     *                      default is to skip it (doc/specs/
     *                      reset-command-surface.md)
     * @return exactly what was reverted, and what was left alone for being
     *         sticky — see {@link ResetOutcome}
     * @throws IllegalArgumentException if {@code target} is an exact name
     *                                   whose active override is {@code
     *                                   STICKY} and {@code includeSticky} is
     *                                   {@code false} (Decision #1 — a
     *                                   single named target refuses outright
     *                                   rather than silently skipping)
     */
    ResetOutcome resetLogger(String target, boolean includeSticky);

    /**
     * Reverts every currently-overridden logger — the bulk counterpart to
     * {@link #resetLogger}, replacing the removed {@code resetAll()}
     * (doc/specs/reset-command-surface.md).
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place
     * @return exactly what was reverted, and what was left alone for being
     *         sticky
     */
    ResetOutcome resetAllLoggers(boolean includeSticky);
}
