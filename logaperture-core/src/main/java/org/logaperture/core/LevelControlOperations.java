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
     * Equivalent to {@link #resetLevel(String, boolean) resetLevel(loggerName,
     * false)} — leaves a {@code STICKY}-tier override or standing rule
     * untouched (doc/specs/reset-command-surface.md's new default).
     *
     * @return exactly what was reverted — see {@link ResetOutcome}
     */
    default ResetOutcome resetLevel(String loggerName) {
        return resetLevel(loggerName, false);
    }

    /** Equivalent to {@link #resetLevel(String, boolean, String) resetLevel(loggerName, includeSticky, null)}. */
    default ResetOutcome resetLevel(String loggerName, boolean includeSticky) {
        return resetLevel(loggerName, includeSticky, null);
    }

    /**
     * {@code loggerName} is either an exact logger name or a pattern
     * (doc/specs/pattern-level-targeting.md). A target narrower than an
     * active standing rule's own coverage carves it out of the rule rather
     * than retiring the whole thing or no-op'ing (doc/specs/
     * reset-command-surface.md "Partial reset — scoped exclusions").
     *
     * @param includeSticky {@code false} (the default) leaves a
     *                      {@code STICKY}-tier override, or a
     *                      {@code STICKY}-tier standing rule, untouched;
     *                      {@code true} reverts/excludes it like any other
     *                      tier
     * @param reason        recorded on the audit entry for every logger this
     *                      call actually reverts, in place of the override's
     *                      own reason; {@code null} to leave each reversion's
     *                      audit reason as the override's original one
     * @return exactly what was reverted, retired, excluded, and skipped —
     *         see {@link ResetOutcome}
     */
    ResetOutcome resetLevel(String loggerName, boolean includeSticky, String reason);

    /**
     * Equivalent to {@link #resetAllLoggers(boolean) resetAllLoggers(false)}.
     * Kept as the pre-existing zero-arg entry point ({@code void}, matching
     * every caller that only ever needed "did it throw") — {@link
     * #resetAllLoggers(boolean)} is the one that reports what happened.
     */
    default void resetAll() {
        resetAllLoggers(false);
    }

    /**
     * Reverts every logger override this operations surface controls —
     * handlers are untouched (doc/specs/reset-command-surface.md, {@code
     * logctl reset loggers}). Also retires every standing rule (skipping a
     * {@code STICKY}-tier one unless {@code includeSticky}), same as
     * today's {@code resetAll} did for the logger side.
     *
     * @param includeSticky see {@link #resetLevel(String, boolean)}
     */
    ResetOutcome resetAllLoggers(boolean includeSticky);
}
