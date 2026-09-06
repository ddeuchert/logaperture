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

import org.logaperture.api.LoggerByteCount;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The byte-count store {@link ByteCountingFormatter} writes into and {@link
 * JulLoggingAdapter#byteCounts()} reads from — doc/specs/top.md "Bounded
 * state". A fixed-capacity, least-recently-<em>updated</em>-evicted map keyed
 * on logger name: an unbounded map keyed on logger name is exactly the
 * memory-leak shape §16.7 of the top-level spec names outright.
 *
 * <p>{@code synchronized} rather than a {@link java.util.concurrent.ConcurrentHashMap}:
 * every write also has to reorder the entry for LRU purposes, which {@code
 * LinkedHashMap}'s access-order mode gives for free but a concurrent map
 * doesn't support atomically. This runs on the same thread already doing the
 * handler's actual (synchronous, I/O-bound) {@code publish()} — {@code
 * java.util.logging}'s own handlers already serialize on their own instance
 * lock, so a single handler's byte counting adds no contention beyond what
 * it already has. Known limitation (issue #24): since one {@code
 * TopCounters} instance is shared across every persistent handler on a
 * context, this lock does add contention <em>between</em> otherwise
 * independent handlers that JUL itself never serialized against each other.
 */
final class TopCounters {

    /** {@value}. */
    static final String MAX_TRACKED_PROPERTY = "logaperture.top.maxTrackedLoggers";

    private static final int DEFAULT_MAX_TRACKED = 1_000;

    private final Map<String, Counter> byLogger;

    TopCounters() {
        this(maxTracked());
    }

    /** Package-visible so a test can exercise eviction without 1,000 fake loggers. */
    TopCounters(int capacity) {
        this.byLogger = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Counter> eldest) {
                return size() > capacity;
            }
        };
    }

    synchronized void record(String loggerName, long bytes, long stackTraceBytes) {
        byLogger.computeIfAbsent(loggerName, name -> new Counter()).add(bytes, stackTraceBytes);
    }

    synchronized List<LoggerByteCount> snapshot() {
        List<LoggerByteCount> result = new ArrayList<>(byLogger.size());
        for (Map.Entry<String, Counter> entry : byLogger.entrySet()) {
            Counter counter = entry.getValue();
            result.add(new LoggerByteCount(entry.getKey(), counter.totalBytes, counter.stackTraceBytes));
        }
        return result;
    }

    private static int maxTracked() {
        String raw = System.getProperty(MAX_TRACKED_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_TRACKED;
        }
        try {
            int configured = Integer.parseInt(raw.trim());
            return configured > 0 ? configured : DEFAULT_MAX_TRACKED;
        } catch (NumberFormatException e) {
            System.err.println("[logaperture] ignoring non-numeric " + MAX_TRACKED_PROPERTY + "='" + raw
                    + "', using " + DEFAULT_MAX_TRACKED);
            return DEFAULT_MAX_TRACKED;
        }
    }

    private static final class Counter {
        private long totalBytes;
        private long stackTraceBytes;

        void add(long bytes, long stackTraceBytes) {
            this.totalBytes += bytes;
            this.stackTraceBytes += stackTraceBytes;
        }
    }
}
