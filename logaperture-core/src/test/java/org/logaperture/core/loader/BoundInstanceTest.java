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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;
import org.logaperture.bridge.DiagnosticLevel;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.spi.ContextHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/class-loader-detector.md "Scope" 5 and 6; spike TC5, TC7, WF6. */
class BoundInstanceTest {

    private static final String FAKE_ENTRY = "org.logaperture.fixture.bridge.FakeEntry";
    private static final FrameworkWatch WATCH = new FrameworkWatch("fake", ScannerMarker.class.getName(),
            List.of("org.logaperture.fixture.owner."), FAKE_ENTRY);
    private static final BoundInstance.Timing FAST =
            new BoundInstance.Timing(Duration.ofMillis(5), Duration.ofSeconds(5));

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

    private final List<String> added = new CopyOnWriteArrayList<>();
    private final List<String> removed = new CopyOnWriteArrayList<>();
    private final ContextSink sink = new ContextSink() {
        @Override public void add(ContextHandle context) { added.add(context.stableKey()); }
        @Override public void remove(ContextHandle context) { removed.add(context.stableKey()); }
    };
    private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        FakeFrameworkState.reset();
        BindFailures.clear();
        Diagnostics.configure(new PrintStream(diagnostics, true, StandardCharsets.UTF_8), DiagnosticLevel.DEBUG);
    }

    @AfterEach
    void tearDown() {
        BindFailures.clear();
        Diagnostics.resetToDefault();
    }

    private BoundInstance start(ClassLoader owner, BoundInstance.Timing timing, BridgeSource source) {
        return BoundInstance.start(WATCH, owner, source, ClassLoader.getSystemClassLoader(), sink, timing);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached within 5 s");
    }

    /** The instance closes itself before its thread has finished with the contexts: wait for the thread. */
    private static void awaitBindThreadFinished() throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Thread bind = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("logaperture-fake-bind")).findFirst().orElse(null);
            if (bind == null) {
                return;
            }
            bind.join(50);
        }
        throw new AssertionError("the bind thread did not finish within 5 s");
    }

    private String diagnosticsText() {
        return diagnostics.toString(StandardCharsets.UTF_8);
    }

    @Test
    void touchesNothingUntilTheFrameworkHasStartedThenBinds() throws Exception {
        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES);

        await(() -> FakeFrameworkState.readyCalls.get() >= 3);
        assertEquals(0, FakeFrameworkState.opened.get());
        assertTrue(added.isEmpty());
        assertFalse(instance.isBound());

        FakeFrameworkState.ready.set(true);
        await(instance::isBound);

        assertEquals(1, FakeFrameworkState.opened.get());
        assertEquals(List.of("fake:one"), added);
    }

    @Test
    void letsGoWhenTheFrameworkShutsDown() throws Exception {
        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES);
        FakeFrameworkState.ready.set(true);
        await(instance::isBound);

        FakeFrameworkState.shutdown.get().run();

        assertTrue(instance.isClosed());
        assertEquals(List.of("fake:one"), removed);
        assertEquals(1, FakeFrameworkState.closed.get());
    }

    @Test
    void lettingGoTwiceClosesTheEntryOnce() throws Exception {
        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES);
        FakeFrameworkState.ready.set(true);
        await(instance::isBound);

        FakeFrameworkState.shutdown.get().run();
        FakeFrameworkState.shutdown.get().run();

        assertEquals(1, FakeFrameworkState.closed.get());
        assertEquals(List.of("fake:one"), removed);
    }

    /** TC5 race: the framework's shutdown fires while open() is still running. */
    @Test
    void aShutdownDuringOpenRegistersNothingAtAll() throws Exception {
        FakeFrameworkState.shutdownDuringOpen.set(true);
        FakeFrameworkState.ready.set(true);

        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES);

        await(instance::isClosed);
        awaitBindThreadFinished();
        // no adopt, no "first context ready" callback, no remove to undo it
        assertTrue(added.isEmpty(), "added: " + added);
        assertTrue(removed.isEmpty(), "removed: " + removed);
    }

    /** The shutdown lands between two contexts: the one already added is taken back, no more are added. */
    @Test
    void aShutdownBetweenContextsRemovesWhatWasAddedAndAddsNoMore() throws Exception {
        FakeFrameworkState.keys.clear();
        FakeFrameworkState.keys.addAll(List.of("fake:one", "fake:two"));
        FakeFrameworkState.ready.set(true);
        ContextSink shutsDownAfterTheFirst = new ContextSink() {
            @Override public void add(ContextHandle context) {
                added.add(context.stableKey());
                FakeFrameworkState.shutdown.get().run();
            }
            @Override public void remove(ContextHandle context) { removed.add(context.stableKey()); }
        };

        BoundInstance instance = BoundInstance.start(WATCH, new FixtureOwnerLoader(), ADAPTER_CLASSES,
                ClassLoader.getSystemClassLoader(), shutsDownAfterTheFirst, FAST);

        await(instance::isClosed);
        await(() -> !removed.isEmpty());
        assertEquals(List.of("fake:one"), added);
        assertEquals(List.of("fake:one"), removed);
    }

    @Test
    void closesTheBridgeWhenItLetsGo() throws Exception {
        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES);
        FakeFrameworkState.ready.set(true);
        await(instance::isBound);
        BridgeClassLoader bridge = instance.bridge();
        assertSame(bridge, bridge.loadClass(FAKE_ENTRY).getClassLoader()); // loads while bound

        FakeFrameworkState.shutdown.get().run();

        assertThrows(ClassNotFoundException.class, () -> bridge.loadClass("org.logaperture.fixture.bridge.BridgeEntry"));
    }

    /** C4, C8, WF6. */
    @Test
    void givesUpAfterTheBoundWithALineAndNothingRegistered() throws Exception {
        BoundInstance.Timing quick = new BoundInstance.Timing(Duration.ofMillis(5), Duration.ofMillis(150));

        BoundInstance instance = start(new FixtureOwnerLoader(), quick, ADAPTER_CLASSES);

        await(instance::isClosed);
        assertTrue(added.isEmpty());
        assertEquals(1, FakeFrameworkState.closed.get());
        String text = diagnosticsText();
        assertTrue(text.contains("found fake in"), text);
        assertTrue(text.contains("did not bind"), text);
        assertTrue(text.contains("FixtureOwnerLoader"), text);
    }

    /** WF6: an owner that is gone is collected once the instance has given up. */
    @Test
    void givingUpDoesNotKeepTheOwnerAlive() throws Exception {
        BoundInstance.Timing quick = new BoundInstance.Timing(Duration.ofMillis(5), Duration.ofMillis(100));
        WeakReference<ClassLoader> ownerRef = startAndDrop(quick);

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ownerRef.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(ownerRef.get(), "the instance kept the owner reachable after giving up");
    }

    private WeakReference<ClassLoader> startAndDrop(BoundInstance.Timing timing) throws Exception {
        FixtureOwnerLoader owner = new FixtureOwnerLoader();
        BoundInstance instance = start(owner, timing, ADAPTER_CLASSES);
        await(instance::isClosed);
        return new WeakReference<>(owner);
    }

    @Test
    void aFrameworkThatCannotBeBoundIsALineNotAnException() throws Exception {
        FakeFrameworkState.readyThrows.set(true);

        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES);

        await(instance::isClosed);
        await(() -> diagnosticsText().contains("framework cannot be bound")); // the line follows the close
        assertTrue(added.isEmpty());
    }

    /** Slice 7, D7-4: what `doctor` says about a framework found and not bound. */
    @Test
    void aTooOldFramework_isAVersionFinding_andAThrowingOneIsNotBound() throws Exception {
        FakeFrameworkState.tooOld.set(true);
        FixtureOwnerLoader old = new FixtureOwnerLoader();
        start(old, FAST, ADAPTER_CLASSES);
        await(() -> BindFailures.findings().size() == 1); // closed is set before the row is recorded

        FakeFrameworkState.tooOld.set(false);
        FakeFrameworkState.readyThrows.set(true);
        FixtureOwnerLoader throwing = new FixtureOwnerLoader();
        start(throwing, FAST, ADAPTER_CLASSES);
        await(() -> BindFailures.findings().size() == 2);

        List<DoctorFinding> findings = BindFailures.findings();
        assertEquals(List.of("fake.version", "fake.not-bound"),
                findings.stream().map(DoctorFinding::check).toList());
        DoctorFinding version = findings.get(0);
        assertEquals(Severity.WARNING, version.severity());
        assertTrue(version.summary().contains("fake 1.0 is too old"), version.summary());
        assertTrue(version.subject().contains("FixtureOwnerLoader"), version.subject());
        assertEquals(null, version.context(), "process-wide: no context");
        assertTrue(findings.get(1).summary().contains("framework cannot be bound"), findings.get(1).summary());
    }

    @Test
    void aFrameworkThatGaveUpWaiting_isNotBound() throws Exception {
        BoundInstance.Timing quick = new BoundInstance.Timing(Duration.ofMillis(5), Duration.ofMillis(100));
        start(new FixtureOwnerLoader(), quick, ADAPTER_CLASSES);
        await(() -> !BindFailures.findings().isEmpty());
        assertEquals(List.of("fake.not-bound"),
                BindFailures.findings().stream().map(DoctorFinding::check).toList());
        assertEquals(Severity.INFO, BindFailures.findings().get(0).severity(), "usually a framework nobody used");
    }

    @Test
    void aBoundFramework_leavesNoFinding() throws Exception {
        FakeFrameworkState.ready.set(true);
        await(start(new FixtureOwnerLoader(), FAST, ADAPTER_CLASSES)::isBound);
        assertEquals(List.of(), BindFailures.findings());
    }

    @Test
    void aBindThatSucceedsAfterAnEarlierFailureOfTheSameLoader_clearsTheRow() throws Exception {
        BoundInstance.Timing quick = new BoundInstance.Timing(Duration.ofMillis(5), Duration.ofMillis(100));
        FixtureOwnerLoader owner = new FixtureOwnerLoader();
        start(owner, quick, ADAPTER_CLASSES);
        await(() -> BindFailures.findings().size() == 1);

        FakeFrameworkState.ready.set(true);
        await(start(owner, FAST, ADAPTER_CLASSES)::isBound);
        assertEquals(List.of(), BindFailures.findings());
    }

    @Test
    void anEntryClassThatCannotBeCreatedIsALineNotAnException() {
        BoundInstance instance = start(new FixtureOwnerLoader(), FAST, name -> null);

        assertTrue(instance.isClosed());
        assertTrue(diagnosticsText().contains("adapter could not be created"), diagnosticsText());
    }

    /** TC7: the waiting thread is not attributed to the owner. */
    @Test
    void theWaitingThreadTakesTheSystemLoaderAsContextLoader() throws Exception {
        FixtureOwnerLoader owner = new FixtureOwnerLoader();
        start(owner, FAST, ADAPTER_CLASSES);

        await(() -> FakeFrameworkState.pollerContextLoader.get() != null);

        assertSame(ClassLoader.getSystemClassLoader(), FakeFrameworkState.pollerContextLoader.get());
    }

    @Test
    void theBinderStartsAnInstanceForEachLoaderTheScannerFinds() throws Exception {
        FakeFrameworkState.ready.set(true);
        FrameworkBinder binder = new FrameworkBinder(w -> ADAPTER_CLASSES, ClassLoader.getSystemClassLoader(), sink, FAST);
        CopyingLoader owner = new CopyingLoader(false);
        LoaderScanner scanner = new LoaderScanner(() -> {
            try {
                return new Class<?>[] {owner.markerCopy()};
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }, List.of(WATCH), binder, LoaderScanner.Cadence.standard());

        scanner.scanOnce();

        await(() -> !added.isEmpty());
        assertEquals(List.of("fake:one"), added);
    }
}
