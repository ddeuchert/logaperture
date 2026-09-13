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
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;
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
    /** Names dropped from {@link #loggers} when {@link #resetLevel} clears them — a "Known" but not "Live" logger. */
    final List<String> forgetOnReset = new ArrayList<>();
    int resetAllCalls;

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
    public SetLevelResultData setLevel(String target, String level, String reason, String tier, long forSeconds,
            boolean confirmed) {
        setLevelCalls.add(new Object[] {target, level, reason, tier, forSeconds, confirmed});
        maybeThrow();
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
     * fake's {@code listLoggers}/{@code setLevel} to behave sensibly against
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
    public void resetLevel(String target) {
        resetLevelCalls.add(target);
        maybeThrow();
        if (target.indexOf('*') >= 0) {
            // Simulates a real pattern reset's effect (doc/specs/
            // pattern-level-targeting.md) well enough for CommandsTest's
            // before/after rendering: every currently-active match reverts
            // to its configured (baseline) level, everything else is
            // untouched.
            List<LoggerInfoData> reverted = new ArrayList<>();
            for (LoggerInfoData row : loggers) {
                if (matchesFilter(target, row.getName()) && row.isOverrideActive()) {
                    String baseline = row.getConfiguredLevel() != null ? row.getConfiguredLevel() : "INFO";
                    reverted.add(new LoggerInfoData(row.getName(), row.getConfiguredLevel(), baseline,
                            false, null, null, null, null, row.getContext()));
                } else {
                    reverted.add(row);
                }
            }
            loggers = reverted;
            return;
        }
        if (forgetOnReset.contains(target)) {
            loggers.removeIf(logger -> logger.getName().equals(target));
        }
    }

    @Override
    public void resetAll() {
        resetAllCalls++;
        maybeThrow();
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
    public void resetHandler(String handlerRef) {
        resetHandlerCalls.add(handlerRef);
        maybeThrow();
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

    private void maybeThrow() {
        if (throwOnNextCall != null) {
            RuntimeException toThrow = throwOnNextCall;
            throwOnNextCall = null;
            throw toThrow;
        }
    }
}
