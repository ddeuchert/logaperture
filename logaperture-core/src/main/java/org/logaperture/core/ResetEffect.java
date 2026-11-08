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

import org.logaperture.api.Level;

import java.util.Optional;
import java.util.function.Function;

/**
 * What a {@code logctl reset logger} would do to a chatty logger {@code logctl doctor} has flagged
 * (doc/specs/doctor-verbosity-fix.md D1). Asked of {@code reset} itself, not of where the current level came from:
 * it is the fix only when it does something ({@link LevelControlService#resetLogger}: nothing without an override
 * or a vendor reset-to-native state to undo) and lands below the chatty levels.
 */
public enum ResetEffect {

    /** A reset undoes an override or a reset-to-native, and lands on a level that is not chatty: it fixes it. */
    FIXES,

    /** Nothing to undo: no {@code logctl} change is in force, so a reset would do nothing. The level is the application's. */
    NOTHING_TO_RESET,

    /** A reset would land on a chatty level the vendor defaults file sets. */
    LANDS_ON_VENDOR_LEVEL,

    /** A reset would land on a chatty level the application's configuration sets (or none can be told). */
    LANDS_ON_CONFIGURED_LEVEL;

    /**
     * The function a container hands {@link DoctorService}. A plain {@code reset} clears a reset-to-native state
     * before it applies the baseline ({@link LevelControlService}'s {@code resetOne}), so it lands on the vendor
     * file's level whenever the file names the logger, and on the native one otherwise. It changes anything only
     * if there is an override or a reset-to-native state to undo. A baseline never captured is taken to be the
     * configuration's. Both composition roots (the {@code none} and the JBoss LogManager containers) build their
     * {@code DoctorService} with it.
     */
    public static Function<String, ResetEffect> resolver(OverrideRegistry overrides, BaselineRegistry baselines) {
        return name -> {
            Optional<Level> vendor = baselines.vendorLevel(name);
            Level landing;
            try {
                landing = vendor.isPresent() ? vendor.get() : baselines.nativeLevel(name).orElse(null);
            } catch (IllegalStateException neverCaptured) {
                return LANDS_ON_CONFIGURED_LEVEL;
            }
            if (DoctorService.isChattyLevel(landing)) {
                return vendor.isPresent() ? LANDS_ON_VENDOR_LEVEL : LANDS_ON_CONFIGURED_LEVEL;
            }
            boolean somethingToUndo = overrides.get(name).isPresent() || baselines.isResetToNative(name);
            return somethingToUndo ? FIXES : NOTHING_TO_RESET;
        };
    }
}
