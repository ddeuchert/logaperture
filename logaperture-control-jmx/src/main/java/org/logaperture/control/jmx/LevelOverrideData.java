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
    private final String reason;
    private final String appliedAt;
    private final String source;
    private final String tier;
    private final String expiresAt;
    private final String forcedBy;

    /**
     * Every field, including {@code forcedBy} (doc/specs/set-logger-force.md). The narrower
     * constructor stays annotated so an older client still reconstructs this type (§11.1).
     */
    @ConstructorProperties({"loggerName", "level", "reason", "appliedAt", "source", "tier", "expiresAt", "forcedBy"})
    public LevelOverrideData(String loggerName, String level, String reason, String appliedAt, String source,
            String tier, String expiresAt, String forcedBy) {
        this.loggerName = loggerName;
        this.level = level;
        this.reason = reason;
        this.appliedAt = appliedAt;
        this.source = source;
        this.tier = tier;
        this.expiresAt = expiresAt;
        this.forcedBy = forcedBy;
    }

    @ConstructorProperties({"loggerName", "level", "reason", "appliedAt", "source", "tier", "expiresAt"})
    public LevelOverrideData(
            String loggerName,
            String level,
            String reason,
            String appliedAt,
            String source,
            String tier,
            String expiresAt) {
        this(loggerName, level, reason, appliedAt, source, tier, expiresAt, null);
    }

    public static LevelOverrideData from(LevelOverride override) {
        return new LevelOverrideData(
                override.loggerName(),
                override.level().name(),
                override.reason(),
                override.appliedAt().toString(),
                override.source(),
                override.tier().name(),
                override.expiresAt() == null ? null : override.expiresAt().toString(),
                override.forcedBy());
    }

    /** The logger whose {@code set logger --force} made this override, or {@code null}. */
    public String getForcedBy() {
        return forcedBy;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getLevel() {
        return level;
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
