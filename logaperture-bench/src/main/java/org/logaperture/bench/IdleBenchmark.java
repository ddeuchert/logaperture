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

import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.RuleService;
import org.logaperture.core.StormService;
import org.logaperture.core.TopService;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;
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
 * logs through its own child logger into the one shared handler.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = "-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
public class IdleBenchmark {

    @State(Scope.Benchmark)
    public static class Install {

        @Param({"baseline", "rule", "rule+storm", "rule+storm+trim", "idle"})
        public String layers;

        /**
         * Decision #12. {@code template}: the same {@code String} every call, what the storm
         * filter sees for parameterized logging. {@code concatenated}: a new {@code String} per
         * call. {@code throwable}: {@code template} plus a 20-frame exception.
         */
        @Param({"template", "concatenated", "throwable"})
        public String message;

        Throwable thrown;
        boolean concatenated;
        DiscardingFileHandler handler;
        LoggingAdapter adapter;

        @Setup(Level.Trial)
        public void install() {
            BenchContext.requireJBossLogManager();
            thrown = message.equals("throwable") ? BenchContext.throwable() : null;
            concatenated = message.equals("concatenated");
            handler = BenchContext.attachHandler();
            if (layers.equals("baseline")) {
                return;
            }
            adapter = JulAdapterFactory.forCurrentContext();
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            RuleService ruleService = new RuleService(adapter, policy, new InMemoryAuditLog(), StateStore.noOp(),
                    "bench", "bench", "bench");
            boolean storm = !layers.equals("rule");
            boolean trim = layers.equals("rule+storm+trim") || layers.equals("idle");
            boolean top = layers.equals("idle");

            // The container's own order (NoneContainer.installContext, AggregateLevelControl):
            // trim inside top on the formatter, storm inside the rule filter on the filter chain.
            if (trim) {
                ruleService.installTrimRendering();
            }
            if (top) {
                new TopService(adapter, policy).startMeasuring();
            }
            if (storm) {
                new StormService(adapter, policy).startDetection();
            }
            ruleService.installPipeline();

            // A scenario that quietly installed nothing would publish the baseline twice.
            requireClass("filter", handler.getFilter(), "JulRuleFilter");
            if (storm) {
                requireClass("inner filter", innerFilter(), "JulStormFilter");
            }
            if (top) {
                requireClass("formatter", handler.getFormatter(), "ByteCountingFormatter");
            } else if (trim) {
                requireClass("formatter", handler.getFormatter(), "JulTrimFormatter");
            }
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (handler.bytesWritten() == 0) {
                throw new IllegalStateException("the handler wrote nothing; the benchmark measured no logging");
            }
            if (layers.equals("idle") && adapter.byteCounts().isEmpty()) {
                throw new IllegalStateException("top's byte counting recorded nothing");
            }
        }

        private Object innerFilter() {
            try {
                var delegate = handler.getFilter().getClass().getDeclaredMethod("delegate");
                delegate.setAccessible(true);
                return delegate.invoke(handler.getFilter());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("can't read the rule filter's delegate", e);
            }
        }

        private static void requireClass(String what, Object actual, String expectedSimpleName) {
            String name = actual == null ? "null" : actual.getClass().getSimpleName();
            if (!name.equals(expectedSimpleName)) {
                throw new IllegalStateException(what + " is " + name + ", expected " + expectedSimpleName);
            }
        }
    }

    @State(Scope.Thread)
    public static class ThreadLogger {

        private static final AtomicInteger NEXT = new AtomicInteger();

        Logger logger;
        int sequence;

        @Setup(Level.Trial)
        public void create(Install install) {
            logger = Logger.getLogger(BenchContext.CATEGORY + ".worker" + NEXT.getAndIncrement());
        }
    }

    @Benchmark
    public void info(Install install, ThreadLogger thread) {
        // Built in every layer, baseline included, so the concatenation itself subtracts out.
        String message = install.concatenated
                ? "Processed order " + (thread.sequence++) + " for customer 7f3a9c21 in 12 ms"
                : BenchContext.MESSAGE;
        thread.logger.log(java.util.logging.Level.INFO, message, install.thrown);
    }
}
