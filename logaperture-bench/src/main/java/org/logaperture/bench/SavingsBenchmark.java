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

import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.FileHandler;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * doc/specs/overhead-benchmarks.md Decision #22, matched-record savings: one
 * {@code INFO} call through a <b>real</b> JBoss {@code FileHandler} (autoflush
 * on, as WildFly configures its file handlers), with no LogAperture at all
 * ({@code baseline}) or with the whole agent and {@code RulesBenchmark}'s 20
 * rules, the last of which matches ({@code drop-hit}, {@code trim}). The
 * difference can come out negative: that is the agent saving more on a
 * matched record than its gate costs. Reported, not budgeted.
 *
 * <p>The only scenarios that touch the disk (Decision #3 keeps it out of
 * every other one). The log file goes in the folder named by the {@value
 * #DIR_PROPERTY} system property, which {@code Suite} points at the results
 * folder so it lands on a real disk rather than a tmpfs {@code /tmp}, and is
 * truncated at the start of every iteration so a run's disk use stays bounded.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = "-Djava.util.logging.manager=org.jboss.logmanager.LogManager")
public class SavingsBenchmark {

    static final String DIR_PROPERTY = "logaperture.bench.dir";

    /** "Deep stack": what a framework exception through a servlet, EJB and JPA stack commonly runs to. */
    static final int DEEP_STACK_FRAMES = 100;

    @State(Scope.Benchmark)
    public static class Install {

        @Param({"baseline", "drop-hit", "trim"})
        public String scenario;

        /** {@code template}, or {@code template} plus a {@value #DEEP_STACK_FRAMES}-frame exception. */
        @Param({"template", "throwable"})
        public String message;

        Throwable thrown;
        Logger logger;
        FileHandler handler;
        Path file;
        Pipeline pipeline;
        String matchingRule;

        @Setup(Level.Trial)
        public void install() throws IOException {
            BenchContext.requireJBossLogManager();
            thrown = message.equals("throwable") ? BenchContext.throwable(DEEP_STACK_FRAMES) : null;
            Path dir = Path.of(System.getProperty(DIR_PROPERTY, "")).toAbsolutePath();
            Files.createDirectories(dir);
            file = Files.createTempFile(dir, "savings-", ".log");
            handler = new FileHandler(new PatternFormatter(BenchContext.PATTERN), file.toFile(), false);
            handler.setAutoFlush(true);
            handler.setLevel(java.util.logging.Level.ALL);

            Logger category = Logger.getLogger(BenchContext.CATEGORY);
            BenchContext.clearHandlers(category);
            category.setLevel(java.util.logging.Level.INFO);
            category.setUseParentHandlers(false);
            category.addHandler(handler);
            logger = Logger.getLogger(BenchContext.CATEGORY + ".worker0");
            BenchContext.clearHandlers(logger);
            logger.setUseParentHandlers(true);

            if (!scenario.equals("baseline")) {
                pipeline = Pipeline.installIdle();
                pipeline.requireInstalledOn(List.of(handler));
                matchingRule = RulesBenchmark.attachRules(pipeline.rules, scenario);
            }
        }

        /** Reopening without append truncates: each iteration starts from an empty file. */
        @Setup(Level.Iteration)
        public void truncate() throws IOException {
            handler.setFile(file.toFile());
        }

        @TearDown(Level.Trial)
        public void verify() throws IOException {
            try {
                if (scenario.equals("drop-hit")) {
                    long suppressed = pipeline.rules.takeDropCounts().stream()
                            .filter(count -> count.ruleId().equals(matchingRule))
                            .mapToLong(RuleService.DropCount::suppressed).sum();
                    if (suppressed == 0) {
                        throw new IllegalStateException("the drop-hit rule suppressed nothing");
                    }
                    return;
                }
                if (Files.size(file) == 0) {
                    throw new IllegalStateException("the file handler wrote nothing; the benchmark measured no logging");
                }
                if (thrown != null) {
                    int expected = scenario.equals("trim") ? RulesBenchmark.TRIM_FRAMES : DEEP_STACK_FRAMES;
                    int frames = longestTrace();
                    if (frames != expected) {
                        throw new IllegalStateException("the file's stack traces have " + frames + " frames, expected "
                                + expected);
                    }
                }
            } finally {
                handler.close();
                Files.deleteIfExists(file);
            }
        }

        /** The most consecutive {@code at} lines among the file's first records. */
        private int longestTrace() throws IOException {
            int longest = 0;
            int run = 0;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                for (int read = 0; read < 2_000 && (line = reader.readLine()) != null; read++) {
                    run = line.startsWith("\tat ") ? run + 1 : 0;
                    longest = Math.max(longest, run);
                }
            }
            return longest;
        }
    }

    @Benchmark
    public void info(Install install) {
        install.logger.log(java.util.logging.Level.INFO, BenchContext.MESSAGE, install.thrown);
    }
}
