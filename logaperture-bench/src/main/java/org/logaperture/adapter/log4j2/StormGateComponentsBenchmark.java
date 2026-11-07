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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.logaperture.core.StormDetector;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * {@code storm-observe} for Log4j 2: the whole {@link GateFilter} entry point with storm detection enabled
 * and no rules, so the call reaches {@code StormDetector.observe} through Log4j's own seam. The two message
 * shapes of Decision #12: a parameterized pattern the detector has seen ({@code template}, the cache-hit
 * path), and a new {@code String} per call ({@code concatenated}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class StormGateComponentsBenchmark {

    private LoggerContext context;
    private Logger logger;
    private GateFilter filter;
    private final Object order = 48213;
    private final Object customer = "7f3a9c21";
    private final Object millis = 12;
    private int sequence;

    @Setup
    public void setUp() {
        context = Log4jBench.startedContext("storm-bench-" + System.nanoTime());
        logger = Log4jBench.logger(context);
        StormDetector detector = new StormDetector();
        if (!detector.isActive()) {
            throw new IllegalStateException("the detector must be enabled for this benchmark");
        }
        filter = new GateFilter();
        filter.setStorm(detector);
    }

    @TearDown
    public void tearDown() {
        context.stop();
    }

    @Benchmark
    public Result stormTemplate() {
        return filter.filter(logger, Level.INFO, null, Log4jBench.PATTERN, order, customer, millis);
    }

    @Benchmark
    public Result stormConcatenated() {
        String raw = "Processed order " + (sequence++) + " for customer 7f3a9c21 in 12 ms";
        return filter.filter(logger, Level.INFO, null, raw);
    }
}
