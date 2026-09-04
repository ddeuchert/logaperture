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
package org.logaperture.cli.it;

import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.LevelControlOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small stateful {@link LevelControlOperations} for {@link CliFixtureApp}
 * — enough to make {@code listLoggers}/{@code setLevel}/{@code
 * resetLevel}/{@code resetAll} round-trip over a real cross-process JMX
 * connection so {@link CliEndToEndIT} can assert against real output. No
 * Logback, no agent — the CLI's transport is what's under test, not the
 * engine.
 */
final class FakeOps implements LevelControlOperations {

    private static final Level BASELINE = Level.INFO;

    private final Map<String, LoggerInfo> state = new LinkedHashMap<>();

    FakeOps() {
        seed("com.acme.batch.Worker");
        seed("com.acme.web.RequestFilter");
    }

    private void seed(String name) {
        state.put(name, baseline(name));
    }

    private static LoggerInfo baseline(String name) {
        return new LoggerInfo(name, BASELINE, BASELINE, false, null, null, null, null);
    }

    @Override
    public synchronized List<LoggerInfo> listLoggers(String filter) {
        List<LoggerInfo> matches = new ArrayList<>();
        for (LoggerInfo info : state.values()) {
            if (filter == null || filter.isEmpty() || info.name().startsWith(filter)) {
                matches.add(info);
            }
        }
        return matches;
    }

    @Override
    public synchronized SetLevelResult setLevel(String loggerName, Level level, SetLevelOptions options) {
        Instant now = Instant.now();
        Instant expiresAt = options.tier() == PersistenceTier.FOR ? now.plus(options.expiresIn()) : null;
        state.put(loggerName, new LoggerInfo(
                loggerName, BASELINE, level, true, "jmx", options.reason(), options.tier(), expiresAt));
        LevelOverride override = new LevelOverride(
                loggerName, level, options.includeChildren(), options.reason(), now, "jmx", options.tier(), expiresAt);
        return new SetLevelResult(override, List.of());
    }

    @Override
    public synchronized void resetLevel(String loggerName) {
        if (state.containsKey(loggerName)) {
            state.put(loggerName, baseline(loggerName));
        }
    }

    @Override
    public synchronized void resetAll() {
        for (String name : List.copyOf(state.keySet())) {
            state.put(name, baseline(name));
        }
    }
}
