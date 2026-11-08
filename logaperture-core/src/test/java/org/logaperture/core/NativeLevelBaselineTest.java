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
import org.logaperture.api.SetLevelOptions;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/native-level-baseline.md: a baseline write-back carries the adapter's native-level token (N1, N3). */
class NativeLevelBaselineTest {

    private static final String LOGGER = "com.acme.Critical";
    private static final Object FATAL = new Object() {
        @Override
        public String toString() {
            return "FATAL";
        }
    };

    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private LevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.setConfiguredLevel(LOGGER, Level.ERROR); // the API reads a declared FATAL as ERROR
        adapter.setNativeToken(LOGGER, FATAL);
        baselines = new BaselineRegistry();
        service = newService(baselines);
    }

    private LevelControlService newService(BaselineRegistry registry) {
        return new LevelControlService(adapter, registry, new OverrideRegistry(), CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), new InMemoryStateStore(), "alice", "jmx");
    }

    @Test
    void aReset_putsTheBaselineBackWithTheTokenItCaptured() {
        service.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.withReason("x"));
        service.resetLogger(LOGGER, false);
        assertTrue(adapter.wasRestored(LOGGER));
        assertSame(FATAL, adapter.lastRestoreToken(LOGGER));
        assertEquals(Level.ERROR, adapter.effectiveLevel(LOGGER));
    }

    @Test
    void aDeliberateErrorOverride_isAnApplyLevel_neverARestore() {
        service.setLogger(LOGGER, Level.ERROR, SetLevelOptions.withReason("x"));
        assertFalse(adapter.wasRestored(LOGGER), "an override must not carry the baseline's token");
    }

    @Test
    void anExpiredOverride_putsTheBaselineBackWithTheToken() {
        service.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(1)));
        service.sweepExpiredOverrides(java.time.Instant.now().plus(Duration.ofMinutes(2)));
        assertSame(FATAL, adapter.lastRestoreToken(LOGGER));
    }

    @Test
    void aLoggerWithNoToken_isRestoredWithNull() {
        adapter.setConfiguredLevel("com.acme.Plain", Level.WARN);
        service.setLogger("com.acme.Plain", Level.DEBUG, SetLevelOptions.withReason("x"));
        service.resetLogger("com.acme.Plain", false);
        assertTrue(adapter.wasRestored("com.acme.Plain"));
        assertNull(adapter.lastRestoreToken("com.acme.Plain"));
    }

    @Test
    void aVendorLevelInEffect_isNeverRestoredWithTheNativeToken() {
        BaselineRegistry vendor = new BaselineRegistry(Map.of(LOGGER, Level.ERROR));
        LevelControlService withVendor = newService(vendor);
        withVendor.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.withReason("x"));
        withVendor.resetLogger(LOGGER, false);
        assertNull(adapter.lastRestoreToken(LOGGER), "the vendor error must stay ERROR, not become FATAL");
    }

    @Test
    void aReloadOfAnUnchangedConfiguration_keepsTheToken() {
        service.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.withReason("x"));
        adapter.setConfiguredLevel(LOGGER, Level.ERROR); // the reload wiped the override; the file declares FATAL again
        service.reapplyActiveOverrides(adapter);
        service.resetLogger(LOGGER, false);
        assertSame(FATAL, adapter.lastRestoreToken(LOGGER));
    }

    @Test
    void aReloadThatChangedWhatTheApplicationDeclares_dropsTheToken() {
        service.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.withReason("x"));
        adapter.setConfiguredLevel(LOGGER, Level.WARN); // the new configuration declares another level
        adapter.setNativeToken(LOGGER, null);
        service.reapplyActiveOverrides(adapter);
        service.resetLogger(LOGGER, false);
        assertNull(adapter.lastRestoreToken(LOGGER));
    }

    @Test
    void aTokenWhoseLevelNoLongerMatchesTheBaseline_isDroppedOnReload() {
        service.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.withReason("x"));
        adapter.setConfiguredLevel(LOGGER, Level.WARN); // still a token, but for a level the baseline no longer holds
        service.reapplyActiveOverrides(adapter);
        service.resetLogger(LOGGER, false);
        assertNull(adapter.lastRestoreToken(LOGGER));
    }

    @Test
    void aRebase_takesTheTokenReadWithTheNewLevel_andNullDropsIt() {
        baselines.captureIfAbsent(LOGGER, adapter);
        Object custom = new Object();
        baselines.rebaseNative(LOGGER, Optional.of(Level.WARN), custom);
        baselines.restore(LOGGER, adapter);
        assertSame(custom, adapter.lastRestoreToken(LOGGER));
        baselines.rebaseNative(LOGGER, Optional.of(Level.WARN), null);
        baselines.restore(LOGGER, adapter);
        assertNull(adapter.lastRestoreToken(LOGGER));
    }
}
