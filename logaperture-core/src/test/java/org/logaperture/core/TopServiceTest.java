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
import org.logaperture.api.LoggerByteCount;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code logctl top}'s engine — see doc/specs/top.md "Testing". */
class TopServiceTest {

    private FakeLoggingAdapter adapter;
    private TopService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        service = new TopService(adapter, CapabilityPolicy.allowAll());
    }

    @Test
    void topLoggers_requiresView() {
        TopService denied = new TopService(adapter, CapabilityPolicy.denyAll());
        assertThrows(CapabilityDeniedException.class, () -> denied.topLoggers(0));
    }

    @Test
    void beforeStartMeasuring_measurementStartedAtIsNull() {
        assertNull(service.topLoggers(0).measurementStartedAt());
    }

    @Test
    void startMeasuring_installsCountingAndRecordsTheStartInstant() {
        service.startMeasuring();

        assertEquals(1, adapter.installByteCountingCallCount());
        assertNotNull(service.topLoggers(0).measurementStartedAt());
    }

    @Test
    void startMeasuring_calledAgain_reinstallsButNeverMovesTheStartInstantForward() {
        service.startMeasuring();
        Instant first = service.topLoggers(0).measurementStartedAt();

        service.startMeasuring();
        service.startMeasuring();

        assertEquals(3, adapter.installByteCountingCallCount(), "each call re-confirms the wrap");
        assertEquals(first, service.topLoggers(0).measurementStartedAt(), "the clock itself never resets");
    }

    @Test
    void topLoggers_sortsWorstFirstByTotalBytes() {
        service.startMeasuring();
        adapter.setByteCounts(List.of(
                new LoggerByteCount("quiet", 10L, 0L),
                new LoggerByteCount("loud", 1_000L, 0L),
                new LoggerByteCount("medium", 100L, 0L)));

        List<LoggerByteCount> rows = service.topLoggers(0).loggers();

        assertEquals(List.of("loud", "medium", "quiet"), rows.stream().map(LoggerByteCount::loggerName).toList());
    }

    @Test
    void topLoggers_limitTruncatesToTheWorstN() {
        service.startMeasuring();
        adapter.setByteCounts(List.of(
                new LoggerByteCount("a", 300L, 0L),
                new LoggerByteCount("b", 200L, 0L),
                new LoggerByteCount("c", 100L, 0L)));

        List<LoggerByteCount> rows = service.topLoggers(2).loggers();

        assertEquals(List.of("a", "b"), rows.stream().map(LoggerByteCount::loggerName).toList());
    }

    @Test
    void topLoggers_zeroOrNegativeLimitMeansEveryTrackedLogger() {
        service.startMeasuring();
        adapter.setByteCounts(List.of(new LoggerByteCount("a", 1L, 0L), new LoggerByteCount("b", 2L, 0L)));

        assertEquals(2, service.topLoggers(0).loggers().size());
        assertEquals(2, service.topLoggers(-1).loggers().size());
    }

    @Test
    void topLoggers_noTrackedLoggers_returnsAnEmptyListNotAFailure() {
        service.startMeasuring();

        assertTrue(service.topLoggers(0).loggers().isEmpty());
    }
}
