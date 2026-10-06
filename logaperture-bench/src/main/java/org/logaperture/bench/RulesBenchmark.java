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
package org.logaperture.bench;

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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * doc/specs/overhead-benchmarks.md, the rule scenarios: one {@code INFO} log
 * call with everything context install puts on the hot path (the {@code idle}
 * layers) plus {@value #RULES} rules. Subtracted from {@code IdleBenchmark}'s
 * {@code baseline} of the same message and thread count.
 * <ul>
 *   <li>{@code drop-miss}: {@value #RULES} {@code drop} rules on other loggers; none can reach
 *       this one, so the gate is skipped (§10 "near-zero for loggers no rule can match").</li>
 *   <li>{@code drop-hit}: {@value #RULES} rules on this logger's category, evaluated in order;
 *       the last, a {@code drop}, matches and the record is denied (§10 "&lt; 200 ns ... ~20
 *       rules"). A denied record is never formatted, so this can come in under
 *       {@code baseline}.</li>
 *   <li>{@code trim}: the same, but the last rule is a 5-frame {@code trim}, which only the
 *       {@code throwable} message gives anything to cut (§17.1 "a {@code trim} rule").</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = "-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
public class RulesBenchmark {

    static final int RULES = 20;
    static final int TRIM_FRAMES = 5;

    @State(Scope.Benchmark)
    public static class Install {

        @Param({"drop-miss", "drop-hit", "trim"})
        public String scenario;

        /** Decision #12, as in {@code IdleBenchmark}. */
        @Param({"template", "concatenated", "throwable"})
        public String message;

        Throwable thrown;
        boolean concatenated;
        BenchContext.Tree tree;
        Pipeline pipeline;
        /** The rule that matches, last of the {@value #RULES}; {@code null} for {@code drop-miss}. */
        String matchingRule;
        final AtomicInteger nextWorker = new AtomicInteger();

        @Setup(Level.Trial)
        public void install(BenchmarkParams params) {
            BenchContext.requireJBossLogManager();
            thrown = message.equals("throwable") ? BenchContext.throwable() : null;
            concatenated = message.equals("concatenated");
            tree = BenchContext.attachHandlers(params.getThreads(), false);
            pipeline = Pipeline.installIdle();
            pipeline.requireInstalledOn(tree.handlers());
            attachRules(pipeline.rules);
        }

        private void attachRules(RuleService rules) {
            RuleAttachOptions options = RuleAttachOptions.defaults();
            if (scenario.equals("drop-miss")) {
                for (int i = 0; i < RULES; i++) {
                    rules.addRuleDrop("org.acme.billing.Stage" + i, CompiledMatchers.matchAll(), options,
                            SampleFullPolicy.defaults());
                }
                return;
            }
            // Message matchers that never match: each one makes the gate read the formatted
            // message, the most a non-matching rule can cost.
            for (int i = 0; i < RULES - 1; i++) {
                CompiledMatchers miss = new CompiledMatchers(null, "connection pool exhausted (" + i + ")", false,
                        null, null, false);
                rules.addRuleDrop(BenchContext.CATEGORY, miss, options, SampleFullPolicy.defaults());
            }
            if (scenario.equals("drop-hit")) {
                CompiledMatchers hit = new CompiledMatchers(null, "Processed order", false, null, null, false);
                matchingRule = rules.addRuleDrop(BenchContext.CATEGORY, hit, options, SampleFullPolicy.defaults())
                        .rule().id();
            } else {
                matchingRule = rules.addRuleTrim(BenchContext.CATEGORY, CompiledMatchers.matchAll(), options,
                        TRIM_FRAMES, false).rule().id();
            }
        }

        @TearDown(Level.Trial)
        public void verify() {
            // drop-hit's handler sees only the sampled-through first record; nothing else to check.
            if (!scenario.equals("drop-hit")) {
                tree.requireWritten();
                pipeline.requireCounted();
            }
            // A trim rule only counts a hit on a record with a throwable to cut.
            boolean expectHits = matchingRule != null && !(scenario.equals("trim") && thrown == null);
            if (expectHits && pipeline.rules.hitCount(matchingRule) == 0) {
                throw new IllegalStateException("the " + scenario + " rule never matched");
            }
        }
    }

    @State(Scope.Thread)
    public static class ThreadLogger {

        Logger logger;
        int sequence;

        @Setup(Level.Trial)
        public void create(Install install) {
            logger = install.tree.workers().get(install.nextWorker.getAndIncrement());
        }
    }

    @Benchmark
    public void info(Install install, ThreadLogger thread) {
        String message = BenchContext.message(install.concatenated, thread.sequence++);
        thread.logger.log(java.util.logging.Level.INFO, message, install.thrown);
    }
}
