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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * doc/specs/overhead-benchmarks.md Decision #19: turns one results folder's
 * JMH JSON files and {@code machine.txt} into {@code report.md}. That file
 * holds the overhead table (Decision #16), the budget checks (#8, #13), the
 * #23/#24 decision rules (#6), the component benchmarks and the machine.
 *
 * <p>Usage: {@code java -cp benchmarks.jar org.logaperture.bench.Report <results-folder>}
 */
public final class Report {

    private static final String IDLE = "org.logaperture.bench.IdleBenchmark.info";
    private static final String RULES = "org.logaperture.bench.RulesBenchmark.info";
    private static final String SAVINGS = "org.logaperture.bench.SavingsBenchmark.info";
    private static final String GATE_EMPTY = "org.logaperture.core.IdleComponentsBenchmark.gateEmpty";
    private static final String STORM_OFF = "org.logaperture.adapter.jul.StormFilterComponentsBenchmark.stormOff";
    private static final String STORM_OFF_DELEGATE =
            "org.logaperture.adapter.jul.StormFilterComponentsBenchmark.stormOffDelegate";
    private static final String GATE_CURVE = "org.logaperture.core.GateCurveBenchmark.gate";
    private static final List<String> MESSAGES = List.of("template", "concatenated", "throwable");
    private static final List<String> SCENARIOS = List.of("rule", "rule+storm", "rule+storm-off", "rule+storm+trim",
            "idle+storm", "idle",
            "drop-miss", "drop-hit", "trim", "trim-typed");
    /** The effective rule count of {@code RulesBenchmark}'s scenarios. */
    private static final int RULE_COUNT = 20;

    /** One JMH result row. {@code error} is the 99.9 % half-width, {@code NaN} with too few iterations. */
    record Result(String benchmark, Map<String, String> params, int threads, double score, double error,
            double allocBytes) {

        String param(String name) {
            return params.getOrDefault(name, "");
        }

        /** The scenario id: an {@code IdleBenchmark} layer or a {@code RulesBenchmark} scenario. */
        String scenario() {
            return benchmark.equals(IDLE) ? param("layers") : param("scenario");
        }

        /** {@code RulesBenchmark} has no handlers parameter: it always logs through one shared handler. */
        String handlers() {
            return params.getOrDefault("handlers", "shared");
        }

        /** {@code Type.method}, plus any JMH parameters, so parameterized rows stay apart. */
        String shortName() {
            int method = benchmark.lastIndexOf('.');
            int type = benchmark.lastIndexOf('.', method - 1);
            String name = benchmark.substring(type + 1);
            if (params.isEmpty()) {
                return name;
            }
            StringBuilder withParams = new StringBuilder(name).append(" (");
            params.forEach((key, value) -> withParams.append(key).append('=').append(value).append(", "));
            withParams.setLength(withParams.length() - 2);
            return withParams.append(')').toString();
        }
    }

    /** A value and its error: a JMH score, or a quantity derived from several. */
    record Measured(double value, double error) {

        Measured minus(Measured other) {
            return new Measured(value - other.value, Math.hypot(error, other.error));
        }

        /**
         * {@code this ÷ base}, with the usual propagated error: σ_r = √((σ_d/b)² + (d·σ_b/b²)²),
         * the spec's √((e_d/d)² + (e_b/b)²) multiplied through, so a zero difference doesn't divide by zero.
         */
        Measured over(Measured base) {
            double ratio = value / base.value;
            double err = Math.hypot(error / base.value, value * base.error / (base.value * base.value));
            return new Measured(ratio, err);
        }

        /** The upper end the budget is checked against; just the value when there is no error bar. */
        double upper() {
            return Double.isNaN(error) ? value : value + error;
        }
    }

    private final List<Result> results;
    private final String machine;

    Report(List<Result> results, String machine) {
        this.results = results;
        this.machine = machine;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: Report <results-folder>");
            System.exit(1);
        }
        Path folder = Path.of(args[0]);
        Path machineFile = folder.resolve("machine.txt");
        String machine = Files.exists(machineFile) ? Files.readString(machineFile, StandardCharsets.UTF_8) : "";
        List<Result> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path json : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                results.addAll(read(Files.readString(json, StandardCharsets.UTF_8)));
            }
        }
        Path out = folder.resolve("report.md");
        Files.writeString(out, new Report(results, machine).render(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    @SuppressWarnings("unchecked")
    static List<Result> read(String json) {
        List<Result> results = new ArrayList<>();
        for (Object row : (List<Object>) Json.parse(json)) {
            Map<String, Object> r = (Map<String, Object>) row;
            Map<String, String> params = new LinkedHashMap<>();
            Object rawParams = r.get("params");
            if (rawParams instanceof Map<?, ?> p) {
                p.forEach((k, v) -> params.put((String) k, String.valueOf(v)));
            }
            Map<String, Object> primary = (Map<String, Object>) r.get("primaryMetric");
            Map<String, Object> secondary = (Map<String, Object>) r.getOrDefault("secondaryMetrics", Map.of());
            double alloc = Double.NaN;
            if (secondary.get("gc.alloc.rate.norm") instanceof Map<?, ?> norm) {
                alloc = Json.number(norm.get("score"));
            }
            results.add(new Result((String) r.get("benchmark"), params, (int) Json.number(r.get("threads")),
                    Json.number(primary.get("score")), Json.number(primary.get("scoreError")), alloc));
        }
        return results;
    }

    String render() {
        StringBuilder md = new StringBuilder();
        md.append("# LogAperture overhead report\n\n");
        warnings(md);
        md.append("""
                **Overhead** is how much longer one `INFO` log call takes with LogAperture than without
                it (`baseline`), on this machine, in the same run: (scenario − baseline) ÷ baseline.
                The baseline is about the cheapest real log call there is (JBoss LogManager, a short
                pattern, output thrown away), so against a real file handler the same nanoseconds are
                a smaller percentage. The nanosecond columns describe this machine only. Errors are
                JMH's 99.9 % confidence intervals, combined for differences and ratios.

                """);
        overheadTable(md);
        budgets(md);
        savings(md);
        decisionRules(md);
        components(md);
        md.append("## Machine\n\n```\n").append(machine.strip()).append("\n```\n");
        return md.toString();
    }

    private void warnings(StringBuilder md) {
        List<String> warnings = new ArrayList<>();
        if (machineValue("quick").equalsIgnoreCase("true")) {
            warnings.add("This was a `--quick` run: one fork and a few short iterations. The numbers are "
                    + "indicative only, the error bars are wide, and a budget can fail on its error bar alone.");
        }
        double clock = parseOr(machineValue("nanoTime (ns)"), Double.NaN);
        if (clock > ClockCheck.LIMIT_NS) {
            warnings.add(String.format(Locale.ROOT, "One `System.nanoTime()` call cost %.0f ns on this machine "
                    + "(about 25 ns is normal). Every log record reads the clock, so these numbers are "
                    + "inflated and shouldn't be compared with anything.", clock));
        }
        String governor = machineValue("governor");
        if (!governor.isEmpty() && !governor.equals("performance")) {
            warnings.add("The CPU frequency governor was `" + governor + "`, not `performance`; expect wider "
                    + "error bars.");
        }
        if (machineValue("boost").equals("1")) {
            warnings.add("CPU boost (turbo) was on; expect wider error bars.");
        }
        for (String warning : warnings) {
            md.append("> **Warning:** ").append(warning).append("\n>\n");
        }
        if (!warnings.isEmpty()) {
            md.setLength(md.length() - 2);
            md.append("\n");
        }
    }

    private void overheadTable(StringBuilder md) {
        md.append("## Overhead per log call\n\n");
        md.append("| Scenario | Message | Threads | Handlers | Overhead | Added (ns) | Score (ns/op) "
                + "| Baseline (ns/op) | Allocated (B/op) |\n");
        md.append("|---|---|--:|---|--:|--:|--:|--:|--:|\n");
        List<Result> rows = results.stream()
                .filter(r -> r.benchmark().equals(IDLE) || r.benchmark().equals(RULES))
                .filter(r -> !r.scenario().equals("baseline"))
                .sorted(Comparator.comparing((Result r) -> !r.handlers().equals("shared"))
                        .thenComparingInt(Result::threads)
                        .thenComparingInt(r -> MESSAGES.indexOf(r.param("message")))
                        .thenComparingInt(r -> SCENARIOS.indexOf(r.scenario())))
                .toList();
        for (Result row : rows) {
            Optional<Result> base = baseline(row.param("message"), row.threads(), row.handlers());
            md.append("| `").append(row.scenario()).append("` | ").append(row.param("message"))
                    .append(" | ").append(row.threads()).append(" | ").append(row.handlers()).append(" | ");
            if (base.isPresent()) {
                Measured added = measured(row).minus(measured(base.get()));
                md.append(percent(added.over(measured(base.get())))).append(" | ").append(signedNs(added));
            } else {
                md.append("— | —");
            }
            md.append(" | ").append(ns(measured(row))).append(" | ")
                    .append(base.map(b -> ns(measured(b))).orElse("—")).append(" | ")
                    .append(bytes(row.allocBytes())).append(" |\n");
        }
        if (rows.isEmpty()) {
            md.append("| (no scenario results) |||||||||\n");
        }
        md.append('\n');
    }

    /**
     * Decision #13's table, #8's rule-scenario budget, each checked as difference plus its error. A
     * {@code NaN} budget marks a row that is reported, not checked (Decision #24's pipeline row).
     */
    private void budgets(StringBuilder md) {
        md.append("## Budgets\n\n");
        md.append("Single thread, one shared handler. A check passes when the difference *plus* its error "
                + "is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule "
                + "budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the "
                + "logger.\n\n");
        md.append("| Check | Message | Measured (ns) | Budget (ns) | Result |\n|---|---|--:|--:|---|\n");
        int concatenatedLength = BenchContext.message(true, 48213).length();
        for (String message : MESSAGES) {
            budgetRow(md, "rule filter: `rule` − `baseline`", message,
                    layer("rule", message), baseline(message, 1, "shared"), 50);
        }
        budgetRow(md, "storm filter: `rule+storm` − `rule`", "template",
                layer("rule+storm", "template"), layer("rule", "template"), 100);
        budgetRow(md, "storm filter: `rule+storm` − `rule`", "concatenated",
                layer("rule+storm", "concatenated"), layer("rule", "concatenated"),
                100 + 4 * Math.min(concatenatedLength, 500)); // Decision #23: 4 ns per character
        // Decision #24: disabled storm, the shipped default since #151, at most 10 % of the enabled
        // budget. Checked on the component benchmark; the pipeline rows' error bars are too wide.
        budgetRow(md, "storm filter, disabled: `storm-off` − `storm-off-delegate` (component)", "any",
                component(STORM_OFF), component(STORM_OFF_DELEGATE), 10);
        for (String message : MESSAGES) {
            budgetRow(md, "storm filter, disabled: `rule+storm-off` − `rule`", message,
                    layer("rule+storm-off", message), layer("rule", message), Double.NaN);
        }
        for (String message : MESSAGES) {
            budgetRow(md, "no rule can match: `drop-miss` − `idle`", message,
                    rules("drop-miss", message), layer("idle", message), 50);
        }
        for (String scenario : List.of("drop-hit", "trim")) {
            for (String message : MESSAGES) {
                budgetRow(md, RULE_COUNT + " rules: `" + scenario + "` − `idle`", message,
                        rules(scenario, message), layer("idle", message), ruleBudget(RULE_COUNT));
            }
        }
        // Decision #22: with no throwable, no type-bound trim is a candidate, so it gets drop-miss's budget.
        for (String message : MESSAGES) {
            double budget = message.equals("throwable") ? ruleBudget(RULE_COUNT) : 50;
            budgetRow(md, RULE_COUNT + " type-bound trims: `trim-typed` − `idle`", message,
                    rules("trim-typed", message), layer("idle", message), budget);
        }
        Optional<Result> gateEmpty = results.stream().filter(r -> r.benchmark().equals(GATE_EMPTY)).findFirst();
        List<Result> curve = results.stream()
                .filter(r -> r.benchmark().equals(GATE_CURVE))
                .sorted(Comparator.comparingInt(r -> Integer.parseInt(r.param("rules"))))
                .toList();
        for (Result gate : curve) {
            int n = Integer.parseInt(gate.param("rules"));
            budgetRow(md, "gate alone, " + n + " rules: `gate-n` − `gate-empty`", "template",
                    Optional.of(gate), gateEmpty, ruleBudget(n));
        }
        md.append('\n');
    }

    /** Decision #21: what the gate may add for {@code n} rules effective on the record's logger. */
    static double ruleBudget(int n) {
        return 50 + 35 * (Math.log(n + 1) / Math.log(2));
    }

    /**
     * Decision #22: a matched record through a real file handler, against
     * no LogAperture through the same handler. Reported, not budgeted; a
     * negative number is the agent saving more than it costs.
     */
    private void savings(StringBuilder md) {
        List<Result> rows = results.stream().filter(r -> r.benchmark().equals(SAVINGS)).toList();
        if (rows.isEmpty()) {
            return;
        }
        md.append("## What a match saves\n\n");
        md.append("One `INFO` call through a real file handler (autoflush on), single thread: the whole agent "
                + "with " + RULE_COUNT + " rules, the last of which matches, against no LogAperture through the "
                + "same handler. `throwable` carries a " + SavingsBenchmark.DEEP_STACK_FRAMES + "-frame "
                + "exception. A negative change means the matched record cost less than with no agent at "
                + "all. Reported, not budgeted (Decision #22).\n\n");
        md.append("| Scenario | Message | Change | Change (ns) | Score (ns/op) | No agent (ns/op) |\n");
        md.append("|---|---|--:|--:|--:|--:|\n");
        for (String message : List.of("template", "throwable")) {
            Optional<Result> base = rows.stream()
                    .filter(r -> r.scenario().equals("baseline") && r.param("message").equals(message)).findFirst();
            for (String scenario : List.of("drop-hit", "trim")) {
                Optional<Result> row = rows.stream()
                        .filter(r -> r.scenario().equals(scenario) && r.param("message").equals(message)).findFirst();
                md.append("| `").append(scenario).append("` | ").append(message).append(" | ");
                if (row.isEmpty() || base.isEmpty()) {
                    md.append("not run | — | — | — |\n");
                    continue;
                }
                Measured change = measured(row.get()).minus(measured(base.get()));
                md.append(percent(change.over(measured(base.get())))).append(" | ").append(signedNs(change))
                        .append(" | ").append(ns(measured(row.get()))).append(" | ")
                        .append(ns(measured(base.get()))).append(" |\n");
            }
        }
        md.append('\n');
    }

    private void budgetRow(StringBuilder md, String check, String message, Optional<Result> scenario,
            Optional<Result> reference, double budget) {
        md.append("| ").append(check).append(" | ").append(message).append(" | ");
        boolean reportedOnly = Double.isNaN(budget);
        if (scenario.isEmpty() || reference.isEmpty()) {
            md.append("not run | ").append(reportedOnly ? "—" : format(budget)).append(" | — |\n");
            return;
        }
        Measured diff = measured(scenario.get()).minus(measured(reference.get()));
        if (reportedOnly) {
            md.append(signedNs(diff)).append(" | — | reported |\n");
            return;
        }
        String verdict = diff.upper() <= budget ? "pass" : "**over**";
        if (Double.isNaN(diff.error())) {
            verdict += " (no error bar)";
        }
        md.append(signedNs(diff)).append(" | ").append(format(budget)).append(" | ").append(verdict).append(" |\n");
    }

    /** Decision #6, written down before measuring. */
    private void decisionRules(StringBuilder md) {
        md.append("## Decision rules\n\n");

        // #23: the top layer, with a throwable, over 25 % of the baseline with a throwable. Measured on
        // the layer chain with storm enabled, so `top` is the only layer between the two.
        Optional<Result> idle = layer("idle+storm", "throwable");
        Optional<Result> belowTop = layer("rule+storm+trim", "throwable");
        Optional<Result> base = baseline("throwable", 1, "shared");
        md.append("- **#23** (`top` renders a throwable's stack trace a second time): ");
        if (idle.isPresent() && belowTop.isPresent() && base.isPresent()) {
            Measured top = measured(idle.get()).minus(measured(belowTop.get()));
            Measured share = top.over(measured(base.get()));
            boolean in = share.value() > 0.25;
            md.append("the `top` layer costs ").append(signedNs(top)).append(" ns, ").append(percent(share))
                    .append(" of the baseline with a throwable; the threshold is 25 %. ")
                    .append(in ? "**Goes into 1.0.**" : "Stays out of 1.0.").append('\n');
        } else {
            md.append("not run (needs `baseline`, `rule+storm+trim` and `idle+storm` with the throwable message).\n");
        }

        // #24: idle on separate handlers at the most threads run, against its own single thread.
        int most = results.stream().filter(r -> r.benchmark().equals(IDLE) && r.handlers().equals("per-thread"))
                .mapToInt(Result::threads).max().orElse(0);
        md.append("- **#24** (`top`'s one lock across independent handlers): ");
        Optional<Result> idleOne = find(IDLE, "idle", "template", 1, "per-thread");
        Optional<Result> idleMost = find(IDLE, "idle", "template", most, "per-thread");
        Optional<Result> baseOne = find(IDLE, "baseline", "template", 1, "per-thread");
        Optional<Result> baseMost = find(IDLE, "baseline", "template", most, "per-thread");
        if (most > 1 && idleOne.isPresent() && idleMost.isPresent() && baseOne.isPresent() && baseMost.isPresent()) {
            Measured idleScaling = measured(idleMost.get()).over(measured(idleOne.get()));
            Measured baseScaling = measured(baseMost.get()).over(measured(baseOne.get()));
            boolean in = idleScaling.value() > 1.5 && baseScaling.value() <= 1.5;
            md.append(String.format(Locale.ROOT, "at %d threads on separate handlers, `idle` takes %s× its "
                    + "single-thread time and `baseline` %s×; the rule is `idle` over 1.5× while `baseline` "
                    + "is not. ", most, ratio(idleScaling), ratio(baseScaling)))
                    .append(in ? "**Goes into 1.0.**" : "Stays out of 1.0.");
            if (most < 8) {
                md.append(" (The rule is written for 8 threads; this machine ran ").append(most).append(".)");
            }
            md.append('\n');
        } else {
            md.append("not run (needs `baseline` and `idle`, template, per-thread handlers, at 1 thread and "
                    + "at least one more).\n");
        }

        md.append("- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.\n\n");
    }

    private void components(StringBuilder md) {
        List<Result> rows = results.stream()
                .filter(r -> !r.benchmark().equals(IDLE) && !r.benchmark().equals(RULES)
                        && !r.benchmark().equals(SAVINGS))
                .sorted(Comparator.comparing(Result::shortName))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        md.append("## Component benchmarks\n\n");
        md.append("Each hot-path piece alone, to cross-check the differences above.\n\n");
        md.append("| Benchmark | Score (ns/op) | Allocated (B/op) |\n|---|--:|--:|\n");
        for (Result row : rows) {
            md.append("| `").append(row.shortName()).append("` | ").append(ns(measured(row))).append(" | ")
                    .append(bytes(row.allocBytes())).append(" |\n");
        }
        md.append('\n');
    }

    private Optional<Result> baseline(String message, int threads, String handlers) {
        return find(IDLE, "baseline", message, threads, handlers);
    }

    /** An {@code IdleBenchmark} layer, single thread, shared handler: what the budgets are written for. */
    private Optional<Result> layer(String layers, String message) {
        return find(IDLE, layers, message, 1, "shared");
    }

    /** A component benchmark's one result, by its full name. */
    private Optional<Result> component(String benchmark) {
        return results.stream().filter(r -> r.benchmark().equals(benchmark)).findFirst();
    }

    private Optional<Result> rules(String scenario, String message) {
        return find(RULES, scenario, message, 1, "shared");
    }

    private Optional<Result> find(String benchmark, String scenario, String message, int threads,
            String handlers) {
        Predicate<Result> matches = r -> r.benchmark().equals(benchmark) && r.scenario().equals(scenario)
                && r.param("message").equals(message) && r.threads() == threads && r.handlers().equals(handlers);
        return results.stream().filter(matches).findFirst();
    }

    private String machineValue(String key) {
        for (String line : machine.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(key)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static Measured measured(Result r) {
        return new Measured(r.score(), r.error());
    }

    private static double parseOr(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String percent(Measured ratio) {
        String value = String.format(Locale.ROOT, "%+.1f %%", ratio.value() * 100);
        return Double.isNaN(ratio.error()) ? value
                : value + String.format(Locale.ROOT, " ± %.1f %%", ratio.error() * 100);
    }

    private static String ratio(Measured ratio) {
        return Double.isNaN(ratio.error()) ? String.format(Locale.ROOT, "%.2f", ratio.value())
                : String.format(Locale.ROOT, "%.2f ± %.2f", ratio.value(), ratio.error());
    }

    private static String ns(Measured m) {
        return Double.isNaN(m.error()) ? format(m.value()) : format(m.value()) + " ± " + format(m.error());
    }

    private static String signedNs(Measured m) {
        String value = (m.value() >= 0 ? "+" : "") + format(m.value());
        return Double.isNaN(m.error()) ? value : value + " ± " + format(m.error());
    }

    private static String format(double ns) {
        return Math.abs(ns) < 100 ? String.format(Locale.ROOT, "%.1f", ns) : String.format(Locale.ROOT, "%.0f", ns);
    }

    private static String bytes(double b) {
        return Double.isNaN(b) ? "—" : String.format(Locale.ROOT, "%.0f", b);
    }
}
