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
package org.logaperture.cli;

import org.logaperture.control.jmx.DoctorFindingData;
import org.logaperture.control.jmx.EnvironmentReportData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.HandlerResetOutcomeData;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.ResetOutcomeData;
import org.logaperture.control.jmx.RuleAlterationData;
import org.logaperture.control.jmx.RuleData;
import org.logaperture.control.jmx.RuleResetOutcomeData;
import org.logaperture.control.jmx.SetLevelResultData;
import org.logaperture.control.jmx.StormReportData;
import org.logaperture.control.jmx.TopReportData;

import java.util.ArrayList;
import java.util.List;

/** A hand-written {@link LevelControlMXBean} test double — records calls, returns what a test wires up. */
final class FakeLevelControlMXBean implements LevelControlMXBean {

    final List<String> listLoggersFilters = new ArrayList<>();
    final List<Object[]> setLevelCalls = new ArrayList<>();
    final List<String> resetLevelCalls = new ArrayList<>();
    final List<Object[]> setHandlerLevelCalls = new ArrayList<>();
    final List<Object[]> setHandlerAutoCalls = new ArrayList<>();
    final List<String> resetHandlerCalls = new ArrayList<>();
    /** Names dropped from {@link #loggers} when {@link #resetLogger} clears them — a "Known" but not "Live" logger. */
    final List<String> forgetOnReset = new ArrayList<>();
    int resetAllLoggersCalls;
    int resetAllHandlersCalls;
    boolean lastIncludeSticky;
    /** The {@code toNative} of the last reset that took one (doc/specs/reset-to-native.md). */
    boolean lastToNative;
    /** Whether the fake's {@link #resetHandler} simulates an active override to revert. */
    boolean handlerHasOverrideToReset = true;

    List<LoggerInfoData> loggers = new ArrayList<>();
    List<HandlerLevelOverrideData> handlerOverrides = new ArrayList<>();
    List<DoctorFindingData> findings = new ArrayList<>();
    TopReportData topReport = new TopReportData(new ArrayList<>(), null, 0);
    final List<Integer> topLoggersLimits = new ArrayList<>();
    SetLevelResultData setLevelResult;
    HandlerLevelOverrideData setHandlerLevelResult;
    HandlerLevelOverrideData setHandlerAutoResult;
    RuntimeException throwOnNextCall;

    @Override
    public List<LoggerInfoData> listLoggers(String filter) {
        listLoggersFilters.add(filter);
        maybeThrow();
        if (filter == null) {
            return loggers;
        }
        List<LoggerInfoData> matches = new ArrayList<>();
        for (LoggerInfoData logger : loggers) {
            if (matchesFilter(filter, logger.getName())) {
                matches.add(logger);
            }
        }
        return matches;
    }

    @Override
    public SetLevelResultData setLogger(String target, String level, String reason, String tier, long forSeconds,
            boolean confirmed) {
        setLevelCalls.add(new Object[] {target, level, reason, tier, forSeconds, confirmed});
        maybeThrow();
        if (target.indexOf('*') >= 0 && target.endsWith(".*")) {
            // Mirrors LevelControlService's trailing-wildcard rejection
            // (doc/specs/pattern-selection-semantics.md, Decision #5)
            // closely enough for CommandsTest to exercise the CLI's
            // straight-to-the-server path for this target shape.
            String ancestor = target.substring(0, target.length() - 2);
            throw new IllegalArgumentException("'" + target + "': a trailing wildcard isn't accepted for a "
                    + "level-setting command. Every descendant of '" + ancestor + "' already inherits its level "
                    + "from the logging framework once '" + ancestor + "' itself is set -- run 'logctl "
                    + level.toLowerCase(java.util.Locale.ROOT) + " " + ancestor + "' instead.");
        }
        if (target.indexOf('*') >= 0 && !confirmed) {
            // Mirrors the real LevelControlService's confirmation gate
            // (doc/specs/pattern-level-targeting.md) closely enough for
            // CommandsTest to exercise the CLI's preview-then-apply flow
            // end-to-end against this fake.
            List<String> matchedNames = new ArrayList<>();
            for (LoggerInfoData logger : loggers) {
                if (matchesFilter(target, logger.getName())) {
                    matchedNames.add(logger.getName());
                }
            }
            throw new org.logaperture.core.ConfirmationRequiredException(target, matchedNames);
        }
        return setLevelResult;
    }

