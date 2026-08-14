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

import org.logaperture.api.LoggerInfo;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link LoggerInfo}. A plain class with a
 * {@link ConstructorProperties}-annotated constructor and JavaBean
 * getters, rather than reusing the {@code api} record directly — record-
 * to-{@code CompositeType} MXBean support only landed around JDK 20, and
 * this project is pinned to Java 17 (doc/specs/level-control.md's design
 * calls). Levels are {@code String} at this boundary for
 * {@code jconsole}-friendliness, matching {@link LevelControlMXBean}.
 */
public final class LoggerInfoData {

    private final String name;
    private final String configuredLevel;
    private final String effectiveLevel;
    private final boolean overrideActive;
    private final String overrideSource;
    private final String overrideReason;

    @ConstructorProperties({"name", "configuredLevel", "effectiveLevel", "overrideActive", "overrideSource", "overrideReason"})
    public LoggerInfoData(
            String name,
            String configuredLevel,
            String effectiveLevel,
            boolean overrideActive,
            String overrideSource,
            String overrideReason) {
        this.name = name;
        this.configuredLevel = configuredLevel;
        this.effectiveLevel = effectiveLevel;
        this.overrideActive = overrideActive;
        this.overrideSource = overrideSource;
        this.overrideReason = overrideReason;
    }

    public static LoggerInfoData from(LoggerInfo info) {
        return new LoggerInfoData(
                info.name(),
                info.configuredLevel() == null ? null : info.configuredLevel().name(),
                info.effectiveLevel().name(),
                info.overrideActive(),
                info.overrideSource(),
                info.overrideReason());
    }

    public String getName() {
        return name;
    }

    public String getConfiguredLevel() {
        return configuredLevel;
    }

    public String getEffectiveLevel() {
        return effectiveLevel;
    }

    public boolean isOverrideActive() {
        return overrideActive;
    }

    public String getOverrideSource() {
        return overrideSource;
    }

    public String getOverrideReason() {
        return overrideReason;
    }
}
