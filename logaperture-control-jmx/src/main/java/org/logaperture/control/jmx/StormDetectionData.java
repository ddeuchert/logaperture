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

import org.logaperture.core.StormDetectionSwitch;

import java.beans.ConstructorProperties;
import java.time.Instant;

/**
 * MXBean-friendly form of the storm-detection switch -- doc/specs/storm-detection-toggle.md
 * "JMX". Answers both {@code stormDetection()} and {@code setStormDetection}; for the first,
 * {@code previous} equals {@code enabled} and {@code changed} is {@code false}.
 */
public final class StormDetectionData {

    private final boolean enabled;
    private final boolean previous;
    private final boolean changed;
    private final String changedAt;
    private final boolean startedEnabled;
    private final String tier;
    private final String expiresAt;

    @ConstructorProperties({"enabled", "previous", "changed", "changedAt", "startedEnabled", "tier", "expiresAt"})
    public StormDetectionData(boolean enabled, boolean previous, boolean changed, String changedAt,
            boolean startedEnabled, String tier, String expiresAt) {
        this.enabled = enabled;
        this.previous = previous;
        this.changed = changed;
        this.changedAt = changedAt;
        this.startedEnabled = startedEnabled;
        this.tier = tier;
        this.expiresAt = expiresAt;
    }

    public static StormDetectionData from(StormDetectionSwitch.State state) {
        return new StormDetectionData(state.enabled(), state.enabled(), false, text(state.changedAt()),
                state.startedEnabled(), state.tier().name(), text(state.expiresAt()));
    }

    public static StormDetectionData from(StormDetectionSwitch.Change change) {
        return new StormDetectionData(change.enabled(), change.previous(), change.changed(),
                text(change.changedAt()), change.startedEnabled(), change.tier().name(), text(change.expiresAt()));
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The position before a {@code setStormDetection} call; the same as {@link #isEnabled()} otherwise. */
    public boolean isPrevious() {
        return previous;
    }

    /** {@code false} when the switch already had the position asked for. */
    public boolean isChanged() {
        return changed;
    }

    /** The last runtime change, or {@code null} while the switch is still in its starting position. */
    public String getChangedAt() {
        return changedAt;
    }

    /** The starting position, from the agent's {@code --storm-detection} argument. */
    public boolean isStartedEnabled() {
        return startedEnabled;
    }

    /** {@code SESSION}, {@code FOR} or {@code STICKY}. */
    public String getTier() {
        return tier;
    }

    /** When a {@code FOR} setting switches to the other position; {@code null} otherwise. */
    public String getExpiresAt() {
        return expiresAt;
    }
}
