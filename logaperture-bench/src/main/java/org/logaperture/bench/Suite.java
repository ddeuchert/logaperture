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
 * [--only <run name prefix>] [--async-profiler <libasyncProfiler path>]}
 */
public final class Suite {

    private static final String IDLE = "org\\.logaperture\\.bench\\.IdleBenchmark\\.";
    private static final String RULES = "org\\.logaperture\\.bench\\.RulesBenchmark\\.";
    private static final String SAVINGS = "org\\.logaperture\\.bench\\.SavingsBenchmark\\.";
    private static final String COMPONENTS =
            "org\\.logaperture\\.(core\\.IdleComponents|core\\.GateCurve|adapter\\.jul\\.TopComponents"
                    + "|adapter\\.jul\\.StormFilterComponents)Benchmark\\.";
    private static final String LOG4J2_COMPONENTS = "org\\.logaperture\\.adapter\\.log4j2\\.\\w+ComponentsBenchmark\\.";
    private static final String LOG4J2_CALL = "org\\.logaperture\\.adapter\\.log4j2\\.Log4jCallBenchmark\\.";

    // The Log4j 2 sets (doc/specs/overhead-benchmarks.md, "Revision 2026-11-06: the Log4j 2 gate"). The benchmark classes
    // default to the CI smoke subset, so every published value is named here.
    private static final String FLOOR = "log4j2-floor";
    private static final String[] L4J_MODES = {"sync", "async-appender", "async-loggers"};
    private static final String[] L4J_LAYERS = {"baseline", "gate", "gate+storm", "gate+storm-off",
            "gate+storm+trim-wrap", "idle"};
    private static final String[] L4J_RULES = {"drop-miss", "drop-hit", "trim", "trim-typed"};

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
            System.err.println("usage: Suite <results-folder> [--quick] [--only <run name prefix>] [--async-profiler <library path>]");
            System.exit(1);
        }
        Path out = Path.of(args[0]);
        boolean quick = false;
        String asyncProfiler = null;
        String only = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--only" -> only = args[++i]; // a name prefix, e.g. log4j2: run just those, to check a change
                case "--async-profiler" -> asyncProfiler = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("log4j2.txt"), log4j2Version() + System.lineSeparator());
        List<Run> runs = runs(Runtime.getRuntime().availableProcessors(), asyncProfiler, out);
        if (only == null) {
            // The floor comparison runs on a different Log4j, so on its own jar and in its own results folder.
            runs = runs.stream().filter(run -> !run.name().startsWith(FLOOR)).toList();
        } else {
            String prefix = only;
            runs = runs.stream().filter(run -> run.name().startsWith(prefix)).toList();
            if (runs.isEmpty()) {
                throw new IllegalArgumentException("--only " + only + " matches no run");
            }
        }
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
        // Decision #22: what a matched record saves, through a real file handler. The log file goes
        // under the results folder, a real disk, not a tmpfs /tmp. Options' jvmArgsAppend replaces
        // the annotation's, so the LogManager setting is repeated here.
        String savingsDir = out.resolve("savings-files").toAbsolutePath().toString();
        runs.add(new Run("savings-t1", o -> o.include(SAVINGS).threads(1)
                .jvmArgsAppend("-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
                        "-D" + SavingsBenchmark.DIR_PROPERTY + "=" + savingsDir)));
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
        runs.addAll(log4j2Runs(hardwareThreads));
        if (asyncProfiler != null) {
            String flamegraphs = out.resolve("flamegraphs").toAbsolutePath().toString();
            String profilerOptions = "libPath=" + asyncProfiler + ";output=flamegraph;dir=" + flamegraphs;
            runs.add(new Run("flamegraphs", false, o -> o.include(IDLE).threads(1).param("handlers", "shared")
                    .param("layers", "idle").param("message", "template", "concatenated", "throwable")
                    .addProfiler("async", profilerOptions)));
        }
        return runs;
    }

    /**
     * The Log4j 2 runs. Whole-call budgets are checked in synchronous mode; the two asynchronous modes are reported
     * only (Decision #27), so the thread runs use synchronous mode alone. {@code disabled} is run for the layers that
     * can change it. Rule rows are compared with {@code idle} of the same mode and message, from {@code log4j2-layers}.
     */
    static List<Run> log4j2Runs(int hardwareThreads) {
        List<Run> runs = new ArrayList<>();
        // The gate's pieces called directly: gate-empty and gate-n, storm-observe, trim-query.
        runs.add(new Run("log4j2-components", o -> o.include(LOG4J2_COMPONENTS)
                .param("rules", "0", "1", "5", "20", "100")
                .param("reads", "pattern", "plain", "level-only")));
        // The layers: every mode and message, one thread, one appender.
        runs.add(new Run("log4j2-layers-t1", o -> o.include(LOG4J2_CALL + "info").threads(1)
                .param("mode", L4J_MODES).param("scenario", L4J_LAYERS)
                .param("message", "template", "concatenated", "throwable").param("handlers", "shared")));
        // The rules, on top of idle. `concatenated` is the plain-message variant, the one Decision #32 budgets; `template` and
        // `throwable` are parameterized, so a rule that reads the message formats it, and those rows are reported.
        runs.add(new Run("log4j2-rules-t1", o -> o.include(LOG4J2_CALL + "info").threads(1)
                .param("mode", L4J_MODES).param("scenario", L4J_RULES)
                .param("message", "template", "concatenated", "throwable").param("handlers", "shared")));
        // Decision #28: a call the level rejects, for the layers that can change it.
        runs.add(new Run("log4j2-disabled-t1", o -> o.include(LOG4J2_CALL + "disabled").threads(1)
                .param("mode", L4J_MODES).param("scenario", "baseline", "idle")
                .param("message", "template").param("handlers", "shared")));
        // The #24 rule's single-thread reference.
        runs.add(new Run("log4j2-per-thread-t1", o -> o.include(LOG4J2_CALL + "info").threads(1)
                .param("mode", "sync").param("scenario", "baseline", "idle")
                .param("message", "template", "concatenated").param("handlers", "per-thread")));
        // Decision #26: `idle` and `disabled` on the adapter's floor, 2.20.0. Only meaningful on a jar built with
        // `--log4j2-version 2.20.0`, so `Suite` leaves them out unless `--only log4j2-floor` asks.
        runs.add(new Run(FLOOR + "-layers", o -> o.include(LOG4J2_CALL + "info").threads(1)
                .param("mode", L4J_MODES).param("scenario", "baseline", "idle")
                .param("message", "template", "throwable").param("handlers", "shared")));
        runs.add(new Run(FLOOR + "-disabled", o -> o.include(LOG4J2_CALL + "disabled").threads(1)
                .param("mode", L4J_MODES).param("scenario", "baseline", "idle")
                .param("message", "template").param("handlers", "shared")));
        for (int threads : new int[] {4, 8}) {
            if (threads > hardwareThreads) {
                System.out.printf("Skipping the Log4j 2 %d-thread runs: this machine has %d hardware threads.%n",
                        threads, hardwareThreads);
                continue;
            }
            runs.add(new Run("log4j2-t" + threads, o -> o.include(LOG4J2_CALL + "info").threads(threads)
                    .param("mode", "sync").param("scenario", "baseline", "gate+storm", "idle")
                    .param("message", "template", "concatenated").param("handlers", "shared", "per-thread")));
        }
        return runs;
    }

    /** The Log4j 2 this jar runs on, recorded beside the results so the report can say what was measured. */
    static String log4j2Version() {
        String version = org.apache.logging.log4j.core.LoggerContext.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
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
