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
package org.logaperture.cli;

import org.logaperture.control.jmx.DoctorFindingData;
import org.logaperture.control.jmx.EnvironmentReportData;
import org.logaperture.control.jmx.HandlerFloorData;
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerByteCountData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;
import org.logaperture.control.jmx.TopReportData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The sub-commands, each a thin renderer over one (occasionally two)
 * {@link org.logaperture.control.jmx.LevelControlMXBean} calls — see
 * doc/specs/cli-transport.md "Command surface" and doc/specs/
 * handler-floor-control.md "The operation" for {@code handler}/{@code
 * resetHandler}. An exact-name {@code reset} still reads {@code
 * listLoggers} before and after -- {@code resetLevel} on an exact name
 * only reports the reverted logger's name, not its resulting effective
 * level -- but a pattern {@code reset} takes its answer directly from
 * {@code resetLevel}'s {@code ResetOutcomeData}, not from diffing
 * {@code listLoggers} reads around the call.
 */
final class Commands {

    private Commands() {
    }

    static Command levels(String filter, boolean json) {
        return (mbean, out, in, interactive) -> {
            List<LoggerInfoData> rows = mbean.listLoggers(filter);
            if (json) {
                out.println(Json.loggers(rows));
                return CliError.OK;
            }
            if (rows.isEmpty()) {
                out.println(filter == null ? "No loggers known yet." : "No loggers match '" + filter + "'.");
                return CliError.OK;
            }
            boolean showContext = spansMultipleContexts(rows, LoggerInfoData::getContext);
            boolean showCascade = rows.stream().anyMatch(LoggerInfoData::isCascading);
            List<List<String>> table = new ArrayList<>();
            for (LoggerInfoData row : rows) {
                List<String> cells = new ArrayList<>();
                if (showContext) {
                    cells.add(orDash(row.getContext()));
                }
                cells.add(orDash(row.getName()));
                cells.add(orDash(row.getConfiguredLevel()));
                cells.add(orDash(row.getEffectiveLevel()));
                cells.add(overrideCell(row));
                if (showCascade) {
                    cells.add(cascadeCell(row));
                }
                table.add(cells);
            }
            List<String> headers = new ArrayList<>();
            if (showContext) {
                headers.add("CONTEXT");
            }
            headers.addAll(List.of("LOGGER", "CONFIGURED", "EFFECTIVE", "OVERRIDE"));
            if (showCascade) {
                headers.add("CASCADE");
            }
            out.println(Format.table(headers, table));
            return CliError.OK;
        };
    }

    static Command status(boolean json) {
        return (mbean, out, in, interactive) -> {
            List<LoggerInfoData> all = mbean.listLoggers(null);
            List<LoggerInfoData> active = new ArrayList<>();
            for (LoggerInfoData row : all) {
                if (row.isOverrideActive()) {
                    active.add(row);
                }
            }
            active.sort(Comparator.comparing(Commands::revertSortKey).thenComparing(LoggerInfoData::getName));
            List<HandlerLevelOverrideData> handlerOverrides = mbean.listHandlerOverrides();
            if (json) {
                out.println(Json.status(active, handlerOverrides));
                return CliError.OK;
            }
            if (active.isEmpty() && handlerOverrides.isEmpty()) {
                out.println("No active overrides.");
                return CliError.OK;
            }
            if (!active.isEmpty()) {
                boolean showContext = spansMultipleContexts(all, LoggerInfoData::getContext);
                boolean showCascade = active.stream().anyMatch(LoggerInfoData::isCascading);
                List<List<String>> table = new ArrayList<>();
                for (LoggerInfoData row : active) {
                    List<String> cells = new ArrayList<>();
                    if (showContext) {
                        cells.add(orDash(row.getContext()));
                    }
                    cells.add(orDash(row.getName()));
                    cells.add(orDash(row.getEffectiveLevel()));
                    cells.add(orDash(row.getTier()));
                    cells.add(revertsCell(row));
                    cells.add(row.getOverrideReason() == null ? Format.NONE : '"' + row.getOverrideReason() + '"');
                    if (showCascade) {
                        cells.add(cascadeCell(row));
                    }
                    table.add(cells);
                }
                List<String> headers = new ArrayList<>();
                if (showContext) {
                    headers.add("CONTEXT");
                }
                headers.addAll(List.of("LOGGER", "LEVEL", "TIER", "REVERTS", "REASON"));
                if (showCascade) {
                    headers.add("CASCADE");
                }
                out.println(Format.table(headers, table));
            }
            if (!handlerOverrides.isEmpty()) {
                if (!active.isEmpty()) {
                    out.println();
                }
                List<List<String>> table = new ArrayList<>();
                for (HandlerLevelOverrideData row : handlerOverrides) {
                    table.add(List.of(
                            orDash(row.getHandlerRef()),
                            orDash(row.getLevel()),
                            orDash(row.getMode()),
                            orDash(row.getTier()),
                            handlerRevertsCell(row),
                            row.getReason() == null ? Format.NONE : '"' + row.getReason() + '"'));
                }
                out.println(Format.table(List.of("HANDLER", "LEVEL", "MODE", "TIER", "REVERTS", "REASON"), table));
            }
            return CliError.OK;
        };
    }

