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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Level;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.IdleComponentsBenchmark;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.RuleService;
import org.logaperture.core.TrimDecision;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@code trim-query} for Log4j 2: {@link RuleGate#trimChoice}, the non-counting query {@code TrimAppender}
 * makes for each event it renders, with 20 rules effective on the logger (19 non-matching {@code drop}s that
 * read the message, then a {@code trim} that matches) and an event that carries a throwable. Called directly:
 * in front of an {@code <Async>} appender it runs on the calling thread, with all-async loggers on the
 * consumer thread, and the cost is the same either way.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class TrimQueryComponentsBenchmark {

    private static final int RULES = 20;

    private RuleGate gate;
    private final Supplier<String> message = () -> IdleComponentsBenchmark.MESSAGE;
    private final Throwable thrown = new IllegalStateException("boom");

    @Setup
    public void setUp() {
        RuleService service = IdleComponentsBenchmark.newRuleService();
        for (int i = 0; i < RULES - 1; i++) {
            CompiledMatchers miss = new CompiledMatchers(null, "connection pool exhausted (" + i + ")", false, null,
                    null, false);
            service.addRuleDrop("org.acme.orders", miss, RuleAttachOptions.defaults(), SampleFullPolicy.defaults());
        }
        service.addRuleTrim("org.acme.orders", CompiledMatchers.matchAll(), RuleAttachOptions.defaults(), 5, false);
        int effective = service.effectiveRules(IdleComponentsBenchmark.LOGGER).size();
        if (effective != RULES) {
            throw new IllegalStateException("expected " + RULES + " effective rules, got " + effective);
        }
        gate = service.gate();
        if (query() == null) {
            throw new IllegalStateException("the trim rule must match, or this measures nothing");
        }
    }

    private TrimDecision query() {
        return gate.trimChoice(new RuleCandidateEvent(IdleComponentsBenchmark.LOGGER, Level.ERROR, thrown, message,
                Instant.EPOCH));
    }

    @Benchmark
    public TrimDecision trimQuery() {
        return query();
    }
}
