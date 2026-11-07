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

import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.RuleService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import org.openjdk.jmh.infra.BenchmarkParams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * doc/specs/overhead-benchmarks.md, "Revision 2026-11-06: the Log4j 2 gate": what one {@code INFO} call
 * costs on a real Log4j 2 {@code LoggerContext} with LogAperture installed, in each of the version matrix's
 * modes, as a difference against the {@code baseline} scenario of the same mode and message.
 *
 * <p>{@code scenario}: the layers, installed in a container's order ({@code baseline}; {@code gate};
 * {@code gate+storm}; {@code gate+storm-off}, the shipped default since #151; {@code gate+storm+trim-wrap};
 * {@code idle}, storm disabled plus the trim wrapper: everything a container installs, without {@code top},
 * which Log4j 2 does not count, A6), and the four rule scenarios of the 1.0 suite on top of {@code idle}
 * ({@code drop-miss}, {@code drop-hit}, {@code trim}, {@code trim-typed}).
 *
 * <p>{@code message}: Decision #12's three shapes. {@code template} is a parameterized message with three
 * arguments, the way an application using {@code {}} writes it, so a rule that reads the message formats it
 * (Decision #32); {@code concatenated} builds a new {@code String} per call; {@code throwable} is
 * {@code template} plus a 20-frame exception.
 *
 * <p>{@code disabled} is a call the level rejects. The two asynchronous modes are reported, not budgeted
 * (Decision #27): a hot loop into a queue measures the queue.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class Log4jCallBenchmark {

    static final int RULES = 20;
    static final int TRIM_FRAMES = 5;
    static final String CATEGORY = "org.acme.orders";

    @State(Scope.Benchmark)
    public static class Install {

        // The @Param defaults below are the CI smoke set (Decision #4: `bench-smoke` runs every benchmark once, with
        // no overrides): every mode and the scenarios that carry a trial-end check, with a throwable. `Suite` passes
        // the full sets for the published run, so adding a value here never changes what is published.
        @Param({"sync", "async-appender", "async-loggers"})
        public String mode;

        /** Full set: baseline, gate, gate+storm, gate+storm-off, gate+storm+trim-wrap, idle, drop-miss, drop-hit, trim, trim-typed. */
        @Param({"baseline", "idle", "drop-hit", "trim"})
        public String scenario;

        /** Full set: template, concatenated, throwable. */
        @Param({"throwable"})
        public String message;

        /**
         * "Concurrency", as {@code IdleBenchmark}'s: {@code shared}, every thread on its own child logger but
         * through one appender; {@code per-thread}, each thread on its own appender, the #24 shape.
         */
        @Param({"shared", "per-thread"})
        public String handlers;

        Throwable thrown;
        boolean concatenated;
        LoggerContext context;
        Log4jBench.Sink sink;
        Log4jPipeline pipeline;
        final List<Logger> workers = new ArrayList<>();
        final List<Log4jBench.Sink> sinks = new ArrayList<>();
        final AtomicInteger nextWorker = new AtomicInteger();
        String matchingRule;
        // Boxed once: the call under test must not pay for the benchmark's own autoboxing.
        final Object order = 48213;
        final Object customer = "7f3a9c21";
        final Object millis = 12;

        @Setup(Level.Trial)
        public void install(BenchmarkParams params) {
            thrown = message.equals("throwable") ? Log4jBench.throwable() : null;
            concatenated = message.equals("concatenated");
            sink = new Log4jBench.Sink();
            context = Log4jBench.context("call-bench-" + System.nanoTime(), mode, sink);
            int threads = params.getThreads();
            if (handlers.equals("per-thread")) {
                for (int i = 0; i < threads; i++) {
                    Log4jBench.Sink own = new Log4jBench.Sink();
                    sinks.add(own);
                    workers.add(Log4jBench.workerWithOwnAppender(context, mode, CATEGORY, i, own));
                }
            } else {
                sinks.add(sink);
                for (int i = 0; i < threads; i++) {
                    workers.add(context.getLogger(CATEGORY + ".worker" + i));
                }
            }
            if (!scenario.equals("baseline")) {
                pipeline = switch (scenario) {
                    case "gate" -> Log4jPipeline.install(context, Log4jPipeline.Storm.NONE, false);
                    case "gate+storm" -> Log4jPipeline.install(context, Log4jPipeline.Storm.ENABLED, false);
                    case "gate+storm-off" -> Log4jPipeline.install(context, Log4jPipeline.Storm.DISABLED, false);
                    case "gate+storm+trim-wrap" -> Log4jPipeline.install(context, Log4jPipeline.Storm.ENABLED, true);
                    default -> Log4jPipeline.installIdle(context);
                };
                pipeline.requireInstalled();
                matchingRule = attachRules(pipeline.rules, scenario);
                if (scenario.equals("drop-hit")) {
                    // The rule lets its first match through in full (drop-rule.md): spend that sample here, so
                    // every measured call is a deny and even a one-call smoke iteration suppresses something (#183).
                    call(this, workers.get(0), 0);
                }
            }
        }

        /** Whether the benchmark using this state logs a call the level lets through. */
        boolean writes() {
            return true;
        }

        @TearDown(Level.Trial)
        public void verify() {
            context.stop(); // drains an asynchronous queue, so the sink has everything that was written
            if (!writes()) {
                return; // a benchmark that logs nothing the level lets through has nothing to check below
            }
            if (!scenario.equals("drop-hit")) {
                for (int i = 0; i < sinks.size(); i++) {
                    if (sinks.get(i).bytes.get() == 0) {
                        throw new IllegalStateException(scenario + " in " + mode + " wrote nothing to appender " + i
                                + ": it measured no call");
                    }
                }
            }
            if (scenario.equals("drop-hit")) {
                long suppressed = pipeline.rules.takeDropCounts().stream()
                        .filter(count -> count.ruleId().equals(matchingRule))
                        .mapToLong(RuleService.DropCount::suppressed).sum();
                if (suppressed == 0) {
                    throw new IllegalStateException("the drop-hit rule suppressed nothing (hits "
                            + pipeline.rules.hitCount(matchingRule) + ")");
                }
            }
            if (scenario.equals("trim") && thrown != null && pipeline.rules.hitCount(matchingRule) == 0) {
                throw new IllegalStateException("the trim rule never matched, so the trim wrapper had nothing to do");
            }
        }
    }

    /** The 20 rules of the 1.0 suite's scenarios, on the logger's category; returns the matching rule's id. */
    static String attachRules(RuleService rules, String scenario) {
        RuleAttachOptions options = RuleAttachOptions.defaults();
        switch (scenario) {
            case "drop-miss" -> {
                for (int i = 0; i < RULES; i++) {
                    rules.addRuleDrop("org.acme.billing.Stage" + i, CompiledMatchers.matchAll(), options,
                            SampleFullPolicy.defaults());
                }
                return null;
            }
            case "trim-typed" -> {
                for (int i = 0; i < RULES - 1; i++) {
                    CompiledMatchers miss = new CompiledMatchers(null, null, false,
                            "org.acme.orders.OrderRejectedException" + i, null, false);
                    rules.addRuleTrim(CATEGORY, miss, options, TRIM_FRAMES, false);
                }
                CompiledMatchers hit = new CompiledMatchers(null, null, false, IllegalStateException.class.getName(),
                        null, false);
                return rules.addRuleTrim(CATEGORY, hit, options, TRIM_FRAMES, false).rule().id();
            }
            case "drop-hit", "trim" -> {
                for (int i = 0; i < RULES - 1; i++) {
                    CompiledMatchers miss = new CompiledMatchers(null, "connection pool exhausted (" + i + ")", false,
                            null, null, false);
                    rules.addRuleDrop(CATEGORY, miss, options, SampleFullPolicy.defaults());
                }
                if (scenario.equals("drop-hit")) {
                    CompiledMatchers hit = new CompiledMatchers(null, "Processed order", false, null, null, false);
                    return rules.addRuleDrop(CATEGORY, hit, options, SampleFullPolicy.defaults()).rule().id();
                }
                return rules.addRuleTrim(CATEGORY, CompiledMatchers.matchAll(), options, TRIM_FRAMES, false)
                        .rule().id();
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code disabled} logs nothing by design, so it must not expect output at the end. */
    @State(Scope.Benchmark)
    public static class DisabledInstall extends Install {
        @Override
        boolean writes() {
            return false;
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {
        int sequence;
        Logger logger;

        @Setup(Level.Trial)
        public void pick(Install install) {
            logger = install.workers.get(install.nextWorker.getAndIncrement() % install.workers.size());
        }
    }

    static void call(Install in, Logger logger, int sequence) {
        if (in.concatenated) {
            logger.info("Processed order " + sequence + " for customer 7f3a9c21 in 12 ms");
        } else if (in.thrown != null) {
            logger.info(Log4jBench.PATTERN, in.order, in.customer, in.millis, in.thrown);
        } else {
            logger.info(Log4jBench.PATTERN, in.order, in.customer, in.millis);
        }
    }

    @Benchmark
    public void info(Install install, ThreadState thread) {
        call(install, thread.logger, thread.sequence++);
    }

    /** Decision #28: a call the level rejects. */
    @Benchmark
    public void disabled(DisabledInstall install) {
        install.workers.get(0).debug(Log4jBench.PATTERN, install.order, install.customer, install.millis);
    }
}
