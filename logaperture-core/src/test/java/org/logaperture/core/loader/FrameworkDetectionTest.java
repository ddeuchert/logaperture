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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/class-loader-detector.md "Scope" 1, 6 and C1, C6, as the agent assembles them. */
class FrameworkDetectionTest {

    private static final String FAKE_ENTRY = "org.logaperture.fixture.bridge.FakeEntry";
    private static final FrameworkWatch WATCH = new FrameworkWatch("fake", ScannerMarker.class.getName(),
            List.of("org.logaperture.fixture.owner."), FAKE_ENTRY, "org.fake.jul.");

    private static final BridgeSource ADAPTER_CLASSES = name -> {
        if (!name.startsWith("org.logaperture.fixture.bridge.")) {
            return null;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    };

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AggregateLevelControl aggregate = new AggregateLevelControl();
    private final ContainerIntegration container = new ContainerIntegration() {
        @Override public String id() { return "fake"; }
        @Override public boolean detect() { return true; }
        @Override public AggregateLevelControl activate(Instrumentation inst, CapabilityPolicy policy,
                AuditLog auditLog, VendorDefaults vendorDefaults, boolean stormDetectionEnabled,
                Consumer<AggregateLevelControl> onFirstContextReady) {
            return aggregate;
        }
        @Override public void adoptContext(ContextHandle context) { calls.add("adopt " + context.stableKey()); }
        @Override public void releaseContext(String stableKey) { calls.add("release " + stableKey); }
    };

    @BeforeEach
    void reset() {
        FakeFrameworkState.reset();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached within 5 s");
    }

    @Test
    void aFrameworkFoundInABundledLoaderReachesTheContainerAndTheAgentIsToldOnce() throws Exception {
        FakeFrameworkState.ready.set(true);
        CopyingLoader owner = new CopyingLoader(false);
        AtomicInteger told = new AtomicInteger();
        Consumer<AggregateLevelControl> onReady = ContainerContextSink.once(ops -> told.incrementAndGet());
        onReady.accept(aggregate); // the container's own context came up first

        LoaderScanner scanner = FrameworkDetection.start(() -> {
            try {
                return new Class<?>[] {owner.markerCopy()};
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }, container, aggregate, onReady, List.of(WATCH), w -> ADAPTER_CLASSES,
                ClassLoader.getSystemClassLoader(), new LoaderScanner.Cadence(Duration.ofMillis(5),
                        Duration.ofSeconds(60), Duration.ofMillis(5)),
                new BoundInstance.Timing(Duration.ofMillis(5), Duration.ofSeconds(5)));
        try {
            await(() -> calls.contains("adopt fake:one"));

            FakeFrameworkState.shutdown.get().run(); // the framework shuts down
            await(() -> calls.contains("release fake:one"));
            assertEquals(1, told.get());
        } finally {
            scanner.stop();
        }
    }

    @Test
    void noWatchesStartsNothing() {
        LoaderScanner scanner = FrameworkDetection.start(() -> new Class<?>[0], container, aggregate, ops -> { },
                List.of(), w -> ADAPTER_CLASSES, ClassLoader.getSystemClassLoader(),
                LoaderScanner.Cadence.standard(), BoundInstance.Timing.standard());

        scanner.scanOnce(); // nothing to find, nothing to throw
        assertTrue(calls.isEmpty());
    }

    /** C1: only a manager that belongs to a framework with a watch is left to the detector. */
    @Test
    void aJulManagerIsLeftToTheDetectorOnlyForAFrameworkItWatches() {
        assertTrue(KnownFrameworks.julManagerFrontsABoundFramework("org.fake.jul.LogManager", List.of(WATCH)));
        assertFalse(KnownFrameworks.julManagerFrontsABoundFramework("org.jboss.logmanager.LogManager", List.of(WATCH)));
        assertFalse(KnownFrameworks.julManagerFrontsABoundFramework(null, List.of(WATCH)));
        assertFalse(KnownFrameworks.julManagerFrontsABoundFramework("org.fake.jul.LogManager", List.of()));
    }

    /** Log4j 2 is the one framework the agent looks for in 1.1.0 (doc/specs/log4j2-adapter.md). */
    @Test
    void log4j2IsTheKnownFramework() {
        assertEquals(List.of(KnownFrameworks.LOG4J2), KnownFrameworks.all());
        assertEquals("log4j2", KnownFrameworks.LOG4J2.framework());
        assertTrue(KnownFrameworks.julManagerFrontsABoundFramework(
                "org.apache.logging.log4j.jul.LogManager", KnownFrameworks.all()),
                "log4j-jul's manager is a front for Log4j 2, not a context of its own (C1)");
    }

    @org.junit.jupiter.api.AfterEach
    void clearBindFailures() {
        BindFailures.clear(); // process-wide rows must not leak into other tests' doctor assertions
    }
}
