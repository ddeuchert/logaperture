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

import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;

import java.util.ArrayList;
import java.util.List;

/** A hand-written {@link LevelControlMXBean} test double — records calls, returns what a test wires up. */
final class FakeLevelControlMXBean implements LevelControlMXBean {

    final List<String> listLoggersFilters = new ArrayList<>();
    final List<Object[]> setLevelCalls = new ArrayList<>();
    final List<String> resetLevelCalls = new ArrayList<>();
    /** Names dropped from {@link #loggers} when {@link #resetLevel} clears them — a "Known" but not "Live" logger. */
    final List<String> forgetOnReset = new ArrayList<>();
    int resetAllCalls;

    List<LoggerInfoData> loggers = new ArrayList<>();
    LevelOverrideData setLevelResult;
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
            if (logger.getName().startsWith(filter)) {
                matches.add(logger);
            }
        }
        return matches;
    }

    @Override
    public LevelOverrideData setLevel(String loggerName, String level, boolean includeChildren, String reason,
            String tier, long forSeconds) {
        setLevelCalls.add(new Object[] {loggerName, level, includeChildren, reason, tier, forSeconds});
        maybeThrow();
        return setLevelResult;
    }

    @Override
    public void resetLevel(String loggerName) {
        resetLevelCalls.add(loggerName);
        maybeThrow();
        if (forgetOnReset.contains(loggerName)) {
            loggers.removeIf(logger -> logger.getName().equals(loggerName));
        }
    }

    @Override
    public void resetAll() {
        resetAllCalls++;
        maybeThrow();
    }

    private void maybeThrow() {
        if (throwOnNextCall != null) {
            RuntimeException toThrow = throwOnNextCall;
            throwOnNextCall = null;
            throw toThrow;
        }
    }
}
