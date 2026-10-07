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
package org.logaperture.core;

import org.logaperture.api.PersistenceTier;
import org.logaperture.api.StormReport;

import java.time.Duration;

/**
 * {@code logctl storms}'s public contract — the {@link TopOperations}
 * counterpart for storm detection (doc/specs/storm-detection.md). Every
 * control surface is a client of this interface, same as {@link
 * TopOperations}/{@link DoctorOperations}.
 */
public interface StormOperations {

    /**
     * Every storm the detector has recorded, sorted worst-first (ongoing
     * before ended, then by event count).
     *
     * @param limit keep only the {@code limit} worst storms; {@code limit <=
     *              0} means "every tracked storm" (mirrors {@link
     *              TopOperations#topLoggers}'s convention)
     */
    StormReport activeStorms(int limit);

    /**
     * Whether storm detection is enabled, and when that last changed at runtime
     * (doc/specs/storm-detection-toggle.md). Needs only {@code VIEW}. The default answers
     * "enabled, never changed" -- what a surface without the switch has always meant.
     */
    default StormDetectionSwitch.State stormDetection() {
        return new StormDetectionSwitch.State(true, true, null);
    }

    /**
     * {@code logctl enable|disable storms}: sets storm detection to {@code enabled} in every
     * context, whatever its current position (doc/specs/storm-detection-toggle.md T4, T10).
     *
     * @throws CapabilityDeniedException without {@link Capability#DIAGNOSTICS}
     */
    default StormDetectionSwitch.Change setStormDetection(boolean enabled, String reason) {
        return setStormDetection(enabled, reason, PersistenceTier.SESSION, null);
    }

    /**
     * ... with a tier: {@code SESSION}, {@code FOR} (then {@code forDuration}, after which it
     * switches to the other position -- T12) or {@code STICKY} (kept across restarts). {@code FOR}
     * and {@code STICKY} also need {@link Capability#PERSIST}.
     */
    default StormDetectionSwitch.Change setStormDetection(boolean enabled, String reason, PersistenceTier tier,
            Duration forDuration) {
        throw new UnsupportedOperationException("storm detection can't be enabled or disabled here");
    }
}
