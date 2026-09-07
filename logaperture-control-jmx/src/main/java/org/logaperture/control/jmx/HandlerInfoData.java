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

import org.logaperture.api.HandlerInfo;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link HandlerInfo} — the {@link LoggerInfoData}
 * counterpart for {@code logctl handlers} (doc/specs/handler-floor-control.md
 * "The handler catalog", issue #15). Plain class with a {@link
 * ConstructorProperties} constructor and JavaBean getters; levels are {@code
 * String} at this boundary, matching the rest of {@link LevelControlMXBean}.
 */
public final class HandlerInfoData {

    private final String ref;
    private final String level;
    private final boolean persistent;
    private final String targetPath;
    private final Boolean autoFlush;
    private final boolean overrideActive;
    private final String overrideLevel;
    private final String overrideTier;
    private final String overrideExpiresAt;
    private final String context;

    @ConstructorProperties({"ref", "level", "persistent", "targetPath", "autoFlush", "overrideActive",
            "overrideLevel", "overrideTier", "overrideExpiresAt", "context"})
    public HandlerInfoData(
            String ref,
            String level,
            boolean persistent,
            String targetPath,
            Boolean autoFlush,
            boolean overrideActive,
            String overrideLevel,
            String overrideTier,
            String overrideExpiresAt,
            String context) {
        this.ref = ref;
        this.level = level;
        this.persistent = persistent;
        this.targetPath = targetPath;
        this.autoFlush = autoFlush;
        this.overrideActive = overrideActive;
        this.overrideLevel = overrideLevel;
        this.overrideTier = overrideTier;
        this.overrideExpiresAt = overrideExpiresAt;
        this.context = context;
    }

    public static HandlerInfoData from(HandlerInfo info) {
        return new HandlerInfoData(
                info.ref(),
                info.level() == null ? null : info.level().name(),
                info.persistent(),
                info.targetPath(),
                info.autoFlush(),
                info.overrideActive(),
                info.overrideLevel() == null ? null : info.overrideLevel().name(),
                info.overrideTier() == null ? null : info.overrideTier().name(),
                info.overrideExpiresAt() == null ? null : info.overrideExpiresAt().toString(),
                info.context());
    }

    public String getRef() {
        return ref;
    }

    public String getLevel() {
        return level;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public Boolean getAutoFlush() {
        return autoFlush;
    }

    public boolean isOverrideActive() {
        return overrideActive;
    }

    public String getOverrideLevel() {
        return overrideLevel;
    }

    public String getOverrideTier() {
        return overrideTier;
    }

    public String getOverrideExpiresAt() {
        return overrideExpiresAt;
    }

    public String getContext() {
        return context;
    }
}
