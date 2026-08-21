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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@link LevelControlService#reapplyActiveOverrides} is genuinely
 * re-invokable, per doc/specs/level-control.md's re-appliability note.
 * Nothing in the {@code none} container's production wiring calls this
 * (no reset event exists there) -- this test is what keeps the claim
 * concrete rather than aspirational.
 */
class OverrideReapplyTest {

    @Test
    void reapplyActiveOverrides_restoresStateOnAFreshAdapter() {
        FakeLoggingAdapter originalAdapter = new FakeLoggingAdapter(Level.INFO);
        BaselineRegistry baselines = new BaselineRegistry();
        OverrideRegistry overrides = new OverrideRegistry();
        LevelControlService service = new LevelControlService(
                originalAdapter, baselines, overrides, CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), "alice", "jmx");

        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        // Simulate a framework reset: a brand-new adapter instance, as if
        // the underlying LoggerContext had been thrown away and rebuilt
        // (doc/logaperture-spec.md §15.5 -- exactly the Spring Boot
        // scenario doc/spikes/m0-adapter-grid.md's point 3 found).
        FakeLoggingAdapter freshAdapter = new FakeLoggingAdapter(Level.INFO);
        assertEquals(Level.INFO, freshAdapter.effectiveLevel("com.acme.Worker")); // reset, override gone

        service.reapplyActiveOverrides(freshAdapter);

        assertEquals(Level.DEBUG, freshAdapter.effectiveLevel("com.acme.Worker")); // restored
    }

    @Test
    void reapplyActiveOverrides_calledTwice_isIdempotent() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        BaselineRegistry baselines = new BaselineRegistry();
        OverrideRegistry overrides = new OverrideRegistry();
        LevelControlService service = new LevelControlService(
                adapter, baselines, overrides, CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), "alice", "jmx");
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.reapplyActiveOverrides(adapter);
        service.reapplyActiveOverrides(adapter);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
    }
}
