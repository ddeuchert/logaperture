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

import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.LevelControlOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A hand-written {@link LevelControlOperations} test double -- no Logback anywhere in this module. */
final class FakeLevelControlOperations implements LevelControlOperations {

    final List<String> listLoggersCalls = new ArrayList<>();
    final List<Object[]> setLevelCalls = new ArrayList<>();
    final List<String> resetLevelCalls = new ArrayList<>();
    boolean resetAllCalled;

    List<LoggerInfo> loggersToReturn = List.of();
    RuntimeException throwOnSetLevel;

    @Override
    public List<LoggerInfo> listLoggers(String filter) {
        listLoggersCalls.add(filter);
        return loggersToReturn;
    }

    @Override
    public SetLevelResult setLevel(String loggerName, Level level, SetLevelOptions options) {
        setLevelCalls.add(new Object[] {loggerName, level, options});
        if (throwOnSetLevel != null) {
            throw throwOnSetLevel;
        }
        LevelOverride override = new LevelOverride(loggerName, level, options.includeChildren(), options.reason(),
                Instant.now(), "jmx", options.tier(),
                options.tier() == PersistenceTier.FOR ? Instant.now().plus(options.expiresIn()) : null);
        return new SetLevelResult(override, List.of());
    }

    @Override
    public void resetLevel(String loggerName) {
        resetLevelCalls.add(loggerName);
    }

    @Override
    public void resetAll() {
        resetAllCalled = true;
    }
}
