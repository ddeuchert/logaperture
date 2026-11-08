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

import java.util.function.Function;

/**
 * Where a logger's current explicit level comes from, as far as {@code logctl doctor} needs to know it
 * (doc/specs/doctor-verbosity-fix.md D1): it decides whether {@code reset} is the fix for a level left on.
 */
public enum LevelOrigin {

    /** An active {@code logctl} override: {@code logctl reset logger} undoes it. */
    OVERRIDE,

    /** The vendor defaults file names the logger and it is not reset to native: {@code reset} goes back to the file. */
    VENDOR,

    /** The application's own configuration, or no way to tell: {@code reset} goes back to the same level. */
    CONFIGURATION;

    /**
     * The function a container hands {@link DoctorService}: an active override wins, then a vendor level that is
     * in effect, else the configuration. Both composition roots (the {@code none} and the JBoss LogManager
     * containers) build their {@code DoctorService} with it.
     */
    public static Function<String, LevelOrigin> resolver(OverrideRegistry overrides, BaselineRegistry baselines) {
        Function<String, LevelOrigin> resolve = name -> {
            if (overrides.get(name).isPresent()) {
                return OVERRIDE;
            }
            return baselines.vendorLevel(name).isPresent() && !baselines.isResetToNative(name)
                    ? VENDOR : CONFIGURATION;
        };
        return resolve;
    }
}