    /**
     * A deliberately small stand-in for {@code NameFilter}'s real grammar
     * (package-private to {@code logaperture-core}, not reachable from this
     * module) — just enough of it (segment-anchored, zero-or-more) for this
     * fake's {@code listLoggers}/{@code setLogger} to behave sensibly against
     * a pattern in CLI-level tests; the full grammar is unit-tested against
     * the real engine in {@code logaperture-core}'s {@code NameFilterTest}.
     */
    private static boolean matchesFilter(String filter, String name) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (filter.indexOf('*') < 0) {
            return name.startsWith(filter);
        }
        String[] segments = filter.split("\\.", -1);
        boolean leadingStar = segments[0].equals("*");
        boolean trailingStar = segments[segments.length - 1].equals("*");
        int start = leadingStar ? 1 : 0;
        int end = trailingStar ? segments.length - 1 : segments.length;
        StringBuilder regex = new StringBuilder("^");
        if (leadingStar) {
            regex.append("([^.]+\\.)*");
        }
        for (int i = start; i < end; i++) {
            if (i > start) {
                regex.append("\\.");
            }
            regex.append(java.util.regex.Pattern.quote(segments[i]));
        }
        if (trailingStar) {
            regex.append("(\\.[^.]+)*");
        }
        return name.matches(regex.append('$').toString());
    }

    @Override
    public ResetOutcomeData resetLogger(String target, boolean includeSticky) {
        resetLevelCalls.add(target);
        lastToNative = false;
        lastIncludeSticky = includeSticky;
        maybeThrow();
        if (target.indexOf('*') >= 0) {
            return resetMatching(row -> matchesFilter(target, row.getName()), includeSticky);
        }
        LoggerInfoData row = loggers.stream().filter(r -> r.getName().equals(target)).findFirst().orElse(null);
        if (row != null && row.isOverrideActive()) {
            if ("STICKY".equals(row.getTier()) && !includeSticky) {
                // Decision #1 (doc/specs/reset-command-surface.md): a single
                // named sticky target refuses outright.
                throw new IllegalArgumentException(
                        "'" + target + "' is STICKY -- reset refused without --include-sticky.");
            }
        }
        if (forgetOnReset.contains(target)) {
            loggers.removeIf(logger -> logger.getName().equals(target));
            return new ResetOutcomeData(List.of(target), List.of());
        }
        return new ResetOutcomeData(List.of(), List.of());
    }

    @Override
    public ResetOutcomeData resetLogger(String target, boolean includeSticky, boolean toNative) {
        ResetOutcomeData outcome = resetLogger(target, includeSticky);
        lastToNative = toNative;
        return outcome;
    }

    @Override
    public ResetOutcomeData resetAllLoggers(boolean includeSticky, boolean toNative) {
        ResetOutcomeData outcome = resetAllLoggers(includeSticky);
        lastToNative = toNative;
        return outcome;
    }

    @Override
    public ResetOutcomeData resetAllLoggers(boolean includeSticky) {
        resetAllLoggersCalls++;
        lastToNative = false;
        lastIncludeSticky = includeSticky;
        maybeThrow();
        return resetMatching(row -> true, includeSticky);
    }

    /**
     * Simulates reverting every currently-active-override row {@code
     * matches} selects (doc/specs/pattern-selection-semantics.md /
     * reset-command-surface.md): a {@code STICKY} row is left alone and
     * reported instead of reverted unless {@code includeSticky}. Shared by
     * the pattern branch of {@link #resetLogger} and {@link
     * #resetAllLoggers} -- both are "revert whatever currently qualifies"
     * operations over the same {@link #loggers} list.
     */
    private ResetOutcomeData resetMatching(java.util.function.Predicate<LoggerInfoData> matches,
            boolean includeSticky) {
        List<LoggerInfoData> updated = new ArrayList<>();
        List<String> revertedNames = new ArrayList<>();
        List<String> skippedStickyNames = new ArrayList<>();
        for (LoggerInfoData row : loggers) {
            if (matches.test(row) && row.isOverrideActive()) {
                if ("STICKY".equals(row.getTier()) && !includeSticky) {
                    skippedStickyNames.add(row.getName());
                    updated.add(row);
                    continue;
                }
                String baseline = row.getConfiguredLevel() != null ? row.getConfiguredLevel() : "INFO";
                updated.add(new LoggerInfoData(row.getName(), row.getConfiguredLevel(), baseline,
                        false, null, null, null, null, row.getContext()));
                revertedNames.add(row.getName());
            } else {
                updated.add(row);
            }
        }
        loggers = updated;
        return new ResetOutcomeData(revertedNames, skippedStickyNames);
    }

    @Override
    public HandlerLevelOverrideData setHandlerLevel(String handlerRef, String level, String reason, String tier,
            long forSeconds) {
        setHandlerLevelCalls.add(new Object[] {handlerRef, level, reason, tier, forSeconds});
        maybeThrow();
        return setHandlerLevelResult;
    }

    @Override
    public HandlerLevelOverrideData setHandlerAuto(String handlerRef, String reason, String tier, long forSeconds) {
        setHandlerAutoCalls.add(new Object[] {handlerRef, reason, tier, forSeconds});
        maybeThrow();
        return setHandlerAutoResult;
    }

    @Override
    public HandlerResetOutcomeData resetHandler(String handlerRef, boolean includeSticky) {
        resetHandlerCalls.add(handlerRef);
        lastToNative = false;
        lastIncludeSticky = includeSticky;
        maybeThrow();
        return handlerHasOverrideToReset
                ? new HandlerResetOutcomeData(List.of(handlerRef), List.of())
                : new HandlerResetOutcomeData(List.of(), List.of());
    }

    @Override
    public HandlerResetOutcomeData resetHandler(String handlerRef, boolean includeSticky, boolean toNative) {
        HandlerResetOutcomeData outcome = resetHandler(handlerRef, includeSticky);
        lastToNative = toNative;
        return outcome;
    }

    @Override
    public HandlerResetOutcomeData resetAllHandlers(boolean includeSticky, boolean toNative) {
        HandlerResetOutcomeData outcome = resetAllHandlers(includeSticky);
        lastToNative = toNative;
        return outcome;
    }

    @Override
    public HandlerResetOutcomeData resetAllHandlers(boolean includeSticky) {
        resetAllHandlersCalls++;
        lastToNative = false;
        lastIncludeSticky = includeSticky;
        maybeThrow();
        return handlerHasOverrideToReset
                ? new HandlerResetOutcomeData(List.of("CONSOLE"), List.of())
                : new HandlerResetOutcomeData(List.of(), List.of());
    }

    @Override
    public List<HandlerLevelOverrideData> listHandlerOverrides() {
        maybeThrow();
        return handlerOverrides;
    }

    List<org.logaperture.control.jmx.HandlerInfoData> handlerCatalog = new ArrayList<>();

    @Override
    public List<org.logaperture.control.jmx.HandlerInfoData> listHandlers() {
        maybeThrow();
        return handlerCatalog;
    }

    final List<List<String>> setDefaultHandlerMembersCalls = new ArrayList<>();
    List<String> defaultHandlerMembersResult = new ArrayList<>();

    @Override
    public List<String> setDefaultHandlerMembers(List<String> names) {
        maybeThrow();
        setDefaultHandlerMembersCalls.add(names);
        return defaultHandlerMembersResult;
    }

    final List<Boolean> resetDefaultHandlerCalls = new ArrayList<>();

    @Override
    public List<String> resetDefaultHandler(boolean toNative) {
        maybeThrow();
        resetDefaultHandlerCalls.add(toNative);
        return defaultHandlerMembersResult;
    }

    @Override
    public List<DoctorFindingData> diagnose() {
        maybeThrow();
        return findings;
    }

    @Override
    public TopReportData topLoggers(int limit) {
        topLoggersLimits.add(limit);
        maybeThrow();
        return topReport;
    }

    EnvironmentReportData environmentReport = new EnvironmentReportData("0.1.0-alpha.2", "21.0.4",
            "Eclipse Adoptium", "Linux", "6.10.3", "x86_64", null, null, null, null, null, null);
    int environmentReportCalls;

    @Override
    public EnvironmentReportData environmentReport() {
        environmentReportCalls++;
        maybeThrow();
        return environmentReport;
    }

    StormReportData stormReport = new StormReportData(new ArrayList<>(), 0, 0, null, 0);
    final List<Integer> activeStormsLimits = new ArrayList<>();

    @Override
    public StormReportData activeStorms(int limit) {
        activeStormsLimits.add(limit);
        maybeThrow();
        return stormReport;
    }

    List<RuleData> rules = new ArrayList<>();
    final List<Object[]> resetRuleCalls = new ArrayList<>();
    RuleData resetRuleResult;
    int resetAllRulesCalls;
    RuleResetOutcomeData resetAllRulesResult = new RuleResetOutcomeData(List.of(), List.of());
    final List<Object[]> resetRulesForLoggerCalls = new ArrayList<>();
    RuleResetOutcomeData resetRulesForLoggerResult = new RuleResetOutcomeData(List.of(), List.of());

    @Override
    public List<RuleData> listRules() {
        maybeThrow();
        return rules;
    }

    @Override
    public RuleData resetRule(String id, boolean includeSticky) {
        resetRuleCalls.add(new Object[] {id, includeSticky});
        maybeThrow();
        return resetRuleResult;
    }

    @Override
    public RuleResetOutcomeData resetAllRules(boolean includeSticky) {
        resetAllRulesCalls++;
        lastIncludeSticky = includeSticky;
        maybeThrow();
        return resetAllRulesResult;
    }

    @Override
    public RuleResetOutcomeData resetRulesForLogger(String loggerName, boolean includeSticky) {
        resetRulesForLoggerCalls.add(new Object[] {loggerName, includeSticky});
        maybeThrow();
        return resetRulesForLoggerResult;
    }

    @Override
    public RuleData resetRule(String id, boolean includeSticky, boolean toNative) {
        lastToNative = toNative;
        return resetRule(id, includeSticky);
    }

    @Override
    public RuleResetOutcomeData resetAllRules(boolean includeSticky, boolean toNative) {
        lastToNative = toNative;
        return resetAllRules(includeSticky);
    }

    @Override
    public RuleResetOutcomeData resetRulesForLogger(String loggerName, boolean includeSticky, boolean toNative) {
        lastToNative = toNative;
        return resetRulesForLogger(loggerName, includeSticky);
    }

    /** Each {@link #alterRule} call's arguments, in parameter order. */
    final List<Object[]> alterRuleCalls = new ArrayList<>();
    RuleAlterationData alterRuleResult;

    @Override
    public RuleAlterationData alterRule(String id, String messageContains, boolean messageIgnoreCase,
            boolean clearMessage, String throwableType, boolean clearThrowable, String throwableMessageContains,
            boolean clearThrowableMessage, Boolean anyCause, String belowLevel, Boolean sampleFullEnabled,
            Long sampleFullEveryMillis, Integer frames, Boolean collapseCauses, String reason, String tier,
            long forSeconds) {
        alterRuleCalls.add(new Object[] {id, messageContains, messageIgnoreCase, clearMessage, throwableType,
                clearThrowable, throwableMessageContains, clearThrowableMessage, anyCause, belowLevel,
                sampleFullEnabled, sampleFullEveryMillis, frames, collapseCauses, reason, tier, forSeconds});
        maybeThrow();
        return alterRuleResult;
    }

    final List<Object[]> addRuleDropCalls = new ArrayList<>();
    RuleData addRuleDropResult;

    @Override
    public RuleData addRuleDrop(String target, String messageContains, boolean messageIgnoreCase,
            String throwableType, String throwableMessageContains, boolean anyCause, String belowLevel,
            boolean sampleFullEnabled, long sampleFullEveryMillis, String reason, String tier, long forSeconds) {
        addRuleDropCalls.add(new Object[] {target, messageContains, messageIgnoreCase, throwableType,
                throwableMessageContains, anyCause, belowLevel, sampleFullEnabled, sampleFullEveryMillis, reason,
                tier, forSeconds});
        maybeThrow();
        return addRuleDropResult;
    }

    final List<Object[]> addRuleTrimCalls = new ArrayList<>();
    RuleData addRuleTrimResult;

    @Override
    public RuleData addRuleTrim(String target, String messageContains, boolean messageIgnoreCase,
            String throwableType, String throwableMessageContains, boolean anyCause, String belowLevel, int frames,
            boolean collapseCauses, String reason, String tier, long forSeconds) {
        addRuleTrimCalls.add(new Object[] {target, messageContains, messageIgnoreCase, throwableType,
                throwableMessageContains, anyCause, belowLevel, frames, collapseCauses, reason, tier, forSeconds});
        maybeThrow();
        return addRuleTrimResult;
    }

    private void maybeThrow() {
        if (throwOnNextCall != null) {
            RuntimeException toThrow = throwOnNextCall;
            throwOnNextCall = null;
            throw toThrow;
        }
    }

    String exportVendorDefaultsResult = "schemaVersion: 1\n";
    int exportVendorDefaultsCalls;

    @Override
    public String exportVendorDefaults() {
        exportVendorDefaultsCalls++;
        maybeThrow();
        return exportVendorDefaultsResult;
    }
}
