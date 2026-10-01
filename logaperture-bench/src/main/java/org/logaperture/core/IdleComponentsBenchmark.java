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

import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * doc/specs/overhead-benchmarks.md "Component benchmarks": the core pieces of
 * the {@code idle} path, each with nothing around it, to cross-check the
 * layer differences in {@code IdleBenchmark}.
 *
 * <p>In {@code org.logaperture.core} (a split package, bench jar only) so it
 * can reach {@link StormDetector#normalize}, which is package-private.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class IdleComponentsBenchmark {

    private static final String LOGGER = "org.acme.orders.worker0";
    private static final String MESSAGE = "Processed order 48213 for customer 7f3a9c21 in 12 ms";

    private RuleGate gate;
    private StormDetector detector;
    private final Supplier<String> message = () -> MESSAGE;
    private int sequence;

    @Setup
    public void setUp() {
        // No logger tree is touched: an adapter with no loggers is enough for an empty rule set.
        LoggingAdapter adapter = new LoggingAdapter() {
            @Override
            public List<String> knownLoggerNames() {
                return List.of();
            }

            @Override
            public Optional<Level> configuredLevel(String loggerName) {
                return Optional.empty();
            }

            @Override
            public Level effectiveLevel(String loggerName) {
                return Level.INFO;
            }

            @Override
            public void applyLevel(String loggerName, Level level) {
            }
        };
        gate = new RuleService(adapter, CapabilityPolicy.allowAll(), new InMemoryAuditLog(), StateStore.noOp(),
                "bench", "bench", "bench").gate();
        detector = new StormDetector();
    }

    /**
     * {@code gate-empty}: one verdict for a context with no rules. A fresh
     * identity per call, as every real record is one, so the decision cache
     * does the insert it does in production.
     */
    @Benchmark
    public GateVerdict gateEmpty() {
        RuleCandidateEvent event = new RuleCandidateEvent(LOGGER, Level.INFO, null, message, Instant.EPOCH);
        return gate.evaluate(new Object(), event);
    }

    /** {@code storm-observe}: one observation of a message already being tracked. */
    @Benchmark
    public void stormObserve() {
        detector.observe(new StormObservation(LOGGER, Level.INFO, null, MESSAGE, false, null, message,
                Instant.EPOCH));
    }

    /** {@code storm-observe}, {@code concatenated}: a new message {@code String} every call (Decision #12). */
    @Benchmark
    public void stormObserveConcatenated() {
        String raw = "Processed order " + (sequence++) + " for customer 7f3a9c21 in 12 ms";
        detector.observe(new StormObservation(LOGGER, Level.INFO, null, raw, false, null, message,
                Instant.EPOCH));
    }

    /** {@code storm-normalize}: message normalization alone, uncached. */
    @Benchmark
    public String stormNormalize() {
        return StormDetector.normalize(MESSAGE);
    }
}
