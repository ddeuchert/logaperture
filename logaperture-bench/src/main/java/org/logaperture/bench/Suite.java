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

import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * doc/specs/overhead-benchmarks.md Decision #19: the published set of runs,
 * in one place, so {@code run-bench.sh} and {@code run-bench.ps1} run exactly
 * the same thing. Each run writes {@code <name>.json} into the results folder;
 * {@link Report} reads them all.
 *
 * <p>Usage: {@code java -cp benchmarks.jar org.logaperture.bench.Suite <results-folder> [--quick]
 * [--async-profiler <libasyncProfiler path>]}
 */
public final class Suite {

    private static final String IDLE = "org\\.logaperture\\.bench\\.IdleBenchmark\\.";
    private static final String RULES = "org\\.logaperture\\.bench\\.RulesBenchmark\\.";
    private static final String COMPONENTS =
            "org\\.logaperture\\.(core\\.IdleComponents|adapter\\.jul\\.TopComponents)Benchmark\\.";

    /**
     * One JMH invocation: a name for its files, and what it adds to the shared options. A run
     * that isn't {@code reported} (a profiled one, whose scores the profiler disturbs) writes its
     * JSON into its own subfolder, out of {@link Report}'s way.
     */
    record Run(String name, boolean reported, UnaryOperator<ChainedOptionsBuilder> options) {

        Run(String name, UnaryOperator<ChainedOptionsBuilder> options) {
            this(name, true, options);
        }
    }

    private Suite() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Suite <results-folder> [--quick] [--async-profiler <library path>]");
            System.exit(1);
        }
        Path out = Path.of(args[0]);
        boolean quick = false;
        String asyncProfiler = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--async-profiler" -> asyncProfiler = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Files.createDirectories(out);
        List<Run> runs = runs(Runtime.getRuntime().availableProcessors(), asyncProfiler, out);
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            System.out.printf("%n== Run %d of %d: %s (started %s)%n", i + 1, runs.size(), run.name(),
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            execute(run, out, quick);
        }
    }

    /**
     * The published set. Thread counts the machine can't run in parallel are skipped, not
     * oversubscribed: a thread waiting for a core measures the scheduler, not LogAperture.
     */
    static List<Run> runs(int hardwareThreads, String asyncProfiler, Path out) {
        List<Run> runs = new ArrayList<>();
        runs.add(new Run("components", o -> o.include(COMPONENTS)));
        // Every layer and message, one thread, one handler: the overhead table and the budgets.
        runs.add(new Run("idle-t1", o -> o.include(IDLE).threads(1).param("handlers", "shared")));
        // The #24 rule's single-thread reference.
        runs.add(new Run("idle-per-thread-t1", o -> o.include(IDLE).threads(1)
                .param("handlers", "per-thread").param("layers", "baseline", "idle").param("message", "template")));
        runs.add(new Run("rules-t1", o -> o.include(RULES).threads(1)));
        for (int threads : new int[] {4, 8}) {
            if (threads > hardwareThreads) {
                System.out.printf("Skipping the %d-thread runs: this machine has %d hardware threads.%n",
                        threads, hardwareThreads);
                continue;
            }
            runs.add(new Run("idle-t" + threads, o -> o.include(IDLE).threads(threads)
                    .param("layers", "baseline", "rule+storm", "idle")
                    .param("message", "template", "concatenated")));
        }
        if (asyncProfiler != null) {
            String flamegraphs = out.resolve("flamegraphs").toAbsolutePath().toString();
            String profilerOptions = "libPath=" + asyncProfiler + ";output=flamegraph;dir=" + flamegraphs;
            runs.add(new Run("flamegraphs", false, o -> o.include(IDLE).threads(1).param("handlers", "shared")
                    .param("layers", "idle").param("message", "template", "concatenated", "throwable")
                    .addProfiler("async", profilerOptions)));
        }
        return runs;
    }

    private static void execute(Run run, Path out, boolean quick) throws RunnerException, IOException {
        Path folder = run.reported() ? out : Files.createDirectories(out.resolve(run.name()));
        ChainedOptionsBuilder options = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON)
                .result(folder.resolve(run.name() + ".json").toString());
        if (quick) {
            // Enough iterations for an error bar, too few for the numbers to mean much.
            options.forks(1)
                    .warmupIterations(3).warmupTime(TimeValue.seconds(1))
                    .measurementIterations(5).measurementTime(TimeValue.seconds(1));
        }
        new Runner(run.options().apply(options).build()).run();
    }
}
