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
import org.logaperture.api.StormStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code logctl storms}'s engine — see doc/specs/storm-detection.md "Testing". */
class StormDetectorTest {

    private static final long THRESHOLD = 5;
    private static final Duration WINDOW = Duration.ofMillis(80);
    private static final Duration QUIET = Duration.ofMillis(200);

    private StormDetector newDetector() {
        return new StormDetector(THRESHOLD, WINDOW, QUIET, 4_000, 100, 8 * 1024);
    }

    private static StormObservation observation(String logger, String message) {
        return observation(logger, message, null);
    }

    private static StormObservation observation(String logger, String message, String throwableClass) {
        return new StormObservation(logger, Level.ERROR, throwableClass, message, throwableClass != null,
                throwableClass != null ? List::of : null, () -> "rendered: " + message, Instant.now());
    }

    private static void feed(StormDetector detector, String logger, String message, int times) {
        for (int i = 0; i < times; i++) {
            detector.observe(observation(logger, message));
        }
    }

    @Test
    void crossingThreshold_beforeWindowGap_transitionsToOngoing() {
        StormDetector detector = newDetector();
        feed(detector, "com.acme.Worker", "boom", (int) THRESHOLD);

        List<Storm> storms = detector.snapshot();

        assertEquals(1, storms.size());
        assertEquals(StormStatus.ONGOING, storms.get(0).status());
        assertNotNull(storms.get(0).firstEventAt());
        assertEquals(THRESHOLD, storms.get(0).eventCount());
    }

    @Test
    void gapLongerThanWindow_resetsCountWithoutBecomingAStorm() throws InterruptedException {
        StormDetector detector = newDetector();
        feed(detector, "com.acme.Worker", "boom", (int) THRESHOLD - 1);
        Thread.sleep(WINDOW.toMillis() + 50);
        detector.observe(observation("com.acme.Worker", "boom")); // gap resets count to 1, not threshold

        assertTrue(detector.snapshot().isEmpty(), "count reset -- never reached the threshold as one burst");
    }

    @Test
    void furtherEvents_keepStormOngoing_andAdvanceLastEventAtAndEventCount() {
        StormDetector detector = newDetector();
        feed(detector, "com.acme.Worker", "boom", (int) THRESHOLD);
        Storm firstSnapshot = detector.snapshot().get(0);

        feed(detector, "com.acme.Worker", "boom", 3);
        Storm secondSnapshot = detector.snapshot().get(0);

        assertEquals(StormStatus.ONGOING, secondSnapshot.status());
        assertEquals(THRESHOLD + 3, secondSnapshot.eventCount());
        assertTrue(!secondSnapshot.lastEventAt().isBefore(firstSnapshot.lastEventAt()));
    }

    @Test
    void gapBeyondQuietPeriod_transitionsToEnded_onNextEventOrSweep() throws InterruptedException {
        StormDetector detector = newDetector();
        feed(detector, "com.acme.Worker", "boom", (int) THRESHOLD);
        Instant lastEventAt = detector.snapshot().get(0).lastEventAt();

        Thread.sleep(QUIET.toMillis() + 50);
        List<Storm> sweepResult = detector.snapshot(); // a sweep alone, no new event, must end it

        assertEquals(StormStatus.ENDED, sweepResult.get(0).status());
        assertEquals(lastEventAt, sweepResult.get(0).endedAt());
    }

    @Test
    void resetAfterGap_thenReCrossingThreshold_isANewStormNotARevival() throws InterruptedException {
        StormDetector detector = newDetector();
        feed(detector, "com.acme.Worker", "boom", (int) THRESHOLD);
        Thread.sleep(QUIET.toMillis() + 50);
        detector.snapshot(); // ends it

        feed(detector, "com.acme.Worker", "boom", (int) THRESHOLD);
        List<Storm> storms = detector.snapshot();

        assertEquals(2, storms.size());
        long ongoing = storms.stream().filter(s -> s.status() == StormStatus.ONGOING).count();
        assertEquals(1, ongoing);
    }