    /**
     * The CONTEXT column shows only when the result actually spans more than
     * one logging context — a plain {@code java -jar} user, and a stock
     * standalone WildFly (one shared system context), never see it
     * (doc/specs/wildfly-support.md, Slice 3's "logctl changes"). Shared by
     * every row type that carries a context key ({@link LoggerInfoData},
     * {@link DoctorFindingData}).
     */
    private static <T> boolean spansMultipleContexts(List<T> rows, Function<T, String> context) {
        return rows.stream()
                .map(context)
                .filter(Objects::nonNull)
                .distinct()
                .limit(2)
                .count() > 1;
    }

    /**
     * Whether {@code target} is a pattern rather than an exact logger name --
     * the one check both {@code setLevel} and {@code reset} branch on before
     * deciding whether they're looking at a standing rule (doc/specs/
     * pattern-level-targeting.md). {@code logaperture-core}'s equivalent
     * ({@code NameFilter.isPattern}) isn't reachable from this module --
     * package-private, and deliberately not part of the JMX wire surface --
     * so this is its own small copy rather than a shared dependency.
     */
    private static boolean isPattern(String target) {
        return target.indexOf('*') >= 0;
    }

    /**
     * {@code target} is either an exact logger name or a pattern (doc/specs/
     * pattern-level-targeting.md) — a {@code *} anywhere in it means the
     * latter, resolved the same way {@code logctl levels} already detects
     * one. A pattern is a standing rule: it previews its current matches
     * and asks for confirmation ({@code --yes} skips the prompt) before
     * applying, since it also reaches loggers discovered later.
     */
    static Command setLevel(String target, String level, String reason, String tierName, long forSeconds,
            boolean yes, boolean json) {
        return (mbean, out, in, interactive) -> {
            boolean isPattern = isPattern(target);
            boolean confirmed = yes || !isPattern;
            // Non-null only when a preview was actually shown -- the set of
            // names the preview promised, kept around so the apply below
            // can call out any it silently drops instead of leaving the
            // discrepancy unexplained (a code-review finding: the preview
            // and the apply query precedence at two different instants, so
            // a previewed logger can lose precedence in between and the old
            // code just never mentioned it again).
            List<LoggerInfoData> previewed = null;
            if (isPattern && !confirmed) {
                previewed = mbean.listLoggers(target);
                if (!interactive) {
                    // Decision #4: fail fast rather than block forever on a
                    // read from a stdin nothing will ever write to -- but
                    // still explain the standing-rule consequence, not just
                    // name the escape hatch, since this is the *only* message
                    // a non-interactive caller (every `wildflyctl.py logctl
                    // --`/`docker compose exec -T` invocation) ever sees; the
                    // full preview (printPatternPreview) never runs here (#46).
                    throw new CliError(CliError.USAGE,
                            "'" + target + "' is a pattern -- applying it creates a standing rule: " + level
                                    + " on every currently-matching logger, AND on any new one discovered later, "
                                    + "until 'logctl reset logger " + target + "'. Pass --yes to apply it "
                                    + "non-interactively.");
                }
                printPatternPreview(out, target, level, previewed);
                if (!readYesAnswer(in)) {
                    out.println("Not applied.");
                    return CliError.OK;
                }
                confirmed = true;
            }

            SetLevelResultData result = mbean.setLevel(target, level, reason, tierName, forSeconds, confirmed);
            List<LevelOverrideData> overrides = result.getOverrides();
            if (json) {
                out.println(Json.setLevelResult(result));
                return CliError.OK;
            }
            if (overrides.isEmpty()) {
                // A pattern with no currently-known match yet -- the rule
                // still stands, ready for the sweep (doc/specs/
                // pattern-level-targeting.md "Sweep integration").
                out.println("Standing rule created for '" + target + "' — no currently-known logger matches yet; "
                        + "it will apply to one discovered later.");
                return CliError.OK;
            }
            for (LevelOverrideData override : overrides) {
                out.println(override.getLoggerName() + " → " + override.getLevel() + "   ("
                        + tierDetail(override.getTier(), override.getExpiresAt()) + ")");
            }
            printSkippedFromPreview(out, previewed, overrides);
            printBlockingHandlersWarning(out, level, result.getBlockingHandlers());
            return CliError.OK;
        };
    }

