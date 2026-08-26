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
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.spi.StateStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused coverage of the {@link CapabilityPolicy} plumbing itself, as
 * distinct from {@link LevelControlServiceTest}'s broader behavioral
 * coverage of where each check sits in the call ordering.
 */
class CapabilityCheckTest {

    @Test
    void allowAll_grantsEveryCapability() {
        CapabilityPolicy policy = CapabilityPolicy.allowAll();
        for (Capability capability : Capability.values()) {
            assertTrue(policy.isGranted(capability));
        }
    }

    @Test
    void denyAll_deniesEveryCapability() {
        CapabilityPolicy policy = CapabilityPolicy.denyAll();
        for (Capability capability : Capability.values()) {
            assertTrue(!policy.isGranted(capability));
        }
    }

    @Test
    void capabilityDeniedException_carriesTheDeniedCapability() {
        CapabilityDeniedException exception = new CapabilityDeniedException(Capability.LEVEL_RAISE);
        assertEquals(Capability.LEVEL_RAISE, exception.capability());
    }

    @Test
    void resetLevel_requiresLevelLower() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        BaselineRegistry baselines = new BaselineRegistry();
        OverrideRegistry overrides = new OverrideRegistry();
        InMemoryAuditLog auditLog = new InMemoryAuditLog();

        LevelControlService allowAll = new LevelControlService(
                adapter, baselines, overrides, CapabilityPolicy.allowAll(), auditLog, StateStore.noOp(), "alice", "jmx");
        allowAll.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        LevelControlService noLower = new LevelControlService(
                adapter, baselines, overrides,
                capability -> capability != Capability.LEVEL_LOWER, auditLog, StateStore.noOp(), "alice", "jmx");

        assertThrows(CapabilityDeniedException.class, () -> noLower.resetLevel("com.acme.Worker"));
    }

    @Test
    void resetAll_requiresLevelLower() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        BaselineRegistry baselines = new BaselineRegistry();
        OverrideRegistry overrides = new OverrideRegistry();
        InMemoryAuditLog auditLog = new InMemoryAuditLog();

        LevelControlService noLower = new LevelControlService(
                adapter, baselines, overrides,
                capability -> capability != Capability.LEVEL_LOWER, auditLog, StateStore.noOp(), "alice", "jmx");

        assertThrows(CapabilityDeniedException.class, noLower::resetAll);
    }
}
