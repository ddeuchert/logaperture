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
 * counterpart for {@code logctl list handlers} (doc/specs/handler-floor-control.md
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
    private final String overrideMode;
    private final String overrideTier;
    private final String overrideExpiresAt;
    private final String membersSummary;
    private final String context;
    private final String vendorDefault;
    private final boolean resetToNative;

    /**
     * Every field, including {@code resetToNative} (doc/specs/reset-to-native.md "Surfaces"); the
     * narrower constructors below stay annotated for older clients (logaperture-spec.md §11.1).
     */
    @ConstructorProperties({"ref", "level", "persistent", "targetPath", "autoFlush", "overrideActive",
            "overrideLevel", "overrideMode", "overrideTier", "overrideExpiresAt", "membersSummary", "context",
            "vendorDefault", "resetToNative"})
    public HandlerInfoData(String ref, String level, boolean persistent, String targetPath, Boolean autoFlush,
            boolean overrideActive, String overrideLevel, String overrideMode, String overrideTier,
            String overrideExpiresAt, String membersSummary, String context, String vendorDefault,
            boolean resetToNative) {
        this.ref = ref;
        this.level = level;
        this.persistent = persistent;
        this.targetPath = targetPath;
        this.autoFlush = autoFlush;
        this.overrideActive = overrideActive;
        this.overrideLevel = overrideLevel;
        this.overrideMode = overrideMode;
        this.overrideTier = overrideTier;
        this.overrideExpiresAt = overrideExpiresAt;
        this.membersSummary = membersSummary;
        this.context = context;
        this.vendorDefault = vendorDefault;
        this.resetToNative = resetToNative;
    }

    /** Every field but {@code resetToNative} (doc/specs/vendor-defaults.md "Surfaces"). */
    @ConstructorProperties({"ref", "level", "persistent", "targetPath", "autoFlush", "overrideActive",
            "overrideLevel", "overrideMode", "overrideTier", "overrideExpiresAt", "membersSummary", "context",
            "vendorDefault"})
    public HandlerInfoData(String ref, String level, boolean persistent, String targetPath, Boolean autoFlush,
            boolean overrideActive, String overrideLevel, String overrideMode, String overrideTier,
            String overrideExpiresAt, String membersSummary, String context, String vendorDefault) {
        this(ref, level, persistent, targetPath, autoFlush, overrideActive, overrideLevel, overrideMode,
                overrideTier, overrideExpiresAt, membersSummary, context, vendorDefault, false);
    }

    @ConstructorProperties({"ref", "level", "persistent", "targetPath", "autoFlush", "overrideActive",
            "overrideLevel", "overrideMode", "overrideTier", "overrideExpiresAt", "membersSummary", "context"})
    public HandlerInfoData(
            String ref,
            String level,
            boolean persistent,
            String targetPath,
            Boolean autoFlush,
            boolean overrideActive,
            String overrideLevel,
            String overrideMode,
            String overrideTier,
            String overrideExpiresAt,
            String membersSummary,
            String context) {
        this(ref, level, persistent, targetPath, autoFlush, overrideActive, overrideLevel, overrideMode,
                overrideTier, overrideExpiresAt, membersSummary, context, null);
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
                info.overrideMode() == null ? null : info.overrideMode().name(),
                info.overrideTier() == null ? null : info.overrideTier().name(),
                info.overrideExpiresAt() == null ? null : info.overrideExpiresAt().toString(),
                info.membersSummary(),
                info.context(),
                info.vendorDefault(),
                info.resetToNative());
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

    /** {@code "FIXED"} or {@code "AUTO"} — doc/specs/handler-floor-control.md "AUTO handler level", issue #20. */
    public String getOverrideMode() {
        return overrideMode;
    }

    public String getOverrideTier() {
        return overrideTier;
    }

    public String getOverrideExpiresAt() {
        return overrideExpiresAt;
    }

    /**
     * {@code DEFAULT_HANDLERS}'s current membership, display-ready ({@code
     * "FILE, CONSOLE"} or {@code "(auto: CONSOLE)"}) -- doc/specs/
     * handler-floor-control.md "Default handler group", issue #28. {@code
     * null} for every other row.
     */
    public String getMembersSummary() {
        return membersSummary;
    }

    public String getContext() {
        return context;
    }

    /** The vendor defaults file's setting for this handler (a level name or {@code AUTO}), or {@code null}. */
    public String getVendorDefault() {
        return vendorDefault;
    }

    /** Whether that vendor setting is being ignored until restart ({@code reset handler --to-native}). */
    public boolean isResetToNative() {
        return resetToNative;
    }
}
