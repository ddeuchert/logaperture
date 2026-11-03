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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.logaperture.api.BackendInfo;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Levels on one Log4j 2 {@link LoggerContext} (doc/specs/log4j2-adapter.md A2, A8). Works on the
 * context it holds, never through {@code Configurator}'s static helpers, which find their context
 * from the caller's class loader (spike SB3).
 *
 * <p>A native level the API cannot name ({@code FATAL}, a custom level) reads back as the nearest
 * one, so a reset that writes the captured baseline back turns a declared {@code fatal} into
 * {@code error}. The adapter cannot do better alone: a reset and a deliberate {@code set logger x
 * error} reach {@link #applyLevel} as the same call. Exact restoration needs the baseline to carry
 * the native level (issue filed with this slice).
 *
 * <p>Not in this class yet: re-applying after a reconfiguration, handler levels, the gate and trim.
 * Until the re-apply listener lands a reconfiguration discards a level set here, as it would any
 * other {@code LoggerConfig} change.
 */
final class Log4j2LoggingAdapter implements LoggingAdapter {

    static final String ROOT_ALIAS = "ROOT";
    private static final String LOG4J_ROOT = "";

    private final LoggerContext context;
    private final String version;
    /**
     * The {@code LoggerConfig}s {@link #applyLevel} created where none was (A8), by name. Held as the
     * object, not just the name: after a reconfiguration the name may belong to a config the
     * application declared, and that one is never removed.
     */
    private final Map<String, LoggerConfig> createdByLogAperture = new ConcurrentHashMap<>();

    Log4j2LoggingAdapter(LoggerContext context, String version) {
        this.context = context;
        this.version = version;
    }

    @Override
    public List<String> knownLoggerNames() {
        // Two different sets, both needed (spike L1): instantiated loggers, and declared LoggerConfigs.
        Set<String> names = new LinkedHashSet<>();
        names.add(ROOT_ALIAS);
        for (org.apache.logging.log4j.core.Logger logger : context.getLoggers()) {
            names.add(alias(logger.getName()));
        }
        names.addAll(aliasAll(context.getConfiguration().getLoggers().keySet()));
        return new ArrayList<>(names);
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        Configuration config = context.getConfiguration();
        String name = nativeName(loggerName);
        LoggerConfig own = config.getLoggers().get(name);
        if (own == null && !name.isEmpty()) {
            return Optional.empty(); // no LoggerConfig of its own: inherits (spike L2)
        }
        LoggerConfig target = own != null ? own : config.getRootLogger();
        return Optional.ofNullable(LevelMapper.toApi(target.getExplicitLevel()));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        LoggerConfig config = context.getConfiguration().getLoggerConfig(nativeName(loggerName));
        return LevelMapper.toApi(config.getLevel());
    }

    @Override
    public synchronized void applyLevel(String loggerName, Level level) {
        Configuration config = context.getConfiguration();
        String name = nativeName(loggerName);
        LoggerConfig own = config.getLoggers().get(name);
        if (level == null) {
            clear(config, name, own);
        } else {
            set(config, name, own, LevelMapper.toLog4j(level));
        }
        context.updateLoggers();
    }

    private void set(Configuration config, String name, LoggerConfig own, org.apache.logging.log4j.Level level) {
        if (name.isEmpty()) {
            config.getRootLogger().setLevel(level);
        } else if (own != null) {
            own.setLevel(level);
        } else {
            // A name no LoggerConfig has: pre-set one of its own, additive, no appenders (A8, L3b).
            LoggerConfig created = new LoggerConfig(name, level, true);
            config.addLogger(name, created);
            createdByLogAperture.put(name, created);
        }
    }

    private void clear(Configuration config, String name, LoggerConfig own) {
        if (name.isEmpty()) {
            return; // the root has nothing to inherit from: leave it
        }
        LoggerConfig mine = createdByLogAperture.remove(name);
        if (own == null) {
            return;
        }
        if (mine == own) {
            config.removeLogger(name); // reset removes the LoggerConfig it created (L3c)
        } else if (mine == null) {
            own.setLevel(null); // a declared, level-less config inherits again (L3e)
        }
        // else: the config LogAperture created is gone (a reconfiguration) and this one is the
        // application's own: leave it. Putting levels back after a reconfiguration is the re-apply slice.
    }

    @Override
    public BackendInfo backendInfo() {
        return new BackendInfo("Log4j 2", version);
    }

    private static String alias(String log4jName) {
        return log4jName == null || log4jName.equals(LOG4J_ROOT) ? ROOT_ALIAS : log4jName;
    }

    private static List<String> aliasAll(Set<String> names) {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(alias(name));
        }
        return out;
    }

    private static String nativeName(String loggerName) {
        return ROOT_ALIAS.equals(loggerName) ? LOG4J_ROOT : loggerName;
    }
}
