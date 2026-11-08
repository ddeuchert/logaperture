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

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** doc/specs/doctor-verbosity-fix.md D1: what a reset would land on, which decides whether it is the fix. */
class ResetEffectTest {

    private static final String LOGGER = "org.hibernate";

    private static Function<String, ResetEffect> resolverFor(BaselineRegistry baselines, Level configured) {
        // The adapter's configured level at capture: what the application's own configuration sets.
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        if (configured != null) {
            adapter.setConfiguredLevel(LOGGER, configured);
        } else {
            adapter.addKnownLogger(LOGGER);
        }
        baselines.captureIfAbsent(LOGGER, adapter);
        return ResetEffect.resolver(baselines);
    }

    @Test
    void aNotChattyConfiguredLevel_isFixedByReset() {
        // An override on top of an INFO baseline, or the application raising it in code: a reset lands on INFO.
        assertEquals(ResetEffect.FIXES, resolverFor(new BaselineRegistry(), Level.INFO).apply(LOGGER));
    }

    @Test
    void aLoggerWithNoLevelOfItsOwn_isFixedByReset() {
        assertEquals(ResetEffect.FIXES, resolverFor(new BaselineRegistry(), null).apply(LOGGER));
    }

    @Test
    void aChattyConfiguredLevel_isNotFixedByReset_whateverIsOnTopOfIt() {
        // Including an override of TRACE on top: reset goes back to the configured DEBUG.
        assertEquals(ResetEffect.LANDS_ON_CONFIGURED_LEVEL,
                resolverFor(new BaselineRegistry(), Level.DEBUG).apply(LOGGER));
    }

    @Test
    void aChattyVendorLevelInEffect_isNotFixedByReset_andIsNamedAsTheVendors() {
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.DEBUG));
        assertEquals(ResetEffect.LANDS_ON_VENDOR_LEVEL, resolverFor(baselines, Level.INFO).apply(LOGGER));
    }

    @Test
    void aVendorLevelThatIsNotChatty_isFixedByReset_evenWhenTheApplicationRaisedItInCode() {
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.INFO));
        assertEquals(ResetEffect.FIXES, resolverFor(baselines, Level.DEBUG).apply(LOGGER));
    }

    @Test
    void aChattyVendorLevelResetToNative_landsOnTheNativeLevel() {
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.DEBUG));
        Function<String, ResetEffect> resolver = resolverFor(baselines, Level.INFO);
        baselines.markResetToNative(LOGGER);
        assertEquals(ResetEffect.FIXES, resolver.apply(LOGGER));
    }

    @Test
    void aBaselineNeverCaptured_isTakenToBeTheConfigurations() {
        assertEquals(ResetEffect.LANDS_ON_CONFIGURED_LEVEL,
                ResetEffect.resolver(new BaselineRegistry()).apply(LOGGER));
    }

    @Test
    void vendorLevelInEffect_isOneDefinitionForGetAndTheResolver() {
        BaselineRegistry baselines = new BaselineRegistry(Map.of(LOGGER, Level.DEBUG));
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.setConfiguredLevel(LOGGER, Level.INFO);
        baselines.captureIfAbsent(LOGGER, adapter);
        assertEquals(true, baselines.vendorLevelInEffect(LOGGER));
        assertEquals(Optional.of(Level.DEBUG), baselines.get(LOGGER));
        baselines.markResetToNative(LOGGER);
        assertEquals(false, baselines.vendorLevelInEffect(LOGGER));
        assertEquals(Optional.of(Level.INFO), baselines.get(LOGGER));
    }
}
