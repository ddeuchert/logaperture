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
package org.logaperture.adapter.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LoggingAdapter} implementation over a real {@code LoggerContext}.
 * Method bodies map 1:1 onto the API shapes the M0 spike validated (see
 * doc/spikes/m0-adapter-grid.md point 1): {@code getLoggerList()}, {@code
 * getLogger(name).setLevel()}, {@code getLevel()} null-vs-explicit, {@code
 * getEffectiveLevel()}.
 */
public final class LogbackLoggingAdapter implements LoggingAdapter {

    private final LoggerContext context;

    /** Package-visible: tests construct against a fresh, throwaway {@code LoggerContext} directly. */
    LogbackLoggingAdapter(LoggerContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<String> knownLoggerNames() {
        return context.getLoggerList().stream().map(Logger::getName).toList();
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        // getLogger(name) creates the Logback Logger as a side effect if it
        // doesn't exist yet -- this is exactly the "Known" state
        // doc/specs/level-control.md §8.5 describes, and it is Logback's
        // own native behavior, not something this adapter has to fake.
        return Optional.ofNullable(LevelMapper.toApi(context.getLogger(loggerName).getLevel()));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        return LevelMapper.toApi(context.getLogger(loggerName).getEffectiveLevel());
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        context.getLogger(loggerName).setLevel(level == null ? null : LevelMapper.toLogback(level));
    }
}
