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

import java.util.function.Function;

/**
 * What a {@code logctl reset} would do to a chatty logger {@code logctl doctor} has flagged
 * (doc/specs/doctor-verbosity-fix.md D1): the fix is {@code reset} only when it lands below the chatty levels.
 * Asked of the baseline a reset returns to, not of where the current level came from, so an override on top of a
 * chatty configured level is not sent to a {@code reset} that changes nothing.
 */
public enum ResetEffect {

    /** A reset lands on a level that is not chatty: {@code logctl reset logger} fixes it. */
    FIXES,

    /** A reset goes back to a chatty level the vendor defaults file sets, so it would not fix it. */
    LANDS_ON_VENDOR_LEVEL,

    /** A reset goes back to a chatty level the application's configuration sets (or none can be told). */
    LANDS_ON_CONFIGURED_LEVEL;

    /**
     * The function a container hands {@link DoctorService}: from the baseline a reset returns to
     * ({@link BaselineRegistry#get}), which is the vendor level when one is in effect, else the configured one. A
     * baseline never captured is taken to be the configuration's. Both composition roots (the {@code none} and the
     * JBoss LogManager containers) build their {@code DoctorService} with it.
     */
    public static Function<String, ResetEffect> resolver(BaselineRegistry baselines) {
        return name -> {
            Level landing;
            try {
                landing = baselines.get(name).orElse(null);
            } catch (IllegalStateException neverCaptured) {
                return LANDS_ON_CONFIGURED_LEVEL;
            }
            if (!DoctorService.isChattyLevel(landing)) {
                return FIXES;
            }
            return baselines.vendorLevelInEffect(name) ? LANDS_ON_VENDOR_LEVEL : LANDS_ON_CONFIGURED_LEVEL;
        };
    }
}
