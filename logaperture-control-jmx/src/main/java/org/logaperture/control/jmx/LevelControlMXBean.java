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
package org.logaperture.control.jmx;

import java.util.List;

/**
 * The JMX control surface — "the reference implementation; everything else
 * is a convenience over it" (doc/logaperture-spec.md §8.1). Levels are
 * plain {@code String}s at this boundary, for maximum compatibility with
 * generic JMX tooling (e.g. {@code jconsole}'s manual-invoke form) and to
 * sidestep any doubt about enum-type marshaling. {@code tier} follows the
 * same convention, per doc/specs/persistence.md's "JMX surface changes".
 */
public interface LevelControlMXBean {

    List<LoggerInfoData> listLoggers(String filter);

    /**
     * @param tier       {@code "SESSION"}/{@code "FOR"}/{@code "STICKY"}
     * @param forSeconds ignored unless {@code tier} is {@code "FOR"}
     * @return the created override, plus any handler on {@code loggerName}'s
     *         path that will still swallow records at {@code level} — doc/specs/
     *         handler-floor-control.md "Warning on level commands"
     */
    SetLevelResultData setLevel(String loggerName, String level, boolean includeChildren, String reason,
            String tier, long forSeconds);

    void resetLevel(String loggerName);

    void resetAll();

    /**
     * {@code logctl handler <name> <level>} — doc/specs/
     * handler-floor-control.md "The operation".
     *
     * @param handlerRef the handler's configured name, or its identity-hash
     *                   fallback token
     * @param tier       {@code "SESSION"}/{@code "FOR"}/{@code "STICKY"}
     * @param forSeconds ignored unless {@code tier} is {@code "FOR"}
     * @return the created override, or {@code null} if this framework's
     *         handlers have no level of their own (Logback, {@code none}) —
     *         a documented no-op, not an error (doc/specs/
     *         handler-floor-control.md "Logback / none")
     */
    HandlerLevelOverrideData setHandlerLevel(String handlerRef, String level, String reason, String tier,
            long forSeconds);

    /** {@code logctl handler <name> reset}. A no-op, not an error, if {@code handlerRef} has no active override. */
    void resetHandler(String handlerRef);
}
