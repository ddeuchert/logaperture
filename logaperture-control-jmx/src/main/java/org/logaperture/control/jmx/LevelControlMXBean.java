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
     * {@code target} is either an exact logger name or a segment-anchored
     * pattern (doc/specs/level-control.md's grammar) — a {@code *} present
     * anywhere in it selects the pattern path. A pattern target is a
     * <em>standing rule</em> (doc/specs/pattern-level-targeting.md):
     * persisted per {@code tier}, and (re-)applied to any logger discovered
     * later that it matches, until reset. Replaces the retired {@code
     * includeChildren} flag — {@code "org.apache.*"} covers what {@code
     * includeChildren=true} on {@code "org.apache"} used to.
     *
     * @param tier       {@code "SESSION"}/{@code "FOR"}/{@code "STICKY"}
     * @param forSeconds ignored unless {@code tier} is {@code "FOR"}
     * @param confirmed  required {@code true} for a pattern {@code target}
     *                   — a call with {@code confirmed=false} mutates
     *                   nothing and throws {@code ConfirmationRequiredException}
     *                   naming the currently-known matches instead; ignored
     *                   for an exact-name target, which carries none of a
     *                   standing rule's risk
     * @return every override this call created or replaced (one, for an
     *         exact-name target; zero or more, for a pattern), plus any
     *         handler on one of their paths that will still swallow
     *         records at {@code level} — doc/specs/handler-floor-control.md
     *         "Warning on level commands"
     */
    SetLevelResultData setLevel(String target, String level, String reason, String tier, long forSeconds,
            boolean confirmed);

    /**
     * Equivalent to {@link #resetLevel(String, boolean) resetLevel(target,
     * false)} — kept for wire compatibility with a client built before
     * doc/specs/reset-command-surface.md's {@code includeSticky} landed;
     * note that this changes what this exact call now does, since skipping
     * a {@code STICKY} target is the new default, not a new opt-in (that
     * spec's "Versioning" section).
     *
     * @return exactly what was reverted, retired, excluded, and skipped —
     *         see {@link ResetOutcomeData}
     */
    default ResetOutcomeData resetLevel(String target) {
        return resetLevel(target, false);
    }

    /**
     * {@code target} is either an exact logger name or a pattern
     * (doc/specs/pattern-level-targeting.md). Resetting a pattern that
     * exactly matches a tracked standing rule's own pattern string reverts
     * every logger it currently covers <em>and</em> retires the rule, so it
     * stops covering loggers discovered later too. A narrower target —  an
     * exact name, or a sub-pattern that isn't itself a tracked rule — falls
     * under {@code logctl reset logger}'s partial-reset case instead: it
     * carves {@code target} out of whichever rule currently governs it
     * rather than retiring the whole rule (doc/specs/
     * reset-command-surface.md "Partial reset — scoped exclusions").
     *
     * @param includeSticky {@code false} (the default) leaves a
     *                      {@code STICKY}-tier override, or a
     *                      {@code STICKY}-tier standing rule (whole or
     *                      partial), untouched; {@code true} reverts/
     *                      excludes it like any other tier
     * @return exactly what was reverted, retired, excluded, and skipped —
     *         see {@link ResetOutcomeData}
     */
    default ResetOutcomeData resetLevel(String target, boolean includeSticky) {
        return resetLevel(target, includeSticky, null);
    }

    /**
     * As {@link #resetLevel(String, boolean)}, plus a reason recorded on
     * the audit entry for every logger this call actually reverts —
     * {@code logctl reset logger <target> --reason ...} (doc/specs/
     * reset-command-surface.md "Command grammar").
     *
     * @param reason {@code null}/empty to leave each reversion's audit
     *               reason as the override's original one
     */
    ResetOutcomeData resetLevel(String target, boolean includeSticky, String reason);

    /** Equivalent to {@link #resetAll(boolean) resetAll(false)}; see {@link #resetLevel(String)}'s note on the wire-compatibility caveat. */
    default ResetOutcomeData resetAll() {
        return resetAll(false);
    }

    /**
     * {@code logctl reset --all} (doc/specs/reset-command-surface.md) —
     * reverts every logger <em>and</em> handler override, both namespaces
     * in one call; Decision #1's "get me back to normal shouldn't require
     * remembering two commands."
     *
     * @param includeSticky see {@link #resetLevel(String, boolean)}
     */
    ResetOutcomeData resetAll(boolean includeSticky);

    /**
     * {@code logctl reset loggers} (doc/specs/reset-command-surface.md) —
     * reverts every logger override, handlers untouched.
     *
     * @param includeSticky see {@link #resetLevel(String, boolean)}
     */
    ResetOutcomeData resetAllLoggers(boolean includeSticky);

    /**
     * {@code logctl reset handlers} (doc/specs/reset-command-surface.md) —
     * reverts every handler override, loggers untouched.
     *
     * @param includeSticky see {@link #resetLevel(String, boolean)}
     */
    ResetOutcomeData resetAllHandlers(boolean includeSticky);

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

    /**
     * {@code logctl handler <name> AUTO} — puts {@code handlerRef} into a
     * self-tracking mode whose applied level follows the lowest currently
     * active logger override (doc/specs/handler-floor-control.md "AUTO
     * handler level", issue #20).
     *
     * @param tier       {@code "SESSION"}/{@code "FOR"}/{@code "STICKY"} —
     *                   how long the AUTO mode itself lasts, independent of
     *                   how often its tracked level moves
     * @param forSeconds ignored unless {@code tier} is {@code "FOR"}
     * @return the created override, or {@code null} if this framework's
     *         handlers have no level of their own, or (single-handler only)
     *         neither an active floor nor a captured baseline exists yet to
     *         track — a documented no-op, not an error
     */
    HandlerLevelOverrideData setHandlerAuto(String handlerRef, String reason, String tier, long forSeconds);

    /**
     * Equivalent to {@link #resetHandler(String, boolean) resetHandler(handlerRef,
     * false)}; see {@link #resetLevel(String)}'s note on the wire-compatibility
     * caveat. A no-op, not an error, if {@code handlerRef} has no active override.
     */
    default ResetOutcomeData resetHandler(String handlerRef) {
        return resetHandler(handlerRef, false);
    }

    /**
     * {@code logctl reset handler <name>} (doc/specs/
     * reset-command-surface.md — replaces the retired {@code logctl
     * handler <name> reset}). A no-op, not an error, if {@code handlerRef}
     * has no active override.
     *
     * @param includeSticky see {@link #resetLevel(String, boolean)}
     */
    ResetOutcomeData resetHandler(String handlerRef, boolean includeSticky);

    /**
     * Every handler override currently active, across every registered
     * context — feeds {@code logctl status} (doc/specs/
     * handler-floor-control.md "logctl status shows handler overrides too").
     */
    List<HandlerLevelOverrideData> listHandlerOverrides();

    /**
     * {@code logctl handlers} — the full addressable handler catalog across
     * every registered context (doc/specs/handler-floor-control.md "The
     * handler catalog", issue #15). Read-only; requires only {@code VIEW}.
     */
    List<HandlerInfoData> listHandlers();

    /**
     * {@code logctl doctor} — a read-only configuration diagnosis, across
     * every registered context (doc/specs/doctor.md). Never mutates
     * anything; requires only the {@code VIEW} capability.
     */
    List<DoctorFindingData> diagnose();

    /**
     * {@code logctl top} — byte volume per logger, across every registered
     * context (doc/specs/top.md). Never mutates anything; requires only the
     * {@code VIEW} capability.
     *
     * @param limit keep only the {@code limit} worst offenders; {@code limit
     *              <= 0} means "every tracked logger"
     */
    TopReportData topLoggers(int limit);

    /**
     * {@code logctl env} — a read-only environment report for a bug report
     * (doc/specs/environment-report.md). Never mutates anything; requires
     * only the {@code VIEW} capability. A fact this JVM can't resolve is
     * {@code null}, never a failure.
     */
    EnvironmentReportData environmentReport();
}
