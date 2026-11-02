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

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/class-loader-detector.md "Acceptance" (identity, dead loader) and "Scope" 1 and 3. */
class LoaderScannerTest {

    private static final String MARKER = ScannerMarker.class.getName();
    private static final FrameworkWatch WATCH = new FrameworkWatch("fake", MARKER,
            List.of("org.logaperture.core.loader."), "fake.Entry");

    private final List<ClassLoader> found = new CopyOnWriteArrayList<>();
    private Class<?>[] loaded = new Class<?>[0];

    private LoaderScanner scanner(FrameworkWatch... watches) {
        return new LoaderScanner(() -> loaded, List.of(watches), (watch, owner) -> found.add(owner),
                new LoaderScanner.Cadence(Duration.ofMillis(5), Duration.ofSeconds(60), Duration.ofMillis(5)));
    }

    @Test
    void findsTheMarkerInTheLoaderThatOwnsIt() throws Exception {
        CopyingLoader owner = new CopyingLoader(false);
        loaded = new Class<?>[] {String.class, owner.markerCopy()};

        scanner(WATCH).scanOnce();

        assertEquals(1, found.size());
        assertSame(owner, found.get(0));
    }

    @Test
    void doesNotReportTheSameLoaderTwice() throws Exception {
        CopyingLoader owner = new CopyingLoader(false);
        loaded = new Class<?>[] {owner.markerCopy()};
        LoaderScanner scanner = scanner(WATCH);

        scanner.scanOnce();
        scanner.scanOnce();

        assertEquals(1, found.size());
    }

    @Test
    void ignoresClassesThatAreNotAMarker() {
        loaded = new Class<?>[] {String.class, List.class, LoaderScannerTest.class};

        scanner(WATCH).scanOnce();

        assertTrue(found.isEmpty());
    }

    /** WF5: two distinct loaders that compare equal are both bound. */
    @Test
    void bindsASecondLoaderThatEqualsTheFirst() throws Exception {
        CopyingLoader first = new CopyingLoader(true);
        CopyingLoader second = new CopyingLoader(true);
        assertEquals(first, second); // the premise: a weak map or a set would merge them
        loaded = new Class<?>[] {first.markerCopy(), second.markerCopy()};

        scanner(WATCH).scanOnce();

        assertEquals(2, found.size());
        assertNotSame(found.get(0), found.get(1));
    }

    @Test
    void forgetMakesALoaderFindableAgain() throws Exception {
        CopyingLoader owner = new CopyingLoader(false);
        loaded = new Class<?>[] {owner.markerCopy()};
        LoaderScanner scanner = scanner(WATCH);

        scanner.scanOnce();
        scanner.forget("fake", owner);
        scanner.scanOnce();

        assertEquals(2, found.size());
    }

    @Test
    void oneMarkerCanBelongToTwoFrameworks() throws Exception {
        FrameworkWatch other = new FrameworkWatch("other", MARKER, List.of(), "other.Entry");
        List<String> frameworks = new ArrayList<>();
        LoaderScanner scanner = new LoaderScanner(() -> loaded, List.of(WATCH, other),
                (watch, owner) -> frameworks.add(watch.framework()), LoaderScanner.Cadence.standard());
        loaded = new Class<?>[] {new CopyingLoader(false).markerCopy()};

        scanner.scanOnce();

        assertEquals(List.of("fake", "other"), frameworks);
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheScan() throws Exception {
        List<ClassLoader> seen = new ArrayList<>();
        LoaderScanner scanner = new LoaderScanner(() -> loaded, List.of(WATCH), (watch, owner) -> {
            seen.add(owner);
            throw new IllegalStateException("bind failed");
        }, LoaderScanner.Cadence.standard());
        loaded = new Class<?>[] {new CopyingLoader(false).markerCopy(), new CopyingLoader(false).markerCopy()};

        scanner.scanOnce();

        assertEquals(2, seen.size());
    }

    @Test
    void theScanThreadFindsALoaderThatAppearsLater() throws Exception {
        LoaderScanner scanner = scanner(WATCH);
        scanner.start();
        try {
            Thread.sleep(30);
            assertTrue(found.isEmpty());
            CopyingLoader owner = new CopyingLoader(false);
            loaded = new Class<?>[] {owner.markerCopy()};

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (found.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, found.size());
        } finally {
            scanner.stop();
        }
    }

    /** The scanner must not keep a webapp's loader alive after the webapp has gone. */
    @Test
    void doesNotKeepALoaderAlive() throws Exception {
        LoaderScanner scanner = scanner(WATCH);
        WeakReference<ClassLoader> ref = bindOne(scanner);
        found.clear();
        loaded = new Class<?>[0];

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertFalse(ref.get() != null, "the scanner kept the class loader reachable");
    }

    private WeakReference<ClassLoader> bindOne(LoaderScanner scanner) throws Exception {
        CopyingLoader owner = new CopyingLoader(false);
        loaded = new Class<?>[] {owner.markerCopy()};
        scanner.scanOnce();
        return new WeakReference<>(owner);
    }

    @Test
    void rejectsAnOwnerPackageWithoutATrailingDot() {
        try {
            new FrameworkWatch("x", MARKER, List.of("org.apache.logging.log4j"), "x.Entry");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
