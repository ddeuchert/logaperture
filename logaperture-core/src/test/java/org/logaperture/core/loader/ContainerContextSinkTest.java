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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        @Override public boolean frameworkNotBound(String framework, ClassLoader owner) {
            calls.add("not bound " + framework);
            return takesOver;
        }
    };
    private boolean takesOver;

    @Test
    void handsAContextToTheContainerAndTellsTheAgent() {
        AtomicInteger ready = new AtomicInteger();
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> ready.incrementAndGet());

        ContextHandle appA = FakeFrameworkState.handle("fake:/appA");
        sink.add(appA);
        sink.remove(appA);

        assertEquals(List.of("adopt fake:/appA", "release fake:/appA"), calls);
        assertEquals(1, ready.get());
    }

    /** A redeploy registers the new copy before the old copy's shutdown listener fires. */
    @Test
    void aStaleRemoveDoesNotTakeAwayTheContextThatReplacedIt() {
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> { });
        ContextHandle oldCopy = FakeFrameworkState.handle("fake:/appA");
        ContextHandle newCopy = FakeFrameworkState.handle("fake:/appA");

        sink.add(oldCopy);
        sink.add(newCopy);
        sink.remove(oldCopy);   // the old instance lets go: must not release the live one
        assertEquals(List.of("adopt fake:/appA", "adopt fake:/appA"), calls);

        sink.remove(newCopy);
        sink.remove(newCopy);   // and removing it twice releases once
        assertEquals(List.of("adopt fake:/appA", "adopt fake:/appA", "release fake:/appA"), calls);
    }

    @Test
    void removingAContextThatWasNeverAddedReleasesNothing() {
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> { });

        sink.remove(FakeFrameworkState.handle("fake:/never"));

        assertTrue(calls.isEmpty());
    }

    @Test
    void anInstallThatFailsIsNotAnException() {
        adoptFails = true;
        AtomicInteger ready = new AtomicInteger();
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> ready.incrementAndGet());

        sink.add(FakeFrameworkState.handle("fake:/appA")); // must not throw

        assertEquals(0, ready.get()); // nothing came up, so no control surface is announced for it
    }

    /** A first publication that throws is not the last chance: the next context tries again. */
    @Test
    void aControlSurfaceThatFailedToComeUpIsTriedAgainByTheNextContext() {
        AtomicInteger attempts = new AtomicInteger();
        Consumer<AggregateLevelControl> publish = ContainerContextSink.once(ops -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("MBean server not ready");
            }
        });
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, publish);

        sink.add(FakeFrameworkState.handle("fake:/appA")); // installs, publishing fails: must not throw
        sink.add(FakeFrameworkState.handle("fake:/appB")); // tries again, succeeds
        sink.add(FakeFrameworkState.handle("fake:/appC")); // and is then done

        assertEquals(2, attempts.get());
        assertEquals(List.of("adopt fake:/appA", "adopt fake:/appB", "adopt fake:/appC"), calls);
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

    /** doc/specs/surface-on-bind-failure.md D1: the sink brings the surface up for a framework found and not bound. */
    @Test
    void unbound_runsTheUnboundCallbackWithTheAggregate_andAFailureIsNotAnException() {
        List<AggregateLevelControl> seen = new java.util.ArrayList<>();
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> { }, seen::add);
        sink.unbound("log4j2");
        assertEquals(List.of(aggregate), seen);
        assertTrue(calls.isEmpty(), "no context was adopted");

        ContainerContextSink failing = new ContainerContextSink(container, aggregate, ops -> { }, ops -> {
            throw new IllegalStateException("no surface");
        });
        failing.unbound("log4j2"); // reported as a line, never thrown into the detector
    }

    @Test
    void unbound_doesNothingWithTheDefaultCallback() {
        new ContainerContextSink(container, aggregate, ops -> { }).unbound("log4j2");
        assertTrue(calls.isEmpty());
    }

    /** doc/specs/jul-fallback-on-log4j-bind-failure.md J3, J5: the notice reaches the container, and doctor hears of a takeover. */
    @Test
    void aNotBoundNoticeReachesTheContainer_andDoctorSaysJulIsControlledWhenItTookOver() {
        BindFailures.clear();
        ClassLoader owner = new FixtureOwnerLoader();
        ContainerContextSink sink = new ContainerContextSink(container, aggregate, ops -> { });

        BindFailures.record("log4j2", owner, "loader", "too old", BindFailures.Kind.VERSION);
        assertEquals(false, sink.notBound("log4j2", owner));
        assertTrue(BindFailures.findings().get(0).detail() == null);

        takesOver = true;
        assertEquals(true, sink.notBound("log4j2", owner));
        assertEquals(List.of("not bound log4j2", "not bound log4j2"), calls);
        assertTrue(BindFailures.findings().get(0).detail().contains("java.util.logging is controlled instead"));
        BindFailures.clear();
    }
}
