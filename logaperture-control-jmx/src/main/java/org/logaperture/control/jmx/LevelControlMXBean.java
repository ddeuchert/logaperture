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
     * one-time selection (doc/specs/pattern-selection-semantics.md):
     * resolved once against currently-known loggers and applied to each,
     * nothing left standing afterward. A <em>trailing</em>-star target
     * (e.g. {@code "org.apache.*"}) is rejected outright — every descendant
     * already inherits a set ancestor's level from the logging framework
     * itself, so {@code "org.apache"} alone covers what a trailing star
     * used to.
     *
     * @param tier       {@code "SESSION"}/{@code "FOR"}/{@code "STICKY"}
     * @param forSeconds ignored unless {@code tier} is {@code "FOR"}
     * @param confirmed  required {@code true} for a leading-star pattern
     *                   {@code target} — a call with {@code confirmed=false}
     *                   mutates nothing and throws {@code
     *                   ConfirmationRequiredException} naming the
     *                   currently-known matches instead; ignored for an
     *                   exact-name target, which carries none of a batch
     *                   mutation's risk
     * @return every override this call created or replaced (one, for an
     *         exact-name target; zero or more, for a pattern), plus any
     *         handler on one of their paths that will still swallow
     *         records at {@code level} — doc/specs/handler-floor-control.md
     *         "Warning on level commands"
     */
    SetLevelResultData setLogger(String target, String level, String reason, String tier, long forSeconds,
            boolean confirmed);

    /**
     * {@code target} is either an exact logger name or a pattern (doc/specs/
     * pattern-selection-semantics.md) — resetting a pattern reverts every
     * currently-matched logger that carries an active override, a one-time
     * selection like every other pattern operation. Renamed from {@code
     * resetLevel} (doc/specs/reset-command-surface.md, Decision #2a).
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place (new
     *                      default: skipped)
     * @return exactly what was reverted, and what was left alone for being
     *         sticky — see {@link ResetOutcomeData}
     * @throws IllegalArgumentException if {@code target} is an exact name
     *                                   whose active override is {@code
     *                                   STICKY} and {@code includeSticky} is
     *                                   {@code false}
     */
    ResetOutcomeData resetLogger(String target, boolean includeSticky);

    /**
     * {@code logctl reset loggers} — reverts every currently-overridden
     * logger (doc/specs/reset-command-surface.md), replacing the removed
     * {@code resetAll()}.
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place
     * @return exactly what was reverted, and what was left alone for being
     *         sticky
     */
    ResetOutcomeData resetAllLoggers(boolean includeSticky);

    /**
     * {@code logctl set handler <name> <level>} — doc/specs/
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
     * {@code logctl set handler <name> AUTO} — puts {@code handlerRef} into a
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
     * {@code logctl reset handler <name>}. A no-op, not an error, if {@code
     * handlerRef} has no active override.
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place
     * @return exactly what was reverted, and what was left alone for being
     *         sticky — see {@link HandlerResetOutcomeData}
     * @throws IllegalArgumentException if {@code handlerRef}'s active
     *                                   override is {@code STICKY} and
     *                                   {@code includeSticky} is {@code
     *                                   false}
     */
    HandlerResetOutcomeData resetHandler(String handlerRef, boolean includeSticky);

    /**
     * {@code logctl reset handlers} — reverts every currently-overridden
     * handler (doc/specs/reset-command-surface.md).
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place
     * @return exactly what was reverted, and what was left alone for being
     *         sticky
     */
    HandlerResetOutcomeData resetAllHandlers(boolean includeSticky);

    /**
     * Every handler override currently active, across every registered
     * context — feeds {@code logctl status} (doc/specs/
     * handler-floor-control.md "logctl status shows handler overrides too").
     */
    List<HandlerLevelOverrideData> listHandlerOverrides();

    /**
     * {@code logctl set handlers} — the full addressable handler catalog across
     * every registered context (doc/specs/handler-floor-control.md "The
     * handler catalog", issue #15). Read-only; requires only {@code VIEW}.
     */
    List<HandlerInfoData> listHandlers();

    /**
     * {@code logctl set default-handler <name>...} — assigns {@code
     * DEFAULT_HANDLERS}'s explicit membership, across every registered
     * context (doc/specs/handler-floor-control.md "Default handler group",
     * issue #28). An empty {@code names} clears the explicit assignment,
     * reverting to the deterministic selection rule. Requires only {@link
     * org.logaperture.core.Capability#PERSIST} — membership is always
     * persisted once assigned, there is no lower/raise direction to judge.
     *
     * @param names each must resolve against a real handler; an unresolved
     *              name throws before anything changes
     * @return the new explicit membership, empty when cleared
     */
    List<String> setDefaultHandlerMembers(List<String> names);

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
     * {@code logctl storms} — report-only log storm detection, across every
     * registered context (doc/specs/storm-detection.md). Never mutates
     * anything; requires only the {@code VIEW} capability.
     *
     * @param limit keep only the {@code limit} worst storms; {@code limit <=
     *              0} means "every tracked storm"
     */
    StormReportData activeStorms(int limit);

    /**
     * {@code logctl env} — a read-only environment report for a bug report
     * (doc/specs/environment-report.md). Never mutates anything; requires
     * only the {@code VIEW} capability. A fact this JVM can't resolve is
     * {@code null}, never a failure.
     */
    EnvironmentReportData environmentReport();

    /**
     * {@code logctl list rules} — every attached content-based rule, across
     * every registered context (doc/specs/rule-pipeline-foundation.md
     * "Command surface"). No concrete rule type ships in this slice, so this
     * is always empty until #72/#34 add one. Requires only {@code VIEW}.
     */
    List<RuleData> listRules();

    /**
     * {@code logctl reset rule <id>} — a single named id refuses if it's
     * {@code STICKY} and {@code includeSticky} wasn't passed.
     *
     * @return the removed rule, or {@code null} if no rule with this id
     *         exists (a no-op, not an error)
     * @throws IllegalArgumentException if the rule is {@code STICKY} and
     *                                   {@code includeSticky} is {@code false}
     */
    RuleData resetRule(String id, boolean includeSticky);

    /**
     * {@code logctl reset rules} — removes every attached rule, across
     * every registered context.
     *
     * @param includeSticky whether a {@code STICKY}-tier rule is removed
     *                      too, instead of left in place
     */
    RuleResetOutcomeData resetAllRules(boolean includeSticky);

    /**
     * {@code logctl reset logger X}'s rule-removal side effect (doc/specs/
     * rule-pipeline-foundation.md "Command surface") — removes every rule
     * attached <em>directly</em> to {@code loggerName}, not its descendants'
     * own separately-attached rules.
     */
    RuleResetOutcomeData resetRulesForLogger(String loggerName, boolean includeSticky);

    /**
     * {@code logctl add rule drop} — doc/specs/drop-rule.md "Command
     * surface". {@code target} is an exact logger name (leading-star
     * pattern-target expansion is deferred past this pass — see that
     * spec's "Divergence from prior specs"). {@code belowLevel} is the
     * keep-floor bound already resolved to a real {@link
     * org.logaperture.api.Level} name by the caller (the CLI/JMX boundary
     * accepts the {@code FATAL} pseudo-token client-side, since {@code
     * Level} itself has none — see that same section). At least one of
     * {@code messageContains}/{@code throwableType}/{@code
     * throwableMessageContains} must be non-null.
     *
     * @throws IllegalArgumentException if no content matcher is given, or
     *                                   {@code target} is a protected
     *                                   category
     */
    RuleData addRuleDrop(String target, String messageContains, boolean messageIgnoreCase, String throwableType,
            String throwableMessageContains, boolean anyCause, String belowLevel, boolean sampleFullEnabled,
            long sampleFullEveryMillis, String reason, String tier, long forSeconds);

    /**
     * {@code logctl add rule trim} — doc/specs/trim-rule.md "Command
     * surface". {@code target} is an exact logger name (leading-star
     * pattern-target expansion deferred, same as {@link #addRuleDrop}).
     * Unlike {@code addRuleDrop}, no content matcher is required — a bare
     * level-bounded trim is a first-class case (doc/specs/trim-rule.md
     * "Matchers in use").
     *
     * @throws IllegalArgumentException if {@code target} is a protected
     *                                   category, or {@code frames} is
     *                                   negative
     */
    RuleData addRuleTrim(String target, String messageContains, boolean messageIgnoreCase, String throwableType,
            String throwableMessageContains, boolean anyCause, String belowLevel, int frames,
            boolean collapseCauses, String reason, String tier, long forSeconds);
}
