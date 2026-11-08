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

import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * doc/specs/doctor-verbosity-fix.md D1: what a {@code reset} would do, which decides whether it is the fix.
 * {@code DoctorFixExecutionTest} runs the suggested commands for the same cases.
 */
class ResetEffectTest {

    private static final String LOGGER = "org.hibernate";

    private final OverrideRegistry overrides = new OverrideRegistry();

    /** A resolver over a baseline captured with the application's {@code configured} level (null: none of its own). */
    private Function<String, ResetEffect> resolverFor(BaselineRegistry baselines, Level configured) {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        if (configured != null) {
            adapter.setConfiguredLevel(LOGGER, configured);
        } else {
            adapter.addKnownLogger(LOGGER);
        }
        baselines.captureIfAbsent(LOGGER, adapter);
        return ResetEffect.resolver(overrides, baselines);
    }

    private void overrideTo(Level level) {
        overrides.put(new LevelOverride(LOGGER, level, "probe", Instant.now(), "jmx", PersistenceTier.SESSION,
                null, null, null, null));
    }

    @Test
    void anOverrideOverANotChattyBaseline_isFixedByReset() {
        Function<String, ResetEffect> resolver = resolverFor(new BaselineRegistry(), Level.INFO);
        overrideTo(Level.DEBUG);
        assertEquals(ResetEffect.FIXES, resolver.apply(LOGGER));
    }

    @Test
    void aLoggerWithNoLevelOfItsOwnAndAnOverride_isFixedByReset() {
        Function<String, ResetEffect> resolver = resolverFor(new BaselineRegistry(), null);
        overrideTo(Level.DEBUG);
        assertEquals(ResetEffect.FIXES, resolver.apply(LOGGER));
    }

    @Test
    void aLevelTheApplicationRaisedItself_hasNothingForResetToUndo() {
        // INFO at capture, DEBUG since, no override: resetLogger answers "nothing reset".
        assertEquals(ResetEffect.NOTHING_TO_RESET, resolverFor(new BaselineRegistry(), Level.INFO).apply(LOGGER));
    }

    @Test
    void aChattyConfiguredLevel_isNotFixedByReset_withOrWithoutAnOverrideOnTop() {
        Function<String, ResetEffect> resolver = resolverFor(new BaselineRegistry(), Level.DEBUG);
        assertEquals(ResetEffect.LANDS_ON_CONFIGURED_LEVEL, resolver.apply(LOGGER));
        overrideTo(Level.TRACE);
        assertEquals(ResetEffect.LANDS_ON_CONFIGURED_LEVEL, resolver.apply(LOGGER));
    }

    @Test
    void aChattyVendorLevel_isNotFixedByReset_andIsNamedAsTheVendors() {
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.DEBUG));
        assertEquals(ResetEffect.LANDS_ON_VENDOR_LEVEL, resolverFor(baselines, Level.INFO).apply(LOGGER));
    }

    @Test
    void aChattyVendorLevelResetToNative_isStillWhereAPlainResetLands() {
        // A plain reset clears the reset-to-native state first, so it goes back to the vendor's DEBUG, not the
        // native INFO, even with an override on top.
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.DEBUG));
        Function<String, ResetEffect> resolver = resolverFor(baselines, Level.INFO);
        baselines.markResetToNative(LOGGER);
        overrideTo(Level.TRACE);
        assertEquals(ResetEffect.LANDS_ON_VENDOR_LEVEL, resolver.apply(LOGGER));
    }

    @Test
    void aVendorLevelThatIsNotChatty_isFixedByResetOnlyWhereThereIsSomethingToUndo() {
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.INFO));
        Function<String, ResetEffect> resolver = resolverFor(baselines, Level.INFO);
        assertEquals(ResetEffect.NOTHING_TO_RESET, resolver.apply(LOGGER));
        overrideTo(Level.DEBUG);
        assertEquals(ResetEffect.FIXES, resolver.apply(LOGGER));
    }

    @Test
    void aBaselineNeverCaptured_isTakenToBeTheConfigurations() {
        assertEquals(ResetEffect.LANDS_ON_CONFIGURED_LEVEL,
                ResetEffect.resolver(overrides, new BaselineRegistry()).apply(LOGGER));
    }
}