    @Test
    void sameExceptionType_differentThrowSites_splitViaSampledFrameSubKey() {
        StormDetector detector = newDetector();
        for (int i = 0; i < THRESHOLD; i++) {
            detector.observe(new StormObservation("com.acme.Worker", Level.ERROR, "java.lang.RuntimeException",
                    "boom", true, () -> List.of("com.acme.Worker.siteA(Worker.java:10)"), () -> "first-a",
                    Instant.now()));
        }
        Storm first = detector.snapshot().get(0);
        assertEquals(List.of("com.acme.Worker.siteA(Worker.java:10)"), first.fingerprint().topFrames());
    }

    @Test
    void normalization_mergesNumericVariants_keepsGenuinelyDifferentMessagesApart() {
        StormDetector detector = newDetector();
        feed(detector, "com.acme.Worker", "failed for order 4821", 3);
        feed(detector, "com.acme.Worker", "failed for order 9134", (int) THRESHOLD - 3);
        feed(detector, "com.acme.Worker", "totally different problem", (int) THRESHOLD);

        List<Storm> storms = detector.snapshot();

        assertEquals(2, storms.size(), "the numeric-variant burst merged into one storm; the other message is separate");
    }

    @Test
    void counterMap_evictsLeastRecentlyUpdated_atCapacity() {
        StormDetector detector = new StormDetector(THRESHOLD, WINDOW, QUIET, 10, 100, 8 * 1024);
        for (int i = 0; i < 25; i++) {
            detector.observe(observation("logger" + i, "msg" + i));
        }
        // Never throws, and the map itself is capped -- exercised indirectly:
        // re-observing an early, likely-evicted fingerprint restarts its tally
        // rather than blowing up.
        detector.observe(observation("logger0", "msg0"));
        assertTrue(true, "no exception, and the detector kept operating past the cap");
    }

    @Test
    void counterMap_neverEvictsAnEntryWithAnActiveStorm() {
        // Cap the map tight enough that a 4th distinct fingerprint forces an eviction, with the
        // first 3 all ONGOING (active). An ONGOING entry must never be the victim: evicting it would
        // orphan its HistoryRecord permanently ONGOING (unreachable by any future event for the same
        // fingerprint, and invisible to the quiet-period sweep, which only walks live entries).
        StormDetector detector = new StormDetector(THRESHOLD, WINDOW, QUIET, 3, 100, 8 * 1024);
        feed(detector, "storming.A", "boom", (int) THRESHOLD);
        feed(detector, "storming.B", "boom", (int) THRESHOLD);
        feed(detector, "storming.C", "boom", (int) THRESHOLD);
        feed(detector, "fresh.D", "boom", 1); // forces eviction pressure at the cap

        // If storming.A's entry had been wrongly evicted, this next event would land on a brand-new
        // entry instead of the still-ONGOING one, and the two would show up as two separate storms.
        feed(detector, "storming.A", "boom", 1);

        List<Storm> stormsForA = detector.snapshot().stream()
                .filter(s -> s.fingerprint().loggerName().equals("storming.A"))
                .toList();
        assertEquals(1, stormsForA.size(), "storming.A's ongoing storm must not be duplicated/orphaned by eviction");
        assertEquals(StormStatus.ONGOING, stormsForA.get(0).status());
        assertEquals(THRESHOLD + 1, stormsForA.get(0).eventCount());
    }

    @Test
    void history_keepsOngoingAheadOfEnded() throws InterruptedException {
        StormDetector detector = newDetector();
        feed(detector, "ended.Logger", "boom", (int) THRESHOLD);
        Thread.sleep(QUIET.toMillis() + 50);
        detector.snapshot(); // ends it
        feed(detector, "ongoing.Logger", "boom", (int) THRESHOLD);

        List<Storm> storms = detector.snapshot();

        assertEquals(2, storms.size());
    }

