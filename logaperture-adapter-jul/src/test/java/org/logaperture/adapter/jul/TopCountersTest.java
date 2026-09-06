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
package org.logaperture.adapter.jul;

import org.junit.jupiter.api.Test;
import org.logaperture.api.LoggerByteCount;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/top.md "Bounded state" — the LRU-capped counter store. */
class TopCountersTest {

    @Test
    void record_accumulatesAcrossMultipleCallsForTheSameLogger() {
        TopCounters counters = new TopCounters(10);

        counters.record("a", 100, 10);
        counters.record("a", 50, 5);

        LoggerByteCount row = onlyRow(counters, "a");
        assertEquals(150, row.totalBytes());
        assertEquals(15, row.stackTraceBytes());
    }

    @Test
    void snapshot_isEmptyInitially() {
        assertTrue(new TopCounters(10).snapshot().isEmpty());
    }

    @Test
    void snapshot_oneEntryPerDistinctLoggerName() {
        TopCounters counters = new TopCounters(10);

        counters.record("a", 1, 0);
        counters.record("b", 2, 0);
        counters.record("a", 1, 0);

        assertEquals(Set.of("a", "b"), names(counters));
    }

    @Test
    void capacity_evictsTheLeastRecentlyUpdatedLoggerOnceExceeded() {
        TopCounters counters = new TopCounters(2);

        counters.record("first", 1, 0);
        counters.record("second", 1, 0);
        counters.record("first", 1, 0); // touches "first" again -- "second" is now the least recently updated
        counters.record("third", 1, 0); // capacity 2 -- evicts "second", not "first"

        assertEquals(Set.of("first", "third"), names(counters));
    }

    private static Set<String> names(TopCounters counters) {
        return counters.snapshot().stream().map(LoggerByteCount::loggerName).collect(Collectors.toSet());
    }

    private static LoggerByteCount onlyRow(TopCounters counters, String loggerName) {
        List<LoggerByteCount> rows = counters.snapshot();
        return rows.stream().filter(r -> r.loggerName().equals(loggerName)).findFirst().orElseThrow();
    }
}
