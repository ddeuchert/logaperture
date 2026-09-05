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

import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.HandlerLevelControlOperations;
import org.logaperture.core.LevelControlOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A hand-written {@link LevelControlOperations} + {@link
 * HandlerLevelControlOperations} test double -- no Logback anywhere in this
 * module. One instance implements both, mirroring how {@code
 * AggregateLevelControl} does in production.
 */
final class FakeLevelControlOperations implements LevelControlOperations, HandlerLevelControlOperations {

    final List<String> listLoggersCalls = new ArrayList<>();
    final List<Object[]> setLevelCalls = new ArrayList<>();
    final List<String> resetLevelCalls = new ArrayList<>();
    final List<Object[]> setHandlerLevelCalls = new ArrayList<>();
    final List<HandlerRef> resetHandlerCalls = new ArrayList<>();
    boolean resetAllCalled;

    List<LoggerInfo> loggersToReturn = List.of();
    List<HandlerFloor> blockingHandlersToReturn = List.of();
    RuntimeException throwOnSetLevel;
    RuntimeException throwOnSetHandlerLevel;
    boolean noOpHandlerLevels;

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
        return new SetLevelResult(override, blockingHandlersToReturn);
    }

    @Override
    public void resetLevel(String loggerName) {
        resetLevelCalls.add(loggerName);
    }

    @Override
    public void resetAll() {
        resetAllCalled = true;
    }

    @Override
    public Optional<HandlerLevelOverride> setHandlerLevel(HandlerRef ref, Level level, SetHandlerLevelOptions options) {
        setHandlerLevelCalls.add(new Object[] {ref, level, options});
        if (throwOnSetHandlerLevel != null) {
            throw throwOnSetHandlerLevel;
        }
        if (noOpHandlerLevels) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant expiresAt = options.tier() == PersistenceTier.FOR ? now.plus(options.expiresIn()) : null;
        return Optional.of(new HandlerLevelOverride(ref, level, options.reason(), now, "jmx", options.tier(), expiresAt));
    }

    @Override
    public void resetHandler(HandlerRef ref) {
        resetHandlerCalls.add(ref);
    }
}
