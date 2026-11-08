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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.LevelOverride;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** doc/specs/doctor-verbosity-fix.md D1: where a logger's level comes from. */
class LevelOriginTest {

    private static final String LOGGER = "org.hibernate";

    private OverrideRegistry overrides;
    private BaselineRegistry baselines;
    private Function<String, LevelOrigin> origin;

    @BeforeEach
    void setUp() {
        overrides = new OverrideRegistry();
        baselines = new BaselineRegistry(Map.of(LOGGER, Level.DEBUG));
        origin = LevelOrigin.resolver(overrides, baselines);
    }

    @Test
    void aLoggerWithNothingSetBehindIt_comesFromTheConfiguration() {
        assertEquals(LevelOrigin.CONFIGURATION, origin.apply("org.apache.http"));
    }

    @Test
    void aVendorLevelInEffect_isTheVendors() {
        assertEquals(LevelOrigin.VENDOR, origin.apply(LOGGER));
    }

    @Test
    void aVendorLevelResetToNative_isTheConfigurations() {
        baselines.markResetToNative(LOGGER);
        assertEquals(LevelOrigin.CONFIGURATION, origin.apply(LOGGER));
    }

    @Test
    void anActiveOverride_winsOverTheVendorLevel() {
        overrides.put(new LevelOverride(LOGGER, Level.DEBUG, "probe", Instant.now(), "jmx", PersistenceTier.SESSION,
                null, null, null, null));
        assertEquals(LevelOrigin.OVERRIDE, origin.apply(LOGGER));
    }
}
