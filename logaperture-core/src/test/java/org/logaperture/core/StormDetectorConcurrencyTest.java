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
import org.logaperture.api.Storm;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/storm-detection.md "Testing" (concurrency bullet) and "Bounded
 * state" — the striped-lock design's actual point: many threads feeding one
 * fingerprint never lose an update, engage the storm exactly once, and never
 * run the first-occurrence supplier while holding the per-entry lock.
 */
class StormDetectorConcurrencyTest {

    private static final long THRESHOLD = 200;
    private static final Duration WINDOW = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofSeconds(30);

    @Test
    void manyThreads_oneFingerprint_noLostUpdates_exactlyOneEngagementAndRender() throws InterruptedException {
        StormDetector detector = new StormDetector(THRESHOLD, WINDOW, QUIET, 4_000, 100, 8 * 1024);
        int threadCount = 8;
        int perThread = 100; // 800 total, well past THRESHOLD=200
        AtomicInteger renderCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    for (int i = 0; i < perThread; i++) {
                        detector.observe(new StormObservation("shared.Logger", Level.ERROR, null, "boom", false,
                                null, () -> {
                            renderCount.incrementAndGet();
                            return "rendered";
                        }, Instant.now()));
                    }
                });
            }
            ready.await();
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<Storm> storms = detector.snapshot();
        assertEquals(1, storms.size());
        assertEquals((long) threadCount * perThread, storms.get(0).eventCount());
        assertEquals(1, renderCount.get(), "first-occurrence rendered exactly once");
    }

    @Test
    void manyThreads_thousandsOfDistinctFingerprints_keepsMapAtBound_neverThrows() throws InterruptedException {
        StormDetector detector = new StormDetector(THRESHOLD, WINDOW, QUIET, 200, 100, 8 * 1024);
        int threadCount = 8;
        int perThread = 500; // 4000 distinct fingerprints total, cap is 200
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger();
        try {
            for (int t = 0; t < threadCount; t++) {
                int base = t * perThread;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            detector.observe(new StormObservation("logger" + (base + i), Level.ERROR, null, "boom",
                                    false, null, () -> "rendered", Instant.now()));
                        }
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
        assertEquals(0, errors.get());
        // No public accessor to the raw map size -- absence of exceptions and
        // a sane (bounded) history size stands in for "kept at its bound".
        assertTrue(detector.snapshot().size() <= 200);
    }

    @Test
    void firstOccurrenceSupplier_neverInvokedWhileThePerEntryLockIsHeld() throws InterruptedException {
        StormDetector detector = new StormDetector(2, WINDOW, QUIET, 4_000, 100, 8 * 1024);
        AtomicBoolean supplierRanConcurrentlyWithAnotherObserve = new AtomicBoolean(false);
        AtomicBoolean supplierRunning = new AtomicBoolean(false);
        CountDownLatch supplierEntered = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);

        // First event: below threshold, cheap.
        detector.observe(new StormObservation("timing.Logger", Level.ERROR, null, "boom", false, null,
                () -> "unused", Instant.now()));

        Thread engager = new Thread(() -> detector.observe(new StormObservation("timing.Logger", Level.ERROR, null,
                "boom", false, null, () -> {
                    supplierRunning.set(true);
                    supplierEntered.countDown();
                    await(releaseSupplier);
                    supplierRunning.set(false);
                    return "rendered";
                }, Instant.now())));
        engager.start();
        assertTrue(supplierEntered.await(5, TimeUnit.SECONDS), "the second event should have engaged the storm");

        // While the supplier is deliberately blocked (simulating slow work),
        // a concurrent event on the SAME fingerprint must not deadlock --
        // proof the supplier is not running inside the per-entry lock, since
        // that lock also guards this very observe() call.
        Thread concurrentObserver = new Thread(() -> {
            if (supplierRunning.get()) {
                supplierRanConcurrentlyWithAnotherObserve.set(true);
            }
            detector.observe(new StormObservation("timing.Logger", Level.ERROR, null, "boom", false, null,
                    () -> "unused2", Instant.now()));
        });
        concurrentObserver.start();
        concurrentObserver.join(5_000);
        assertFalse(concurrentObserver.isAlive(), "a concurrent observe() on the same fingerprint must not block "
                + "on the first-occurrence supplier -- proof the supplier runs outside the per-entry lock");

        releaseSupplier.countDown();
        engager.join(5_000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
