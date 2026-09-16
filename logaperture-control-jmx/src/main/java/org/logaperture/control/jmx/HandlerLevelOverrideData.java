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
import org.logaperture.api.SquelchedLogger;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link HandlerLevelOverride} — the {@link
 * LevelOverrideData} counterpart for {@code logctl handler <name> <level>}
 * (doc/specs/handler-floor-control.md). {@code warnings} was added by
 * "Squelch warning" (issue #16) — empty except right after a raise that
 * newly silences one or more active logger overrides.
 */
public final class HandlerLevelOverrideData {

    private final String handlerRef;
    private final String level;
    private final String mode;
    private final String reason;
    private final String appliedAt;
    private final String source;
    private final String tier;
    private final String expiresAt;
    private final List<SquelchedLoggerData> warnings;

    @ConstructorProperties({"handlerRef", "level", "mode", "reason", "appliedAt", "source", "tier", "expiresAt", "warnings"})
    public HandlerLevelOverrideData(
            String handlerRef,
            String level,
            String mode,
            String reason,
            String appliedAt,
            String source,
            String tier,
            String expiresAt,
            List<SquelchedLoggerData> warnings) {
        this.handlerRef = handlerRef;
        this.level = level;
        this.mode = mode;
        this.reason = reason;
        this.appliedAt = appliedAt;
        this.source = source;
        this.tier = tier;
        this.expiresAt = expiresAt;
        this.warnings = warnings == null ? List.of() : warnings;
    }

    /** {@code warnings} always empty -- the {@link #from(HandlerLevelOverride, List)} overload is for {@code setHandlerLevel}'s raise direction. */
    public static HandlerLevelOverrideData from(HandlerLevelOverride override) {
        return from(override, List.of());
    }

    public static HandlerLevelOverrideData from(HandlerLevelOverride override, List<SquelchedLogger> squelched) {
        return new HandlerLevelOverrideData(
                override.handlerRef().value(),
                override.level().name(),
                override.mode().name(),
                override.reason(),
                override.appliedAt().toString(),
                override.source(),
                override.tier().name(),
                override.expiresAt() == null ? null : override.expiresAt().toString(),
                squelched.stream().map(SquelchedLoggerData::from).toList());
    }

    public String getHandlerRef() {
        return handlerRef;
    }

    public String getLevel() {
        return level;
    }

    /** {@code "FIXED"} or {@code "AUTO"} — doc/specs/handler-floor-control.md "AUTO handler level", issue #20. */
    public String getMode() {
        return mode;
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

    /** Active logger overrides this raise newly silences (doc/specs/handler-floor-control.md "Squelch warning", issue #16) — empty otherwise. */
    public List<SquelchedLoggerData> getWarnings() {
        return warnings;
    }
}
