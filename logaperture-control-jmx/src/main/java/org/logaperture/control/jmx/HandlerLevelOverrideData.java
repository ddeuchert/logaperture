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

import org.logaperture.api.HandlerLevelOverride;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link HandlerLevelOverride} — the {@link
 * LevelOverrideData} counterpart for {@code logctl handler <name> <level>}
 * (doc/specs/handler-floor-control.md).
 */
public final class HandlerLevelOverrideData {

    private final String handlerRef;
    private final String level;
    private final String reason;
    private final String appliedAt;
    private final String source;
    private final String tier;
    private final String expiresAt;

    @ConstructorProperties({"handlerRef", "level", "reason", "appliedAt", "source", "tier", "expiresAt"})
    public HandlerLevelOverrideData(
            String handlerRef,
            String level,
            String reason,
            String appliedAt,
            String source,
            String tier,
            String expiresAt) {
        this.handlerRef = handlerRef;
        this.level = level;
        this.reason = reason;
        this.appliedAt = appliedAt;
        this.source = source;
        this.tier = tier;
        this.expiresAt = expiresAt;
    }

    public static HandlerLevelOverrideData from(HandlerLevelOverride override) {
        return new HandlerLevelOverrideData(
                override.handlerRef().value(),
                override.level().name(),
                override.reason(),
                override.appliedAt().toString(),
                override.source(),
                override.tier().name(),
                override.expiresAt() == null ? null : override.expiresAt().toString());
    }

    public String getHandlerRef() {
        return handlerRef;
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