    /** The confirmation preview (doc/specs/pattern-level-targeting.md "Confirmation and CLI behavior"). */
    private static void printPatternPreview(java.io.PrintStream out, String pattern, String level,
            List<LoggerInfoData> matches) {
        if (matches.isEmpty()) {
            out.println("This will create a standing rule setting " + level + " on any logger matching '"
                    + pattern + "' — none currently known, but it will apply the moment one is discovered.");
        } else {
            out.println("This will set " + level + " on " + matches.size() + " currently-known logger"
                    + (matches.size() == 1 ? "" : "s") + " matching '" + pattern + "':");
            for (LoggerInfoData match : matches) {
                out.println("  " + match.getName());
            }
        }
        out.println("Because this is a pattern, LogAperture will also apply " + level + " to any new logger "
                + "discovered later that matches '" + pattern + "', until you run 'logctl reset logger " + pattern + "'.");
        out.print("Apply this standing rule? [y/N] ");
        out.flush();
    }

    /**
     * Names any logger the preview promised would change that the apply
     * then didn't actually mutate -- it lost precedence to a higher-priority
     * override in the interval between the preview read and the apply
     * (doc/specs/pattern-level-targeting.md "Precedence"). Silent about it
     * is exactly the code-review finding this exists to fix: the preview
     * and the confirmed apply are two separate calls, so some staleness
     * between them is inherent to the confirm-then-apply flow, but leaving
     * the resulting gap unexplained looked like a bug in the tool rather
     * than an accurate reflection of what happened.
     */
    private static void printSkippedFromPreview(java.io.PrintStream out, List<LoggerInfoData> previewed,
            List<LevelOverrideData> applied) {
        if (previewed == null || previewed.isEmpty()) {
            return;
        }
        java.util.Set<String> appliedNames = new java.util.HashSet<>();
        for (LevelOverrideData override : applied) {
            appliedNames.add(override.getLoggerName());
        }
        for (LoggerInfoData match : previewed) {
            if (!appliedNames.contains(match.getName())) {
                out.println(match.getName() + " — left unchanged; it picked up a higher-precedence override "
                        + "between the preview and the apply.");
            }
        }
    }

