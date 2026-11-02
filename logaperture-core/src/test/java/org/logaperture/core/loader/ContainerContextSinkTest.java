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
package org.logaperture.core.loader;

import org.junit.jupiter.api.Test;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** doc/specs/class-loader-detector.md C6. */
class ContainerContextSinkTest {

    private final List<String> calls = new ArrayList<>();
    private final AggregateLevelControl aggregate = new AggregateLevelControl();
    private boolean adoptFails;

    private final ContainerIntegration container = new ContainerIntegration() {
        @Override public String id() { return "fake"; }
        @Override public boolean detect() { return true; }
        @Override public AggregateLevelControl activate(Instrumentation inst, CapabilityPolicy policy,
                AuditLog auditLog, VendorDefaults vendorDefaults, boolean stormDetectionEnabled,
                Consumer<AggregateLevelControl> onFirstContextReady) {
            return aggregate;
        }
        @Override public void adoptContext(ContextHandle context) {
            if (adoptFails) {
                throw new IllegalStateException("install failed");
            }
            calls.add("adopt " + context.stableKey());
        }
        @Override public void releaseContext(String stableKey) { calls.add("release " + stableKey); }
    };

    @Test
    void handsAContextToTheContainerAndTellsTheAgent() {
        AtomicInteger ready = new AtomicInteger();
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> ready.incrementAndGet());

        sink.add(FakeFrameworkState.handle("fake:/appA"));
        sink.remove("fake:/appA");

        assertEquals(List.of("adopt fake:/appA", "release fake:/appA"), calls);
        assertEquals(1, ready.get());
    }

    @Test
    void anInstallThatFailsIsNotAnException() {
        adoptFails = true;
        AtomicInteger ready = new AtomicInteger();
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> ready.incrementAndGet());

        sink.add(FakeFrameworkState.handle("fake:/appA")); // must not throw

        assertEquals(0, ready.get()); // nothing came up, so no control surface is announced for it
    }

    @Test
    void onceLetsTheFirstCallerPublishTheSurface() {
        AtomicInteger published = new AtomicInteger();
        Consumer<AggregateLevelControl> shared = ContainerContextSink.once(ops -> published.incrementAndGet());
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, shared);

        shared.accept(aggregate);        // the container's own context
        sink.add(FakeFrameworkState.handle("fake:/appA"));
        sink.add(FakeFrameworkState.handle("fake:/appB"));

        assertEquals(1, published.get());
    }
}
