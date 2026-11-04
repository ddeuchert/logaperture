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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The handler levels this adapter has set (doc/specs/log4j2-adapter.md A5, Slice 6), the one place a slot's
 * current level is read from. Log4j has no setter for an appender reference's level: changing one is a
 * remove and an add on the {@code LoggerConfig}, and {@code getAppenderRefs()} goes on reporting the level
 * the file declared (H1). So the reference gives the <em>file's</em> level, and this table the level set
 * since; a slot with no entry is at the file's.
 *
 * <p>The table is as current as the changes made through this adapter: code that swaps an appender's level
 * itself ({@code addAppender(a, level, f)} from elsewhere) is not seen.
 *
 * <p>Keyed weakly by {@code LoggerConfig} ({@code LoggerConfig} does not override {@code equals}, so that is
 * identity): a reconfiguration builds new {@code LoggerConfig}s,
 * and the old table goes with them, which is what a swap does to a handler level anyway.
 */
final class HandlerLevels {

    private final Map<LoggerConfig, Map<String, Level>> set = new WeakHashMap<>();

    /** The level the slot is at now: what was set, else what the file declared (possibly none). */
    synchronized Level current(LoggerConfig loggerConfig, AppenderRef reference) {
        Map<String, Level> levels = set.get(loggerConfig);
        if (levels != null) {
            Level level = levels.get(reference.getRef());
            if (level != null) {
                return level;
            }
        }
        return reference.getLevel();
    }

    /** What was set for the slot, or null if it is at the file's level. */
    synchronized Level recorded(LoggerConfig loggerConfig, String appenderName) {
        Map<String, Level> levels = set.get(loggerConfig);
        return levels == null ? null : levels.get(appenderName);
    }

    /** Records {@code level} for the slot; {@code null} goes back to the file's. */
    synchronized void record(LoggerConfig loggerConfig, String appenderName, Level level) {
        if (level == null) {
            Map<String, Level> levels = set.get(loggerConfig);
            if (levels != null) {
                levels.remove(appenderName);
            }
            return;
        }
        set.computeIfAbsent(loggerConfig, key -> new HashMap<>()).put(appenderName, level);
    }

    /** Every {@code LoggerConfig} of the configuration, the root first, each once. */
    static List<LoggerConfig> loggerConfigs(Configuration configuration) {
        List<LoggerConfig> all = new ArrayList<>();
        LoggerConfig root = configuration.getRootLogger();
        all.add(root);
        for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
            if (loggerConfig != root) {
                all.add(loggerConfig);
            }
        }
        return all;
    }
}
