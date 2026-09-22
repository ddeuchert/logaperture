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
import org.logaperture.api.Storm;
import org.logaperture.api.StormReport;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code logctl storms}'s engine — see doc/specs/storm-detection.md "Testing". */
class StormServiceTest {

    private static final long THRESHOLD = 3;

    private FakeLoggingAdapter adapter;
    private StormDetector detector;
    private StormService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        detector = new StormDetector(THRESHOLD, Duration.ofSeconds(10), Duration.ofSeconds(60), 4_000, 100, 8 * 1024);
        service = new StormService(adapter, CapabilityPolicy.allowAll(), detector);
    }

    @Test
    void activeStorms_requiresView() {
        StormService denied = new StormService(adapter, CapabilityPolicy.denyAll(), detector);
        assertThrows(CapabilityDeniedException.class, () -> denied.activeStorms(0));
    }

    @Test
    void beforeStartDetection_measurementStartedAtIsNull() {
        assertNull(service.activeStorms(0).measurementStartedAt());
    }

    @Test
    void startDetection_installsObserverAndRecordsTheStartInstant() {
        service.startDetection();

        assertEquals(1, adapter.installStormDetectionCallCount());
        assertNotNull(service.activeStorms(0).measurementStartedAt());
    }

    @Test
    void startDetection_calledAgain_reinstallsButNeverMovesTheStartInstantForward() {
        service.startDetection();
        Instant first = service.activeStorms(0).measurementStartedAt();

        service.startDetection();
        service.startDetection();

        assertEquals(3, adapter.installStormDetectionCallCount());
        assertEquals(first, service.activeStorms(0).measurementStartedAt());
    }

    @Test
    void activeStorms_sortsWorstFirst_ongoingBeforeEnded_thenByEventCount() {
        feed("quiet.Logger", 3);
        feed("loud.Logger", 6);

        List<Storm> storms = service.activeStorms(0).storms();

        assertEquals("loud.Logger", storms.get(0).fingerprint().loggerName());
    }

    @Test
    void activeStorms_limitTruncates_zeroOrNegativeMeansAll() {
        feed("a.Logger", 3);
        feed("b.Logger", 4);
        feed("c.Logger", 5);

        assertEquals(1, service.activeStorms(1).storms().size());
        assertEquals(3, service.activeStorms(0).storms().size());
        assertEquals(3, service.activeStorms(-1).storms().size());
    }

    @Test
    void activeStorms_trackedAndOngoingCounts_arePreTruncation() {
        feed("a.Logger", 3);
        feed("b.Logger", 3);

        StormReport report = service.activeStorms(1);

        assertEquals(1, report.storms().size());
        assertEquals(2, report.trackedCount());
        assertEquals(2, report.ongoingCount());
    }

    private void feed(String logger, int times) {
        for (int i = 0; i < times; i++) {
            detector.observe(new StormObservation(logger, Level.ERROR, null, "boom " + logger, false, null,
                    () -> "rendered", Instant.now()));
        }
    }
}
