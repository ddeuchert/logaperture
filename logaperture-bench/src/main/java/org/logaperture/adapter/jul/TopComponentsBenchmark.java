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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * doc/specs/overhead-benchmarks.md "Component benchmarks": the two pieces of
 * {@code top}'s byte counting, each with nothing around it, to cross-check the
 * {@code idle} layer in {@code IdleBenchmark}.
 *
 * <p>In {@code org.logaperture.adapter.jul} (a split package, bench jar only)
 * so it can reach {@link TopCounters} and {@link ByteCountingFormatter}, which
 * are package-private.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class TopComponentsBenchmark {

    private static final String LOGGER = "org.acme.orders.worker0";

    private TopCounters counters;
    private Throwable thrown;
    private String formatted;
    private long formattedBytes;

    @Setup
    public void setUp() {
        counters = new TopCounters();
        // The same shape IdleBenchmark's throwable message carries: 20 frames, no cause.
        IllegalStateException e = new IllegalStateException("Connection refused: db-primary:5432");
        StackTraceElement[] frames = new StackTraceElement[20];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("org.acme.orders.OrderService$Stage" + i, "process",
                    "OrderService.java", 100 + i);
        }
        e.setStackTrace(frames);
        thrown = e;
        LogRecord record = new LogRecord(java.util.logging.Level.SEVERE, "Processed order 48213 for customer 7f3a9c21 in 12 ms");
        record.setLoggerName(LOGGER);
        record.setThrown(e);
        formatted = new SimpleFormatter().format(record);
        formattedBytes = formatted.getBytes(StandardCharsets.UTF_8).length;
        if (ByteCountingFormatter.traceStart(formatted, thrown) < 0) {
            throw new IllegalStateException("trace-share would measure the fallback render, not the lookup");
        }
    }

    /** {@code top-record}: one record's bytes added to its logger's tally (#24's lock, uncontended). */
    @Benchmark
    public void topRecord() {
        counters.record(LOGGER, 80, 0);
    }

    /**
     * {@code trace-share}: finding a record's stack-trace share in what its formatter wrote, as
     * every text formatter's record with a throwable does since #23 (doc/specs/top.md T1-T4).
     */
    @Benchmark
    public long traceShare() {
        return ByteCountingFormatter.stackTraceBytes(formatted, formattedBytes, thrown);
    }

    /**
     * {@code trace-bytes}: the separate stack-trace render; since #23 only the fallback for a
     * formatter that doesn't write the trace as text (top.md T2).
     */
    @Benchmark
    public long traceBytes() {
        return ByteCountingFormatter.traceBytes(thrown);
    }
}
