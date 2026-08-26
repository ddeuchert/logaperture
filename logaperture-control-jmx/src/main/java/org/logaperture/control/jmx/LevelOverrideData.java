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

import org.logaperture.api.LevelOverride;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link LevelOverride} — see {@link
 * LoggerInfoData} for the pattern's rationale. {@code tier} and {@code
 * expiresAt} (ISO-8601, or {@code null} unless {@code tier} is {@code
 * "FOR"}) were added by doc/specs/persistence.md's "JMX surface changes".
 */
public final class LevelOverrideData {

    private final String loggerName;
    private final String level;
    private final boolean includeChildren;
    private final String reason;
    private final String appliedAt;
    private final String source;
    private final String tier;
    private final String expiresAt;

    @ConstructorProperties({"loggerName", "level", "includeChildren", "reason", "appliedAt", "source", "tier", "expiresAt"})
    public LevelOverrideData(
            String loggerName,
            String level,
            boolean includeChildren,
            String reason,
            String appliedAt,
            String source,
            String tier,
            String expiresAt) {
        this.loggerName = loggerName;
        this.level = level;
        this.includeChildren = includeChildren;
        this.reason = reason;
        this.appliedAt = appliedAt;
        this.source = source;
        this.tier = tier;
        this.expiresAt = expiresAt;
    }

    public static LevelOverrideData from(LevelOverride override) {
        return new LevelOverrideData(
                override.loggerName(),
                override.level().name(),
                override.includeChildren(),
                override.reason(),
                override.appliedAt().toString(),
                override.source(),
                override.tier().name(),
                override.expiresAt() == null ? null : override.expiresAt().toString());
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getLevel() {
        return level;
    }

    public boolean isIncludeChildren() {
        return includeChildren;
    }

    public String getReason() {
        return reason;
    }

    public String getAppliedAt() {
        return appliedAt;
    }

    public String getSource() {
        return source;
    }

    public String getTier() {
        return tier;
    }

    public String getExpiresAt() {
        return expiresAt;
    }
}
