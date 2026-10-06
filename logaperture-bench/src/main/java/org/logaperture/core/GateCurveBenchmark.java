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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Level;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;
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
import org.openjdk.jmh.annotations.Warmup;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * doc/specs/overhead-benchmarks.md Decision #22, {@code gate-n}: one gate
 * verdict with {@code n} {@code drop} rules effective on the logger (all on
 * its parent), none matching, each with a message-contains matcher, so every
 * rule reads the message and moves on: the most an allowed record can cost
 * the gate. Run at several {@code n} and checked against Decision #21's
 * curve, {@code 50 ns + 35 ns × log2(n + 1)}, over {@code gate-empty}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class GateCurveBenchmark {

    @Param({"1", "5", "20", "100"})
    public int rules;

    private RuleGate gate;
    private final Supplier<String> message = () -> IdleComponentsBenchmark.MESSAGE;

    @Setup
    public void setUp() {
        RuleService service = IdleComponentsBenchmark.newRuleService();
        for (int i = 0; i < rules; i++) {
            CompiledMatchers miss = new CompiledMatchers(null, "connection pool exhausted (" + i + ")", false, null,
                    null, false);
            service.addRuleDrop("org.acme.orders", miss, RuleAttachOptions.defaults(), SampleFullPolicy.defaults());
        }
        int effective = service.effectiveRules(IdleComponentsBenchmark.LOGGER).size();
        if (effective != rules) {
            throw new IllegalStateException("expected " + rules + " effective rules, got " + effective);
        }
        gate = service.gate();
    }

    /** A fresh identity per call, as every real record is one (see {@code gate-empty}). */
    @Benchmark
    public GateVerdict gate() {
        RuleCandidateEvent event = new RuleCandidateEvent(IdleComponentsBenchmark.LOGGER, Level.INFO, null, message,
                Instant.EPOCH);
        return gate.evaluate(new Object(), event);
    }
}