    private static boolean readYesAnswer(java.io.InputStream in) {
        try {
            String line = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The actionable warning doc/specs/handler-floor-control.md "Warning on
     * level commands" calls for: names every handler that will still
     * swallow records at the level just applied, and the exact {@code
     * logctl handler} command to clear each one — one per handler, since a
     * developer may only want the console lowered, not every sink.
     */
    private static void printBlockingHandlersWarning(java.io.PrintStream out, String level, List<HandlerFloorData> blocking) {
        if (blocking.isEmpty()) {
            return;
        }
        if (blocking.size() == 1) {
            HandlerFloorData floor = blocking.get(0);
            out.println("WARN: handler " + floor.getHandlerRef() + " is at " + floor.getCurrentLevel()
                    + " and will drop " + level + " records from this logger.");
            out.println("      To see them: logctl handler " + floor.getHandlerRef() + " " + level);
        } else {
            out.println("WARN: " + blocking.size() + " handlers are above " + level
                    + " and will drop these records:");
            for (HandlerFloorData floor : blocking) {
                out.println("      logctl handler " + floor.getHandlerRef() + " " + level
                        + "   (currently " + floor.getCurrentLevel() + ")");
            }
            out.println("      Run the ones you actually want -- you may only need one.");
        }
    }

    static Command setHandlerLevel(String handlerRef, String level, String reason, String tierName, long forSeconds,
            boolean json) {
        return (mbean, out, in, interactive) -> {
            HandlerLevelOverrideData result = mbean.setHandlerLevel(handlerRef, level, reason, tierName, forSeconds);
            if (result == null) {
                // doc/specs/handler-floor-control.md "Logback / none" -- this
                // framework's handlers have no level of their own.
                if (json) {
                    out.println(Json.handlerNoOp(handlerRef));
                } else {
                    out.println("logctl handler: this framework's handlers have no level of their own; "
                            + "nothing to change.");
                }
                return CliError.OK;
            }
            if (json) {
                out.println(Json.handlerOverride(result));
                return CliError.OK;
            }
            out.println("handler " + handlerRef + " → " + result.getLevel() + "   ("
                    + tierDetail(result.getTier(), result.getExpiresAt()) + ")");
            return CliError.OK;
        };
    }

    /**
     * {@code logctl handler <name> AUTO} — doc/specs/handler-floor-control.md
     * "AUTO handler level" (issue #20). Puts {@code handlerRef} into a
     * self-tracking mode instead of a fixed level.
     */
    static Command setHandlerAuto(String handlerRef, String reason, String tierName, long forSeconds, boolean json) {
        return (mbean, out, in, interactive) -> {
            HandlerLevelOverrideData result = mbean.setHandlerAuto(handlerRef, reason, tierName, forSeconds);
            if (result == null) {
                if (json) {
                    out.println(Json.handlerNoOp(handlerRef));
                } else {
                    out.println("logctl handler: this framework's handlers have no level of their own, or nothing "
                            + "is active to track yet; nothing to change.");
                }
                return CliError.OK;
            }
            if (json) {
                out.println(Json.handlerOverride(result));
                return CliError.OK;
            }
            out.println("handler " + handlerRef + " → AUTO, currently " + result.getLevel() + "   ("
                    + tierDetail(result.getTier(), result.getExpiresAt()) + ")");
            return CliError.OK;
        };
    }

    static Command resetHandler(String handlerRef, boolean includeSticky, boolean json) {
        return (mbean, out, in, interactive) -> {
            org.logaperture.control.jmx.ResetOutcomeData outcome = mbean.resetHandler(handlerRef, includeSticky);
            if (json) {
                out.println(Json.resetOutcome(outcome));
                return CliError.OK;
            }
            if (!outcome.getSkippedStickyNames().isEmpty()) {
                out.println(handlerRef + " — left untouched (STICKY; use --include-sticky to include it).");
            } else if (outcome.getRevertedNames().isEmpty()) {
                out.println(handlerRef + " — nothing was overridden.");
            } else {
                out.println("handler " + handlerRef + " → reset to its previous level.");
            }
            return CliError.OK;
        };
    }

    /**
     * {@code target} is either an exact logger name or a pattern. Resetting
     * a target that exactly matches a tracked standing rule's own pattern
     * reverts every logger it currently covers <em>and</em> retires the
     * rule, so it stops covering loggers discovered later too. A narrower
     * target — an exact name, or a sub-pattern that isn't itself a tracked
     * rule — carves {@code target} out of whichever rule currently governs
     * it instead of retiring the whole rule or no-op'ing (doc/specs/
     * reset-command-surface.md "Partial reset — scoped exclusions"). No
     * confirmation either way, matching {@code reset}'s existing no-prompt
     * convention (reverting a bounded, current state is the opposite risk
     * shape from applying an unbounded, future-reaching one).
     *
     * <p>The server reports exactly what it reverted, retired, excluded, and
     * skipped ({@link org.logaperture.control.jmx.ResetOutcomeData}), so this
     * needs at most one {@code listLoggers} read — after the call, to render
     * post-reset levels for whatever was actually reverted — never a
     * before/after pair: the outcome itself already answers "was anything
     * overridden" (a code-review finding against the original slice's
     * before/after diff, which was racy against concurrent mutation).
     */
    static Command resetLogger(String target, String reason, boolean includeSticky, boolean json) {
        return (mbean, out, in, interactive) -> {
            org.logaperture.control.jmx.ResetOutcomeData outcome = mbean.resetLevel(target, includeSticky, reason);
            List<String> reverted = outcome.getRevertedNames();
            List<String> retiredPatterns = outcome.getRetiredPatterns();
            List<String> excludedFrom = outcome.getExcludedFrom();

            if (json) {
                out.println(Json.resetOutcome(outcome));
                return CliError.OK;
            }

            if (reverted.isEmpty() && retiredPatterns.isEmpty() && excludedFrom.isEmpty()) {
                if (!outcome.getSkippedStickyNames().isEmpty()) {
                    // One line per skipped name, not per target -- a
                    // pattern target can leave more than one logger sticky-
                    // protected in a single call.
                    for (String name : outcome.getSkippedStickyNames()) {
                        out.println(name + " — left untouched (STICKY; use --include-sticky to include it).");
                    }
                } else {
                    out.println(target + " — nothing was overridden.");
                }
                return CliError.OK;
            }

            Map<String, LoggerInfoData> afterByName = new LinkedHashMap<>();
            if (!reverted.isEmpty()) {
                for (LoggerInfoData row : mbean.listLoggers(target)) {
                    afterByName.put(row.getName(), row);
                }
            }
            for (String name : reverted) {
                LoggerInfoData row = afterByName.get(name);
                if (row != null) {
                    out.println(name + " → " + row.getEffectiveLevel() + " (baseline)");
                } else {
                    out.println(name + " → baseline (not yet instantiated, so no level to show)");
                }
            }
            for (String pattern : retiredPatterns) {
                out.println(reverted.isEmpty()
                        ? "Standing rule '" + pattern + "' retired -- it had no currently-matched logger to revert."
                        : "Standing rule '" + pattern + "' retired.");
            }
            for (String pattern : excludedFrom) {
                out.println("Excluded '" + target + "' from standing rule '" + pattern + "' — its own descendants, "
                        + "and any logger discovered under it later, keep inheriting the rule.");
            }
            return CliError.OK;
        };
    }

    static Command resetAllLoggers(boolean includeSticky, boolean json) {
        return (mbean, out, in, interactive) ->
                printBroadResetSummary(out, json, "logger", mbean.resetAllLoggers(includeSticky));
    }

    static Command resetAllHandlers(boolean includeSticky, boolean json) {
        return (mbean, out, in, interactive) ->
                printBroadResetSummary(out, json, "handler", mbean.resetAllHandlers(includeSticky));
    }

    static Command resetAll(boolean includeSticky, boolean json) {
        return (mbean, out, in, interactive) ->
                printBroadResetSummary(out, json, null, mbean.resetAll(includeSticky));
    }

    /**
     * {@code reset loggers}/{@code reset handlers}/{@code reset --all}'s
     * shared rendering — a summary, not a per-name confirmation line
     * (doc/specs/reset-command-surface.md "CLI output"), same shape as the
     * pre-existing {@code resetAll}'s {@code "Reverted N override(s)."}.
     * {@code nounPrefix} is {@code null} for {@code reset --all}, which
     * spans both namespaces so no single noun fits.
     */
    private static int printBroadResetSummary(java.io.PrintStream out, boolean json, String nounPrefix,
            org.logaperture.control.jmx.ResetOutcomeData outcome) {
        if (json) {
            out.println(Json.resetOutcome(outcome));
            return CliError.OK;
        }
        String prefix = nounPrefix == null ? "" : nounPrefix + " ";
        out.println("Reverted " + outcome.getRevertedNames().size() + " " + prefix + "override(s).");
        int skipped = outcome.getSkippedStickyNames().size();
        if (skipped > 0) {
            out.println(skipped + " sticky override(s) left untouched (use --include-sticky).");
        }
        return CliError.OK;
    }

    /**
     * {@code logctl doctor} — doc/specs/doctor.md "The operation". Read-only:
     * no target, no tier, nothing to confirm.
     */
    static Command doctor(boolean json) {
        return (mbean, out, in, interactive) -> {
            List<DoctorFindingData> findings = mbean.diagnose();
            if (json) {
                out.println(Json.doctor(findings));
                return CliError.OK;
            }
            if (findings.isEmpty()) {
                out.println("No checks could run against this JVM.");
                return CliError.OK;
            }
            boolean showContext = spansMultipleContexts(findings, DoctorFindingData::getContext);
            int critical = 0;
            int warning = 0;
            int info = 0;
            int clean = 0;
            for (DoctorFindingData f : findings) {
                switch (f.getSeverity()) {
                    case "CRITICAL" -> critical++;
                    case "WARNING" -> warning++;
                    case "INFO" -> info++;
                    default -> clean++;
                }
                StringBuilder line = new StringBuilder();
                if (showContext) {
                    line.append('[').append(orDash(f.getContext())).append("] ");
                }
                String bracket = "[" + severityLabel(f.getSeverity()) + "]";
                line.append(bracket.length() < 8 ? String.format("%-8s", bracket) : bracket + " ")
                        .append(f.getSummary());
                out.println(line);
                if (f.getDetail() != null) {
                    out.println("        " + f.getDetail());
                }
                if (f.getSuggestedFix() != null) {
                    out.println("        suggested: " + f.getSuggestedFix());
                }
            }
            out.println();
            out.println(Json.checksRun(findings) + " checks run — " + critical + " critical, " + warning
                    + " warning, " + info + " info, " + clean + " clean.");
            return CliError.OK;
        };
    }

    /**
     * {@code logctl env} — doc/specs/environment-report.md "The operation".
     * Read-only, like {@code doctor}/{@code top}: no target, no tier,
     * nothing to confirm. {@code logctl}'s own version is stitched in
     * locally ({@link Main#version()}) alongside the agent-reported facts —
     * it's not on the wire shape at all (doc/specs/environment-report.md
     * "Data model": "Deliberately no {@code cliVersion} field").
     */
    static Command env(boolean json) {
        return (mbean, out, in, interactive) -> {
            EnvironmentReportData report = mbean.environmentReport();
            String cliVersion = Main.version();
            if (json) {
                out.println(Json.env(report, cliVersion));
                return CliError.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("LogAperture agent", report.getAgentVersion() + "  (logctl " + cliVersion + ")"));
            rows.add(List.of("Java", report.getJavaVersion() + "  " + report.getJavaVendor()));
            rows.add(List.of("OS", report.getOsName() + " " + report.getOsVersion() + "  " + report.getOsArch()));
            if (report.getBackendName() != null) {
                rows.add(List.of("Logging backend", nameAndVersion(report.getBackendName(), report.getBackendVersion())));
            }
            if (report.getContainerName() != null) {
                rows.add(List.of("Framework/container",
                        nameAndVersion(report.getContainerName(), report.getContainerVersion())));
            }
            // Decision #5: shown either way, including "not set" -- the next
            // question in most support threads, closed in the same paste.
            rows.add(List.of("Diagnostics level",
                    report.getDiagnosticsLevel() != null ? report.getDiagnosticsLevel() : Format.NONE));
            // Same "shown either way" convention -- doc/specs/environment-report.md
            // "State file": absent persistence is itself diagnostically useful,
            // not a fact to quietly drop the line for.
            rows.add(List.of("State file",
                    report.getStateFilePath() != null ? report.getStateFilePath() : Format.NONE));
            out.println(Format.table(rows));
            return CliError.OK;
        };
    }

    private static String nameAndVersion(String name, String version) {
        return version == null ? name : name + " " + version;
    }

    /**
     * {@code logctl handlers} — the addressable handler catalog (doc/specs/
     * handler-floor-control.md "The handler catalog", issue #15). Read-only,
     * no target, nothing to confirm — the counterpart to {@code logctl
     * levels} for handlers.
     */
    static Command handlers(boolean json) {
        return (mbean, out, in, interactive) -> {
            List<HandlerInfoData> rows = mbean.listHandlers();
            if (json) {
                out.println(Json.handlers(rows));
                return CliError.OK;
            }
            if (rows.isEmpty()) {
                out.println("This framework's handlers have no level of their own — nothing to list.");
                return CliError.OK;
            }
            boolean showContext = spansMultipleContexts(rows, HandlerInfoData::getContext);
            List<List<String>> table = new ArrayList<>();
            for (HandlerInfoData row : rows) {
                boolean notALiveHandler = row.getLevel() == null && !row.isPersistent()
                        && row.getTargetPath() == null && row.getAutoFlush() == null;
                List<String> cells = new ArrayList<>();
                if (showContext) {
                    cells.add(orDash(row.getContext()));
                }
                cells.add(orDash(row.getRef()));
                cells.add(orDash(row.getLevel()));
                cells.add(notALiveHandler ? Format.NONE : (row.isPersistent() ? "file" : "no"));
                cells.add(orDash(row.getTargetPath()));
                cells.add(handlerCatalogOverrideCell(row));
                table.add(cells);
            }
            List<String> headers = new ArrayList<>();
            if (showContext) {
                headers.add("CONTEXT");
            }
            headers.addAll(List.of("HANDLER", "LEVEL", "PERSISTS", "TARGET", "OVERRIDE"));
            out.println(Format.table(headers, table));
            return CliError.OK;
        };
    }

    /**
     * {@code logctl top} — doc/specs/top.md "The operation". Read-only, like
     * {@code doctor}; unlike it, rate and the stack-trace percentage are
     * computed here from the raw counts plus how long measurement has been
     * running, not carried on the wire (doc/specs/top.md "Data model").
     */
    static Command top(int limit, boolean json) {
        return (mbean, out, in, interactive) -> {
            TopReportData report = mbean.topLoggers(limit);
            if (json) {
                out.println(Json.top(report));
                return CliError.OK;
            }
            List<LoggerByteCountData> loggers = report.getLoggers();
            String startedAtRaw = report.getMeasurementStartedAt();
            if (loggers.isEmpty() || startedAtRaw == null) {
                out.println("No byte-volume measurements available yet.");
                return CliError.OK;
            }
            boolean showContext = spansMultipleContexts(loggers, LoggerByteCountData::getContext);
            Instant startedAt = Instant.parse(startedAtRaw);
            double hoursElapsed = Math.max(1.0 / 3_600, Duration.between(startedAt, Instant.now()).toMillis() / 3_600_000.0);

            List<List<String>> table = new ArrayList<>();
            for (LoggerByteCountData row : loggers) {
                double bytesPerHour = row.getTotalBytes() / hoursElapsed;
                long pct = row.getTotalBytes() == 0 ? 0 : Math.round(100.0 * row.getStackTraceBytes() / row.getTotalBytes());
                List<String> cells = new ArrayList<>();
                if (showContext) {
                    cells.add('[' + orDash(row.getContext()) + ']');
                }
                cells.add(row.getLoggerName());
                cells.add(Format.bytes(bytesPerHour) + "/h");
                cells.add("(" + Format.bytes(bytesPerHour * 24) + "/day)");
                cells.add(pct + "% stack traces");
                table.add(cells);
            }
            out.println(Format.table(table));
            out.println();
            out.println("measured over " + Format.elapsed(Duration.between(startedAt, Instant.now()))
                    + " (since agent start, " + startedAt + ") — " + report.getTrackedCount()
                    + (report.getTrackedCount() == 1 ? " logger" : " loggers") + " tracked.");
            return CliError.OK;
        };
    }

    /** {@code WARNING} renders as {@code WARN} in text mode (matching the blocking-handler warning's own convention); {@code --json} keeps the full enum name. */
    private static String severityLabel(String severity) {
        return "WARNING".equals(severity) ? "WARN" : severity;
    }

    /** Sorts soonest-revert first; {@code FOR} entries by their deadline, {@code STICKY}/{@code SESSION} (no deadline) last. */
    private static Instant revertSortKey(LoggerInfoData row) {
        return row.getExpiresAt() == null ? Instant.MAX : Instant.parse(row.getExpiresAt());
    }

    private static String orDash(String value) {
        return value == null ? Format.NONE : value;
    }

    private static String overrideCell(LoggerInfoData row) {
        if (!row.isOverrideActive()) {
            return Format.NONE;
        }
        StringBuilder cell = new StringBuilder(orDash(row.getTier()));
        if ("FOR".equals(row.getTier()) && row.getExpiresAt() != null) {
            cell.append(", reverts ").append(Format.clock(row.getExpiresAt()))
                    .append(" (").append(Format.relative(row.getExpiresAt())).append(')');
        }
        if (row.getOverrideReason() != null) {
            cell.append(" — \"").append(row.getOverrideReason()).append('"');
        }
        return cell.toString();
    }

    /**
     * {@code CASCADE} column (Decision #6, doc/specs/reset-command-surface.md
     * "Marking a rule-governed override") — says only that this row is
     * governed by a live standing rule and will keep propagating to
     * newly-discovered descendants, never which rule; naming the rule would
     * reintroduce exactly the provenance-recall Decision #2 exists to avoid.
     */
    private static String cascadeCell(LoggerInfoData row) {
        return row.isCascading() ? "yes" : Format.NONE;
    }

    private static String revertsCell(LoggerInfoData row) {
        if ("FOR".equals(row.getTier()) && row.getExpiresAt() != null) {
            return Format.clock(row.getExpiresAt()) + " (" + Format.relative(row.getExpiresAt()) + ")";
        }
        if ("SESSION".equals(row.getTier())) {
            return "until restart";
        }
        return "until reset";
    }

    private static String handlerCatalogOverrideCell(HandlerInfoData row) {
        if (!row.isOverrideActive()) {
            return Format.NONE;
        }
        StringBuilder cell = new StringBuilder();
        if ("AUTO".equals(row.getOverrideMode())) {
            cell.append("AUTO → ");
        }
        cell.append(orDash(row.getOverrideLevel())).append(" (").append(orDash(row.getOverrideTier()));
        if ("FOR".equals(row.getOverrideTier()) && row.getOverrideExpiresAt() != null) {
            cell.append(", reverts ").append(Format.relative(row.getOverrideExpiresAt()));
        }
        return cell.append(')').toString();
    }

    private static String handlerRevertsCell(HandlerLevelOverrideData row) {
        if ("FOR".equals(row.getTier()) && row.getExpiresAt() != null) {
            return Format.clock(row.getExpiresAt()) + " (" + Format.relative(row.getExpiresAt()) + ")";
        }
        if ("SESSION".equals(row.getTier())) {
            return "until restart";
        }
        return "until reset";
    }

    private static String tierDetail(String tier, String expiresAt) {
        return switch (tier) {
            case "SESSION" -> "SESSION — until the JVM stops";
            case "STICKY" -> "STICKY — until reset";
            case "FOR" -> expiresAt == null
                    ? "FOR"
                    : "FOR, reverts " + Format.clock(expiresAt) + " local — "
                            + Format.relative(expiresAt);
            default -> tier;
        };
    }
}