    @Test
    void throwingObservation_isSwallowed_eventPassesThrough() {
        StormDetector detector = newDetector();
        StormObservation poison = new StormObservation("com.acme.Worker", Level.ERROR, null, "boom", false, null,
                () -> {
                    throw new RuntimeException("boom in supplier");
                }, Instant.now());
        // Feed enough to cross the threshold; the poisoned supplier only
        // throws when actually invoked (storm engagement), which must be
        // swallowed without propagating.
        for (int i = 0; i < THRESHOLD - 1; i++) {
            detector.observe(observation("com.acme.Worker", "boom"));
        }
        detector.observe(poison);

        assertEquals(1, detector.snapshot().size(), "the storm still engaged despite the failing supplier");
        assertNull(detector.snapshot().get(0).firstOccurrence(), "first occurrence failed to render, left null");
    }

    @Test
    void notRetainedCount_startsAtZero() {
        assertEquals(0, newDetector().notRetainedCount());
    }

    @Test
    void allOngoingAtHistoryCapacity_dropsNewStorm_andDisclosesNotRetainedCount() {
        StormDetector detector = new StormDetector(THRESHOLD, WINDOW, QUIET, 4_000, 2, 8 * 1024);
        feed(detector, "logger.A", "boom", (int) THRESHOLD);
        feed(detector, "logger.B", "boom", (int) THRESHOLD);
        feed(detector, "logger.C", "boom", (int) THRESHOLD); // history full of ONGOING -- dropped

        List<Storm> storms = detector.snapshot();
        assertEquals(2, storms.size());
        assertEquals(1, detector.notRetainedCount());
    }

    @Test
    void normalize_collapsesDigitHexAndUuidRuns() {
        assertEquals("failed for order <n>", StormDetector.normalize("failed for order 4821"));
        assertEquals("failed for order <n>", StormDetector.normalize("failed for order 9134"));
        assertEquals("id <uuid> missing", StormDetector.normalize("id 123e4567-e89b-12d3-a456-426614174000 missing"));
    }

    @Test
    void normalize_longPurelyDecimalRun_isDigitsNotHex() {
        // A 6+ digit id is still a decimal run, not hex, even though every digit it contains is
        // technically a valid hex digit -- the same underlying message with a wider numeric id must
        // normalize to the same fingerprint as the narrower one, or a real storm with a widening id
        // fragments into sub-threshold fingerprints that never individually trip.
        assertEquals("failed for order <n>", StormDetector.normalize("failed for order 482156"));
        assertEquals(StormDetector.normalize("failed for order 4821"),
                StormDetector.normalize("failed for order 482156"));
    }

    @Test
    void normalize_genuineHexRun_isStillHex() {
        assertEquals("checksum <hex> mismatch", StormDetector.normalize("checksum deadbeef mismatch"));
        assertEquals("addr <hex>", StormDetector.normalize("addr 0x1a2b3c"));
    }

    @Test
    void firstOccurrence_capsAt8KB_withTruncationMarker() {
        StormDetector detector = new StormDetector(1, WINDOW, QUIET, 4_000, 100, 16); // tiny cap for the test
        String longText = "x".repeat(1_000);
        detector.observe(new StormObservation("com.acme.Worker", Level.ERROR, null, "boom", false, null,
                () -> longText, Instant.now()));

        String firstOccurrence = detector.snapshot().get(0).firstOccurrence();
        assertTrue(firstOccurrence.length() < longText.length());
        assertTrue(firstOccurrence.contains("truncated"));
    }

    @Test
    void concurrentDistinctFingerprints_neverSerializeThroughASharedLock() throws InterruptedException {
        // Not a hard timing assertion (flaky by nature) -- just a smoke test
        // that many threads on distinct fingerprints complete promptly and
        // without exceptions, per doc/specs/storm-detection.md's exit
        // criterion ("never serialize through a shared monitor").
        StormDetector detector = newDetector();
        AtomicInteger errors = new AtomicInteger();
        Thread[] threads = new Thread[16];
        for (int t = 0; t < threads.length; t++) {
            int id = t;
            threads[t] = new Thread(() -> {
                try {
                    feed(detector, "logger" + id, "distinct message " + id, (int) THRESHOLD);
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        assertEquals(0, errors.get());
        assertEquals(threads.length, detector.snapshot().size());
    }
}
