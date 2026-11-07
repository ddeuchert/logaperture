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
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.IdleComponentsBenchmark;
import org.logaperture.core.RuleService;
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
 * The disabled call (Decision #28): {@link GateFilter}'s entry point for a call the logger's level rejects, which
 * Log4j runs through the filter anyway (spike G2). Held to 10 ns. It is its own class, with no parameters, because
 * the filter leaves at the level check before it looks at a rule: with 20 non-matching rules installed (the case that
 * could make it slower) it costs what it does with none, so a rules x reads cross-product would only repeat it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class GateFilterDisabledComponentsBenchmark {

    private static final int RULES = 20;

    private LoggerContext context;
    private Logger logger;
    private GateFilter filter;
    private final Object order = 48213;
    private final Object customer = "7f3a9c21";
    private final Object millis = 12;

    @Setup
    public void setUp() {
        context = Log4jBench.startedContext("disabled-bench-" + System.nanoTime());
        logger = Log4jBench.logger(context);
        RuleService service = IdleComponentsBenchmark.newRuleService();
        for (int i = 0; i < RULES; i++) {
            CompiledMatchers miss = new CompiledMatchers(null, "connection pool exhausted (" + i + ")", false, null,
                    null, false);
            service.addRuleDrop("org.acme.orders", miss, RuleAttachOptions.defaults(), SampleFullPolicy.defaults());
        }
        filter = new GateFilter();
        filter.setGate(service.gate());
        if (filter.filter(logger, Level.DEBUG, null, Log4jBench.PATTERN, order, customer, millis) != Result.NEUTRAL) {
            throw new IllegalStateException("a call the level rejects must pass the filter untouched");
        }
    }

    @TearDown
    public void tearDown() {
        context.stop();
    }

    @Benchmark
    public Result disabled() {
        return filter.filter(logger, Level.DEBUG, null, Log4jBench.PATTERN, order, customer, millis);
    }
}
