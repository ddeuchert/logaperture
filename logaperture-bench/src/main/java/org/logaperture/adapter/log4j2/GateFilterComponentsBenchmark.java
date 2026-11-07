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
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.Filter.Result;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * doc/specs/overhead-benchmarks.md, "Revision 2026-11-06: the Log4j 2 gate", {@code gate-empty} and
 * {@code gate-n} for Log4j 2 (the disabled call is {@link GateFilterDisabledComponentsBenchmark}): the whole
 * {@link GateFilter} entry point Log4j calls, with {@code rules} {@code drop} rules effective on the logger (all on its parent,
 * none matching, each reading the message), as {@code GateCurveBenchmark} does for the JUL gate.
 *
 * <p>{@code rules = 0} is {@code gate-empty}: the gate is installed and knows no rule for the logger, the
 * shipped idle state. {@code gate} is checked as {@code gate − gate-empty} against Decision #21's curve, for rules
 * that read nothing or a plain message; what a parameterized message adds is its own row (Decision #32).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class GateFilterComponentsBenchmark {

    /** The CI smoke set (0 and 20); {@code Suite} passes 0, 1, 5, 20 and 100 for the published run. */
    @Param({"0", "20"})
    public int rules;

    /**
     * What the rules read: {@code pattern} (message-contains rules on a parameterized message, which
     * formats it once per call), {@code plain} (the same rules on a message with no parameters, nothing to
     * format) or {@code level-only} (rules with no message matcher, so the message is never read).
     */
    @Param({"pattern", "plain", "level-only"})
    public String reads;

    private LoggerContext context;
    private Logger logger;
    private GateFilter filter;
    // Boxed once: the call under test must not pay for the benchmark's own autoboxing.
    private final Object order = 48213;
    private final Object customer = "7f3a9c21";
    private final Object millis = 12;

    @Setup
    public void setUp() {
        context = Log4jBench.startedContext("gate-bench-" + System.nanoTime());
        logger = Log4jBench.logger(context);
        RuleService service = IdleComponentsBenchmark.newRuleService();
        for (int i = 0; i < rules; i++) {
            CompiledMatchers miss = "level-only".equals(reads)
                    ? new CompiledMatchers(org.logaperture.api.Level.TRACE, null, false, null, null, false)
                    : new CompiledMatchers(null, "connection pool exhausted (" + i + ")", false, null, null, false);
            service.addRuleDrop("org.acme.orders", miss, RuleAttachOptions.defaults(), SampleFullPolicy.defaults());
        }
        int effective = service.effectiveRules(IdleComponentsBenchmark.LOGGER).size();
        if (effective != rules) {
            throw new IllegalStateException("expected " + rules + " effective rules, got " + effective);
        }
        filter = new GateFilter();
        filter.setGate(service.gate());
        if (gate() != Result.NEUTRAL) {
            throw new IllegalStateException("a non-matching rule set must let the call through");
        }
    }

    @TearDown
    public void tearDown() {
        context.stop();
    }

    /** {@code gate-empty} (rules 0) and {@code gate-n}: a call the logger writes. */
    @Benchmark
    public Result gate() {
        return "plain".equals(reads)
                ? filter.filter(logger, Level.INFO, null, IdleComponentsBenchmark.MESSAGE)
                : filter.filter(logger, Level.INFO, null, Log4jBench.PATTERN, order, customer, millis);
    }
}
