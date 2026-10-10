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
 * doc/specs/overhead-benchmarks.md, the {@code idle} scenario: what one
 * {@code INFO} log call costs with LogAperture installed and nothing
 * configured.
 *
 * <p>{@code layers} adds the four pieces context install puts on the hot path
 * one at a time, in the order a container installs them, so each piece's
 * share is the difference between two adjacent rows of the same run:
 * <ul>
 *   <li>{@code baseline}: none of LogAperture</li>
 *   <li>{@code rule}: the gate {@code Filter} ({@code drop}/{@code trim} evaluation)</li>
 *   <li>{@code rule+storm}: plus the storm-detection {@code Filter}</li>
 *   <li>{@code rule+storm+trim}: plus the trim {@code Formatter} wrap</li>
 *   <li>{@code idle}: plus {@code top}'s byte-counting {@code Formatter} wrap — everything a
 *       container installs at context install</li>
 * </ul>
 *
 * <p>Threads: run with {@code -t 1}, {@code -t 4}, {@code -t 8}. Every thread
 * logs through its own child logger, into one shared handler or, with
 * {@code handlers=per-thread}, into a handler of its own.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = "-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
public class IdleBenchmark {

    @State(Scope.Benchmark)
    public static class Install {

        /**
         * Each layer added in the container's order. Storm detection is enabled in the layer chain
         * up to {@code idle+storm}; {@code rule+storm-off} and {@code idle} have it installed but
         * disabled, as shipped since #151 (doc/specs/overhead-benchmarks.md, storm-detection-toggle.md).
         */
        @Param({"baseline", "rule", "rule+storm", "rule+storm-off", "rule+storm+trim", "idle+storm", "idle"})
        public String layers;

        /**
         * Decision #12. {@code template}: the same {@code String} every call, what the storm
         * filter sees for parameterized logging. {@code concatenated}: a new {@code String} per
         * call. {@code throwable}: {@code template} plus a 20-frame exception.
         */
        @Param({"template", "concatenated", "throwable"})
        public String message;

        /**
         * "Concurrency". {@code shared}: every thread through one handler. {@code per-thread}:
         * each thread on its own handler, the #24 shape.
         */
        @Param({"shared", "per-thread"})
        public String handlers;

        Throwable thrown;
        boolean concatenated;
        BenchContext.Tree tree;
        Pipeline pipeline;
        final AtomicInteger nextWorker = new AtomicInteger();

        @Setup(Level.Trial)
        public void install(BenchmarkParams params) {
            BenchContext.requireJBossLogManager();
            thrown = message.equals("throwable") ? BenchContext.throwable() : null;
            concatenated = message.equals("concatenated");
            tree = BenchContext.attachHandlers(params.getThreads(), handlers.equals("per-thread"));
            if (layers.equals("baseline")) {
                return;
            }
            Pipeline.Storm storm = switch (layers) {
                case "rule" -> Pipeline.Storm.NONE;
                case "rule+storm-off", "idle" -> Pipeline.Storm.DISABLED;
                default -> Pipeline.Storm.ENABLED;
            };
            boolean trim = layers.equals("rule+storm+trim") || layers.startsWith("idle");
            boolean top = layers.startsWith("idle");
            pipeline = Pipeline.install(storm, trim, top);
            pipeline.requireInstalledOn(tree.handlers());
        }

        @TearDown(Level.Trial)
        public void verify() {
            tree.requireWritten();
            if (pipeline != null) {
                pipeline.requireCounted();
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
