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

import org.junit.jupiter.api.Test;
import org.logaperture.control.jmx.DoctorFindingData;
import org.logaperture.control.jmx.HandlerFloorData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerByteCountData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;
import org.logaperture.control.jmx.TopReportData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Every command but a pattern-targeted {@code setLevel} ignores {@code in}/{@code interactive}. */
    private int run(Command command) {
        return command.run(mbean, out, InputStream.nullInputStream(), false);
    }

    /** For the confirmation-prompt tests: {@code answer} is what a typed reply would send. */
    private int run(Command command, String answer, boolean interactive) {
        InputStream in = new ByteArrayInputStream((answer + "\n").getBytes(StandardCharsets.UTF_8));
        return command.run(mbean, out, in, interactive);
    }

    private static SetLevelResultData setLevelResult(LevelOverrideData override, List<HandlerFloorData> blocking) {
        return new SetLevelResultData(List.of(override), blocking);
    }

    @Test
    void levelsRendersATableAndPassesTheFilterThrough() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Worker", "INFO", "DEBUG", true, "jmx", "INC-1", "FOR",
                        Instant.now().plus(30, ChronoUnit.MINUTES).toString()),
                new LoggerInfoData("com.acme.Idle", null, "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.levels("com.acme", false)));

        assertEquals(List.of("com.acme"), mbean.listLoggersFilters);
        String text = output();
        assertTrue(text.contains("LOGGER"));
        assertTrue(text.contains("com.acme.Worker"));
        assertTrue(text.contains("FOR, reverts"));
        assertTrue(text.contains("\"INC-1\""));
        assertTrue(text.contains("com.acme.Idle"));
        assertTrue(text.contains("—"), "unset baseline / no override should render as a dash");
    }

    @Test
    void levelsPassesAGlobFilterThroughVerbatim() {
        // The CLI does no name matching or grammar validation of its own — a
        // pattern must reach listLoggers unchanged for the server-side
        // NameFilter to apply and validate it. (The fake MXBean
        // prefix-matches, so it returns nothing here; what matters is the
        // filter string arriving untouched.)
        assertEquals(CliError.OK, run(Commands.levels("*.infinispan", false)));

        assertEquals(List.of("*.infinispan"), mbean.listLoggersFilters);
    }

    @Test
    void levelsEmptyResultsGiveAFriendlyLineNotAnError() {
        assertEquals(CliError.OK, run(Commands.levels("no.such", false)));
        assertTrue(output().contains("No loggers match 'no.such'."));

        captured.reset();
        assertEquals(CliError.OK, run(Commands.levels(null, false)));
        assertTrue(output().contains("No loggers known yet."));
    }

    @Test
    void levelsJsonEmitsAnArray() {
        mbean.loggers = List.of(new LoggerInfoData("a", "INFO", "INFO", false, null, null, null, null));
        run(Commands.levels(null, true));
        assertTrue(output().strip().startsWith("[{"));
    }

    @Test
    void statusShowsOnlyActiveOverridesSortedByRevertTime() {
        String soon = Instant.now().plus(5, ChronoUnit.MINUTES).toString();
        String later = Instant.now().plus(3, ChronoUnit.HOURS).toString();
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Quiet", "INFO", "INFO", false, null, null, null, null),
                new LoggerInfoData("com.acme.Later", "INFO", "DEBUG", true, "jmx", null, "FOR", later),
                new LoggerInfoData("com.acme.Sticky", "INFO", "WARN", true, "jmx", "muted", "STICKY", null),
                new LoggerInfoData("com.acme.Soon", "INFO", "TRACE", true, "jmx", null, "FOR", soon));

        run(Commands.status(false));

        String text = output();
        assertFalse(text.contains("com.acme.Quiet"), "inactive loggers must not appear in status");
        int soonAt = text.indexOf("com.acme.Soon");
        int laterAt = text.indexOf("com.acme.Later");
        int stickyAt = text.indexOf("com.acme.Sticky");
        assertTrue(soonAt < laterAt && laterAt < stickyAt, "sooner reverts first; sticky last:\n" + text);
        assertTrue(text.contains("until reset"));
    }

    @Test
    void statusWithNothingActivePrintsTheEmptyStateLine() {
        mbean.loggers = List.of(new LoggerInfoData("a", "INFO", "INFO", false, null, null, null, null));
        assertEquals(CliError.OK, run(Commands.status(false)));
        assertEquals("No active overrides.", output().strip());
    }

    @Test
    void status_alsoRendersActiveHandlerOverrides() {
        mbean.handlerOverrides = List.of(new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", "INC-1", Instant.now().toString(), "jmx", "STICKY", null));

        assertEquals(CliError.OK, run(Commands.status(false)));

        String text = output();
        assertTrue(text.contains("HANDLER"));
        assertTrue(text.contains("CONSOLE"));
        assertTrue(text.contains("TRACE"));
        assertTrue(text.contains("until reset"));
    }

    @Test
    void status_handlerOverridesOnly_stillPrintsWithNoLoggerTable() {
        mbean.handlerOverrides = List.of(new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", null, Instant.now().toString(), "jmx", "SESSION", null));

        assertEquals(CliError.OK, run(Commands.status(false)));

        assertFalse(output().contains("No active overrides."));
    }

    @Test
    void status_jsonWrapsLoggersAndHandlerOverrides() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Loud", "INFO", "DEBUG", true, "jmx", null, "STICKY", null));
        mbean.handlerOverrides = List.of(new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", null, Instant.now().toString(), "jmx", "STICKY", null));

        run(Commands.status(true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"loggers\":[{"), text);
        assertTrue(text.contains("\"handlerOverrides\":[{"), text);
    }

    // --- doctor (doc/specs/doctor.md) ------------------------------------------------------------

    @Test
    void doctor_rendersEachFindingWithASeverityBracketAndASummaryLine() {
        mbean.findings = List.of(
                new DoctorFindingData("handler.unbounded-growth", "WARNING", "FILE",
                        "FILE has no size cap — writes are unbounded.", null,
                        "configure a size-based rotation policy on FILE.", null),
                new DoctorFindingData("handler.autoflush", "OK", "handlers",
                        "no autoflush handlers found on a busy path.", null, null, null));

        assertEquals(CliError.OK, run(Commands.doctor(false)));

        String text = output();
        assertTrue(text.contains("[WARN]"), text);
        assertTrue(text.contains("FILE has no size cap"), text);
        assertTrue(text.contains("suggested: configure a size-based rotation policy on FILE."), text);
        assertTrue(text.contains("[OK]"), text);
        assertTrue(text.contains("2 checks run — 0 critical, 1 warning, 0 info, 1 clean."), text);
    }

    @Test
    void doctor_noFindings_printsANoteInsteadOfAnEmptySummary() {
        mbean.findings = List.of();

        assertEquals(CliError.OK, run(Commands.doctor(false)));

        assertTrue(output().contains("No checks could run"));
    }

    @Test
    void doctor_json_wrapsFindingsAndChecksRun() {
        mbean.findings = List.of(new DoctorFindingData("logger.verbosity-left-on", "WARNING", "ROOT",
                "root logger is at DEBUG.", null, null, null));

        run(Commands.doctor(true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"findings\":[{"), text);
        assertTrue(text.contains("\"checksRun\":1"), text);
    }

    @Test
    void handlers_rendersATableWithLevelSinkTargetAndOverride() {
        mbean.handlerCatalog = List.of(
                new org.logaperture.control.jmx.HandlerInfoData(
                        "ALL_HANDLERS", null, false, null, null, false, null, null, null, null, null),
                new org.logaperture.control.jmx.HandlerInfoData(
                        "CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null, null),
                new org.logaperture.control.jmx.HandlerInfoData(
                        "FILE", "DEBUG", true, "/opt/server.log", Boolean.TRUE, true, "DEBUG", "FIXED", "FOR",
                        Instant.now().plus(30, ChronoUnit.MINUTES).toString(), null));

        assertEquals(CliError.OK, run(Commands.handlers(false)));

        String text = output();
        assertTrue(text.contains("HANDLER") && text.contains("LEVEL") && text.contains("TARGET"), text);
        assertTrue(text.contains("ALL_HANDLERS"), text);
        assertTrue(text.contains("CONSOLE") && text.contains("INFO"), text);
        assertTrue(text.contains("FILE") && text.contains("/opt/server.log"), text);
        assertTrue(text.contains("DEBUG (FOR"), "the override cell shows level + tier: " + text);
    }

    @Test
    void handlers_nothingToList_printsANote() {
        mbean.handlerCatalog = List.of();
        assertEquals(CliError.OK, run(Commands.handlers(false)));
        assertTrue(output().contains("no level of their own"), output());
    }

    @Test
    void handlers_json_wrapsTheCatalog() {
        mbean.handlerCatalog = List.of(new org.logaperture.control.jmx.HandlerInfoData(
                "CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null, null));

        run(Commands.handlers(true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"handlers\":[{"), text);
        assertTrue(text.contains("\"ref\":\"CONSOLE\""), text);
    }

    // --- top (doc/specs/top.md) ------------------------------------------------------------------

    @Test
    void top_rendersRatesProjectionsAndStackTracePercentage() {
        // 20 GiB over 10h => 2.0 GB/h -- comfortably clear of the GB/MB
        // rounding boundary so this test isn't sensitive to how many
        // milliseconds elapse between computing startedAt and rendering.
        String startedAt = Instant.now().minus(10, ChronoUnit.HOURS).toString();
        mbean.topReport = new TopReportData(List.of(
                new LoggerByteCountData("org.apache.http", 20L * 1024 * 1024 * 1024, 0L, null),
                new LoggerByteCountData("com.acme.Half", 100L, 50L, null)),
                startedAt, 2);

        assertEquals(CliError.OK, run(Commands.top(0, false)));

        String text = output();
        assertTrue(text.contains("org.apache.http"), text);
        assertTrue(text.contains("2.0 GB/h"), text);
        assertTrue(text.contains("(48.0 GB/day)"), text);
        assertTrue(text.contains("0% stack traces"), text);
        assertTrue(text.contains("50% stack traces"), text);
        assertTrue(text.contains("2 loggers tracked."), text);
        assertEquals(List.of(0), mbean.topLoggersLimits);
    }

    @Test
    void top_noTrackedLoggers_printsANoteInsteadOfATable() {
        mbean.topReport = new TopReportData(List.of(), null, 0);

        assertEquals(CliError.OK, run(Commands.top(10, false)));

        assertTrue(output().contains("No byte-volume measurements available yet."));
    }

    @Test
    void top_measurementStartedAtNull_printsANoteEvenIfLoggersIsNonEmpty() {
        // TopReport's own contract allows this combination (doc/specs/top.md);
        // no shipped container produces it today, but Commands.top() must not
        // NPE on Instant.parse(null) if a future one does.
        mbean.topReport = new TopReportData(
                List.of(new LoggerByteCountData("a", 10L, 0L, null)), null, 1);

        assertEquals(CliError.OK, run(Commands.top(0, false)));

        assertTrue(output().contains("No byte-volume measurements available yet."));
    }

    @Test
    void top_trackedCountReflectsTheTrueTotal_notTheLimitTruncatedRowCount() {
        // --limit truncated "loggers" to 1 row, but 200 loggers are actually
        // tracked -- the footer must report the true count, not loggers.size().
        mbean.topReport = new TopReportData(
                List.of(new LoggerByteCountData("a", 10L, 0L, null)), Instant.now().toString(), 200);

        run(Commands.top(1, false));

        assertTrue(output().contains("200 loggers tracked."), output());
    }

    @Test
    void top_json_wrapsLoggersAndPassesTheLimitThrough() {
        mbean.topReport = new TopReportData(
                List.of(new LoggerByteCountData("a", 10L, 0L, null)), Instant.now().toString(), 1);

        run(Commands.top(5, true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"loggers\":[{"), text);
        assertEquals(List.of(5), mbean.topLoggersLimits);
    }

    @Test
    void top_showsContextPrefixOnlyWhenResultSpansMultipleContexts() {
        mbean.topReport = new TopReportData(List.of(
                new LoggerByteCountData("a", 10L, 0L, "system"),
                new LoggerByteCountData("b", 10L, 0L, "myapp.war")),
                Instant.now().toString(), 2);

        run(Commands.top(0, false));

        String text = output();
        assertTrue(text.contains("[system]"), text);
        assertTrue(text.contains("[myapp.war]"), text);
    }

    @Test
    void setLevelForwardsEveryArgumentAndPrintsARevertTime() {
        String expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES).toString();
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "DEBUG", null, "INC-1", Instant.now().toString(), "jmx", "FOR", expiresAt), List.of());

        run(Commands.setLevel("com.acme", "DEBUG", "INC-1", "FOR", 1800L, false, false));

        Object[] call = mbean.setLevelCalls.get(0);
        assertEquals("com.acme", call[0]);
        assertEquals("DEBUG", call[1]);
        assertEquals("INC-1", call[2]);
        assertEquals("FOR", call[3]);
        assertEquals(1800L, call[4]);
        assertEquals(true, call[5], "an exact-name target is always confirmed, regardless of --yes");

        String text = output();
        assertTrue(text.contains("com.acme → DEBUG"));
        assertTrue(text.contains("FOR, reverts"));
        assertTrue(text.contains("local —"));
    }

    @Test
    void setLevelStickyAndSessionConfirmationsReadPlainly() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "WARN", null, null, Instant.now().toString(), "jmx", "STICKY", null), List.of());
        run(Commands.setLevel("com.acme", "WARN", null, "STICKY", 0L, false, false));
        assertTrue(output().contains("(STICKY — until reset)"));

        captured.reset();
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "WARN", null, null, Instant.now().toString(), "jmx", "SESSION", null), List.of());
        run(Commands.setLevel("com.acme", "WARN", null, "SESSION", 0L, false, false));
        assertTrue(output().contains("(SESSION — until the JVM stops)"));
    }

    @Test
    void setLevelJsonEmitsTheOverrideObject() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "DEBUG", null, null, "2026-08-25T00:00:00Z", "jmx", "SESSION", null), List.of());
        run(Commands.setLevel("com.acme", "DEBUG", null, "SESSION", 0L, false, true));
        assertEquals(
                "{\"overrides\":[{\"loggerName\":\"com.acme\",\"level\":\"DEBUG\",\"originPattern\":null,"
                        + "\"reason\":null,\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\","
                        + "\"tier\":\"SESSION\",\"expiresAt\":null}],\"warnings\":[]}",
                output().strip());
    }

    @Test
    void setLevel_oneBlockingHandler_printsTheActionableWarning() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "TRACE", null, null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of(new HandlerFloorData("CONSOLE", "INFO")));

        run(Commands.setLevel("com.acme", "TRACE", null, "SESSION", 0L, false, false));

        String text = output();
        assertTrue(text.contains("WARN: handler CONSOLE is at INFO"));
        assertTrue(text.contains("logctl handler CONSOLE TRACE"));
    }

    @Test
    void setLevel_multipleBlockingHandlers_printsOneCommandEach() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "TRACE", null, null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of(new HandlerFloorData("CONSOLE", "INFO"), new HandlerFloorData("FILE", "DEBUG")));

        run(Commands.setLevel("com.acme", "TRACE", null, "SESSION", 0L, false, false));

        String text = output();
        assertTrue(text.contains("2 handlers"));
        assertTrue(text.contains("logctl handler CONSOLE TRACE"));
        assertTrue(text.contains("logctl handler FILE TRACE"));
    }

    @Test
    void setLevel_noBlockingHandlers_printsNoWarning() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "DEBUG", null, null, Instant.now().toString(), "jmx", "SESSION", null), List.of());

        run(Commands.setLevel("com.acme", "DEBUG", null, "SESSION", 0L, false, false));

        assertFalse(output().contains("WARN"));
    }

    @Test
    void setLevelJson_withBlockingHandlers_emitsWarningsArray() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "TRACE", null, null, "2026-08-25T00:00:00Z", "jmx", "SESSION", null),
                List.of(new HandlerFloorData("CONSOLE", "INFO")));

        run(Commands.setLevel("com.acme", "TRACE", null, "SESSION", 0L, false, true));

        assertTrue(output().contains("\"warnings\":[{\"handlerRef\":\"CONSOLE\",\"currentLevel\":\"INFO\"}]"));
    }

    // --- setLevel on a pattern: standing rules (doc/specs/pattern-level-targeting.md) ---------

    @Test
    void setLevel_pattern_confirmedViaYesFlag_appliesWithoutPrompting() {
        mbean.loggers = List.of(new LoggerInfoData("org.apache.Worker", "INFO", "INFO", false, null, null, null, null));
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "org.apache.Worker", "DEBUG", "org.apache.*", null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of());

        int exit = run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, true, false));

        assertEquals(CliError.OK, exit);
        Object[] call = mbean.setLevelCalls.get(0);
        assertEquals(true, call[5], "confirmed");
        assertFalse(output().contains("Apply this standing rule"), "--yes must skip the prompt entirely");
        assertTrue(output().contains("org.apache.Worker → DEBUG"), output());
    }

    @Test
    void setLevel_pattern_interactiveTypedY_previewsThenApplies() {
        mbean.loggers = List.of(new LoggerInfoData("org.apache.Worker", "INFO", "INFO", false, null, null, null, null));
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "org.apache.Worker", "DEBUG", "org.apache.*", null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of());

        int exit = run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, false, false), "y", true);

        assertEquals(CliError.OK, exit);
        String text = output();
        assertTrue(text.contains("Apply this standing rule"), text);
        assertTrue(text.contains("org.apache.Worker"), "the preview lists the current match: " + text);
        assertTrue(mbean.setLevelCalls.get(0)[5].equals(true), "a typed 'y' confirms exactly like --yes");
    }

    @Test
    void setLevel_pattern_interactiveTypedN_declinesWithoutCallingSetLevel() {
        int exit = run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, false, false), "n", true);

        assertEquals(CliError.OK, exit);
        assertTrue(mbean.setLevelCalls.isEmpty(), "declining must never reach the server");
        assertTrue(output().contains("Not applied."), output());
    }

    @Test
    void setLevel_pattern_nonInteractiveWithoutYes_isAUsageErrorNamingTheFlag() {
        // Decision #4: fail fast rather than block forever on a read from a
        // stdin nothing will ever write to. This is also the *only* message a
        // non-interactive caller ever sees -- the full preview
        // (printPatternPreview) never runs here -- so it must explain the
        // standing-rule consequence itself, not just name the escape hatch
        // (#46).
        CliError error = org.junit.jupiter.api.Assertions.assertThrows(CliError.class,
                () -> run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, false, false)));

        assertEquals(CliError.USAGE, error.exitCode());
        String message = error.getMessage();
        assertTrue(message.contains("--yes"), message);
        assertTrue(message.contains("standing rule"), message);
        assertTrue(message.contains("DEBUG"), message);
        assertTrue(message.contains("logctl reset logger org.apache.*"), message);
        assertTrue(mbean.setLevelCalls.isEmpty());
    }

    @Test
    void setLevel_pattern_zeroCurrentMatches_stillCreatesTheStandingRule() {
        // The rule is created even though nothing is mutated right now --
        // it stands ready for the sweep (doc/specs/pattern-level-targeting.md
        // "Sweep integration").
        mbean.setLevelResult = new SetLevelResultData(List.of(), List.of());

        int exit = run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, true, false));

        assertEquals(CliError.OK, exit);
        assertTrue(output().contains("Standing rule created"), output());
    }

    @Test
    void setLevel_pattern_multipleMatches_printsOneLinePerLogger() {
        mbean.setLevelResult = new SetLevelResultData(List.of(
                new LevelOverrideData("org.apache.A", "DEBUG", "org.apache.*", null, Instant.now().toString(),
                        "jmx", "SESSION", null),
                new LevelOverrideData("org.apache.B", "DEBUG", "org.apache.*", null, Instant.now().toString(),
                        "jmx", "SESSION", null)),
                List.of());

        run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, true, false));

        String text = output();
        assertTrue(text.contains("org.apache.A → DEBUG"), text);
        assertTrue(text.contains("org.apache.B → DEBUG"), text);
    }

    @Test
    void setLevel_pattern_json_wrapsOverridesAsAList() {
        mbean.setLevelResult = new SetLevelResultData(List.of(
                new LevelOverrideData("org.apache.A", "DEBUG", "org.apache.*", null, "2026-08-25T00:00:00Z",
                        "jmx", "SESSION", null)),
                List.of());

        run(Commands.setLevel("org.apache.*", "DEBUG", null, "SESSION", 0L, true, true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"overrides\":[{"), text);
        assertTrue(text.contains("\"originPattern\":\"org.apache.*\""), text);
    }

    // --- reset logger on a pattern (doc/specs/reset-command-surface.md) ----------------------

    @Test
    void resetLogger_pattern_revertsCurrentMatchesAndRetiresTheRule() {
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.A", "INFO", "DEBUG", true, "jmx", null, "STICKY", null),
                new LoggerInfoData("org.apache.B", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", null, true, false)));

        assertEquals(1, mbean.resetLevelCalls.size());
        assertEquals("org.apache.*", mbean.resetLevelCalls.get(0)[0]);
        assertEquals(true, mbean.resetLevelCalls.get(0)[1]);
        String text = output();
        assertTrue(text.contains("org.apache.A → INFO (baseline)"), text);
        assertFalse(text.contains("org.apache.B"), "never-overridden matches aren't reported as reverted: " + text);
        assertTrue(text.contains("Standing rule 'org.apache.*' retired."), text);
    }

    @Test
    void resetLogger_pattern_stickyMatchLeftUntouchedByDefault() {
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.A", "INFO", "DEBUG", true, "jmx", null, "STICKY", null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", null, false, false)));

        String text = output();
        assertTrue(text.contains("org.apache.A — left untouched (STICKY; use --include-sticky to include it)."),
                text);
    }

    @Test
    void resetLogger_pattern_ruleTrackedButNothingCurrentlyOverridden_stillReportsRuleRetired() {
        mbean.loggers = List.of(new LoggerInfoData("org.apache.A", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", null, true, false)));

        String text = output();
        assertTrue(text.contains("Standing rule 'org.apache.*' retired -- it had no currently-matched logger "
                + "to revert."), text);
    }

    @Test
    void resetLogger_pattern_noRuleWasEverTracked_reportsNothingOverridden() {
        // Code-review finding: the old CLI reconstructed "retired" from
        // whether anything was overridden before the call, so it printed
        // "Standing rule retired" even when no rule existed under that
        // exact pattern at all. The server now reports this directly, via
        // an empty outcome, not a special "no rule tracked" message.
        mbean.patternRuleTrackedForReset = false;
        mbean.loggers = List.of(new LoggerInfoData("org.apache.A", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", null, true, false)));

        String text = output();
        assertTrue(text.contains("nothing was overridden"), text);
        assertFalse(text.contains("retired"), text);
    }

    @Test
    void resetLogger_pattern_json_reportsRevertedNamesAndRetiredPattern() {
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.A", "INFO", "DEBUG", true, "jmx", null, "STICKY", null));

        run(Commands.resetLogger("org.apache.*", null, true, true));

        String text = output().strip();
        assertTrue(text.contains("\"reverted\":[\"org.apache.A\"]"), text);
        assertTrue(text.contains("\"retiredPatterns\":[\"org.apache.*\"]"), text);
    }

    @Test
    void resetLogger_pattern_json_noRuleTracked_reportsEmptyOutcome() {
        mbean.patternRuleTrackedForReset = false;

        run(Commands.resetLogger("org.apache.*", null, true, true));

        String text = output().strip();
        assertTrue(text.contains("\"reverted\":[]"), text);
        assertTrue(text.contains("\"retiredPatterns\":[]"), text);
    }

    // --- handler (doc/specs/handler-floor-control.md) -----------------------------------------

    @Test
    void handlerSetForwardsEveryArgumentAndPrintsAConfirmation() {
        mbean.setHandlerLevelResult = new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", "INC-1", Instant.now().toString(), "jmx", "SESSION", null);

        run(Commands.setHandlerLevel("CONSOLE", "TRACE", "INC-1", "SESSION", 0L, false));

        Object[] call = mbean.setHandlerLevelCalls.get(0);
        assertEquals("CONSOLE", call[0]);
        assertEquals("TRACE", call[1]);
        assertEquals("INC-1", call[2]);
        assertEquals("SESSION", call[3]);
        assertEquals("handler CONSOLE → TRACE   (SESSION — until the JVM stops)", output().strip());
    }

    @Test
    void handlerSetJsonEmitsTheOverrideObject() {
        mbean.setHandlerLevelResult = new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", null, "2026-08-25T00:00:00Z", "jmx", "STICKY", null);

        run(Commands.setHandlerLevel("CONSOLE", "TRACE", null, "STICKY", 0L, true));

        assertEquals(
                "{\"handlerRef\":\"CONSOLE\",\"level\":\"TRACE\",\"mode\":\"FIXED\",\"reason\":null,"
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"STICKY\","
                        + "\"expiresAt\":null}",
                output().strip());
    }

    @Test
    void handlerSet_adapterHasNoHandlerLevels_printsTheNoOpNote() {
        mbean.setHandlerLevelResult = null; // Logback / none: no level of its own

        assertEquals(CliError.OK, run(Commands.setHandlerLevel("CONSOLE", "TRACE", null, "SESSION", 0L, false)));
        assertTrue(output().contains("nothing to change"));
    }

    @Test
    void handlerSetJson_adapterHasNoHandlerLevels_emitsAChangedFalseObject() {
        mbean.setHandlerLevelResult = null;

        run(Commands.setHandlerLevel("CONSOLE", "TRACE", null, "SESSION", 0L, true));

        assertEquals("{\"handlerRef\":\"CONSOLE\",\"changed\":false}", output().strip());
    }

    // --- handler AUTO (doc/specs/handler-floor-control.md "AUTO handler level", issue #20) ----

    @Test
    void handlerAutoForwardsEveryArgumentAndPrintsAConfirmation() {
        mbean.setHandlerAutoResult = new HandlerLevelOverrideData(
                "CONSOLE", "DEBUG", "AUTO", "INC-1", Instant.now().toString(), "jmx", "SESSION", null);

        run(Commands.setHandlerAuto("CONSOLE", "INC-1", "SESSION", 0L, false));

        Object[] call = mbean.setHandlerAutoCalls.get(0);
        assertEquals("CONSOLE", call[0]);
        assertEquals("INC-1", call[1]);
        assertEquals("SESSION", call[2]);
        assertEquals("handler CONSOLE → AUTO, currently DEBUG   (SESSION — until the JVM stops)", output().strip());
    }

    @Test
    void handlerAutoJsonEmitsTheOverrideObjectWithMode() {
        mbean.setHandlerAutoResult = new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "AUTO", null, "2026-08-25T00:00:00Z", "jmx", "STICKY", null);

        run(Commands.setHandlerAuto("CONSOLE", null, "STICKY", 0L, true));

        assertEquals(
                "{\"handlerRef\":\"CONSOLE\",\"level\":\"TRACE\",\"mode\":\"AUTO\",\"reason\":null,"
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"STICKY\","
                        + "\"expiresAt\":null}",
                output().strip());
    }

    @Test
    void handlerAuto_nothingToTrack_printsTheNoOpNote() {
        mbean.setHandlerAutoResult = null; // no level of its own, or no active floor and no baseline yet

        assertEquals(CliError.OK, run(Commands.setHandlerAuto("CONSOLE", null, "SESSION", 0L, false)));
        assertTrue(output().contains("nothing to change"));
    }

    @Test
    void status_rendersAModeColumnDistinguishingAutoFromFixed() {
        mbean.handlerOverrides = List.of(
                new HandlerLevelOverrideData("CONSOLE", "DEBUG", "AUTO", null, Instant.now().toString(), "jmx",
                        "STICKY", null),
                new HandlerLevelOverrideData("FILE", "WARN", "FIXED", null, Instant.now().toString(), "jmx",
                        "STICKY", null));

        assertEquals(CliError.OK, run(Commands.status(false)));

        String text = output();
        assertTrue(text.contains("MODE"), text);
        assertTrue(text.contains("AUTO"), text);
        assertTrue(text.contains("FIXED"), text);
    }

    @Test
    void handlers_catalogRowInAutoMode_showsTheTrackedLevelPrefixed() {
        mbean.handlerCatalog = List.of(new org.logaperture.control.jmx.HandlerInfoData(
                "CONSOLE", "DEBUG", false, null, Boolean.TRUE, true, "DEBUG", "AUTO", "STICKY", null, null));

        assertEquals(CliError.OK, run(Commands.handlers(false)));

        assertTrue(output().contains("AUTO → DEBUG"), output());
    }

    @Test
    void handlerResetCallsResetHandler() {
        assertEquals(CliError.OK, run(Commands.resetHandler("CONSOLE", false, false)));
        assertEquals(1, mbean.resetHandlerCalls.size());
        assertEquals("CONSOLE", mbean.resetHandlerCalls.get(0)[0]);
        assertEquals(false, mbean.resetHandlerCalls.get(0)[1]);
        assertEquals("handler CONSOLE → reset to its previous level.", output().strip());
    }

    @Test
    void handlerResetJsonEmitsAnObject() {
        assertEquals(CliError.OK, run(Commands.resetHandler("CONSOLE", false, true)));
        assertEquals(
                "{\"reverted\":[\"CONSOLE\"],\"retiredPatterns\":[],\"excludedFrom\":[],\"skippedSticky\":[]}",
                output().strip());
    }

    @Test
    void resetLogger_exactName_callsResetLevelThenReportsTheRestoredLevel() {
        mbean.loggers = List.of(new LoggerInfoData("com.acme", "INFO", "INFO", false, null, null, null, null));
        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme", null, false, false)));
        assertEquals("com.acme", mbean.resetLevelCalls.get(0)[0]);
        assertEquals("com.acme — nothing was overridden.", output().strip());
    }

    @Test
    void resetLogger_exactName_overriddenLogger_reportsTheRestoredLevel() {
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme", "INFO", "DEBUG", true, "jmx", null, "SESSION", null)));
        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme", null, false, false)));
        assertEquals("com.acme → INFO (baseline)", output().strip());
    }

    @Test
    void resetLogger_ofAnUnknownLoggerSaysNothingWasOverridden() {
        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme.ghost", null, false, false)));
        assertEquals("com.acme.ghost — nothing was overridden.", output().strip());
    }

    @Test
    void resetLogger_ofAnOverriddenButNotYetInstantiatedLoggerReportsTheRevertNotNothing() {
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme.Known", null, "DEBUG", true, "jmx", "resumed", "SESSION", null)));
        mbean.forgetOnReset.add("com.acme.Known");

        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme.Known", null, false, false)));
        assertEquals("com.acme.Known", mbean.resetLevelCalls.get(0)[0]);
        assertEquals("com.acme.Known → baseline (not yet instantiated, so no level to show)", output().strip());
    }

    @Test
    void resetLogger_stickyExactName_leftUntouchedByDefault() {
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme.Known", null, "DEBUG", true, "jmx", null, "STICKY", null)));

        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme.Known", null, false, false)));
        assertEquals("com.acme.Known — left untouched (STICKY; use --include-sticky to include it).",
                output().strip());
    }

    @Test
    void resetLogger_jsonEmitsAnObjectEvenWhenNoPostResetLoggerRemains() {
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme.Known", null, "DEBUG", true, "jmx", null, "SESSION", null)));
        mbean.forgetOnReset.add("com.acme.Known");

        run(Commands.resetLogger("com.acme.Known", null, false, true));
        assertEquals(
                "{\"reverted\":[\"com.acme.Known\"],\"retiredPatterns\":[],\"excludedFrom\":[],\"skippedSticky\":[]}",
                output().strip());
    }

    @Test
    void resetAllLoggers_printsRevertedSummary() {
        assertEquals(CliError.OK, run(Commands.resetAllLoggers(false, false)));
        assertEquals(1, mbean.resetAllLoggersCalls);
        assertEquals("Reverted 0 logger override(s).", output().strip());
    }

    @Test
    void resetAllHandlers_printsRevertedSummary() {
        assertEquals(CliError.OK, run(Commands.resetAllHandlers(false, false)));
        assertEquals(1, mbean.resetAllHandlersCalls);
        assertEquals("Reverted 0 handler override(s).", output().strip());
    }

    @Test
    void resetAll_printsRevertedSummaryWithNoNounPrefix() {
        assertEquals(CliError.OK, run(Commands.resetAll(false, false)));
        assertEquals(1, mbean.resetAllCalls);
        assertEquals("Reverted 0 override(s).", output().strip());
    }

    @Test
    void resetAllJsonEmitsTheOutcome() {
        assertEquals(CliError.OK, run(Commands.resetAll(true, true)));
        assertEquals(
                "{\"reverted\":[],\"retiredPatterns\":[],\"excludedFrom\":[],\"skippedSticky\":[]}",
                output().strip());
    }

    // --- CONTEXT column (doc/specs/wildfly-support.md, Slice 3) ------------------------------------

    @Test
    void levels_showsContextColumn_onlyWhenTheResultSpansMoreThanOneContext() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.shared.Util", "INFO", "DEBUG", true, "jmx", null, "STICKY", null, "system"),
                new LoggerInfoData("com.myapp.Svc", null, "INFO", false, null, null, null, null, "myapp.war"));

        assertEquals(CliError.OK, run(Commands.levels(null, false)));

        String text = output();
        assertTrue(text.contains("CONTEXT"), "column header present");
        assertTrue(text.contains("system"));
        assertTrue(text.contains("myapp.war"));
    }

    @Test
    void levels_hidesContextColumn_whenEveryRowSharesOneContext() {
        mbean.loggers = List.of(
                new LoggerInfoData("a", "INFO", "DEBUG", true, "jmx", null, "STICKY", null, "system"),
                new LoggerInfoData("b", "INFO", "INFO", false, null, null, null, null, "system"));

        assertEquals(CliError.OK, run(Commands.levels(null, false)));

        assertFalse(output().contains("CONTEXT"), "a single-context server never shows the column");
    }

    @Test
    void status_showsContextColumn_whenTheServerHasMoreThanOneContext() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.shared.Util", "INFO", "DEBUG", true, "jmx", "why", "STICKY", null, "system"),
                new LoggerInfoData("com.myapp.Idle", null, "INFO", false, null, null, null, null, "myapp.war"));

        assertEquals(CliError.OK, run(Commands.status(false)));

        String text = output();
        assertTrue(text.contains("CONTEXT"));
        assertTrue(text.contains("system"));
    }

    // --- env (doc/specs/environment-report.md) -----------------------------------------------

    @Test
    void env_rendersAgentCliJavaAndOsAsALabelValueBlock() {
        mbean.environmentReport = new org.logaperture.control.jmx.EnvironmentReportData(
                "0.1.0-alpha.2", "21.0.4", "Eclipse Adoptium", "Linux", "6.10.3", "x86_64",
                null, null, null, null, null, null);

        assertEquals(CliError.OK, run(Commands.env(false)));

        String text = output();
        assertTrue(text.contains("LogAperture agent") && text.contains("0.1.0-alpha.2"), text);
        assertTrue(text.contains("(logctl "), "logctl's own version is stitched in alongside the agent's: " + text);
        assertTrue(text.contains("21.0.4") && text.contains("Eclipse Adoptium"), text);
        assertTrue(text.contains("Linux") && text.contains("6.10.3") && text.contains("x86_64"), text);
        assertFalse(text.contains("Logging backend"), "no backend detected -- the line is left out entirely");
        assertFalse(text.contains("Framework/container"), "no container detected -- the line is left out entirely");
        assertTrue(text.contains("Diagnostics level"), "shown either way, per Decision #5: " + text);
        assertTrue(text.contains("State file") && text.contains("—"), "shown either way, dash if absent: " + text);
    }

    @Test
    void env_backendAndContainerDetected_showBothLinesWithVersions() {
        mbean.environmentReport = new org.logaperture.control.jmx.EnvironmentReportData(
                "0.1.0-alpha.2", "21.0.4", "Eclipse Adoptium", "Linux", "6.10.3", "x86_64",
                "JBoss LogManager", "3.1.1.Final", "WildFly", "34.0.1.Final", "INFO",
                "/home/alice/.logaperture/instances/abc123-app.state.yaml");

        assertEquals(CliError.OK, run(Commands.env(false)));

        String text = output();
        assertTrue(text.contains("Logging backend") && text.contains("JBoss LogManager 3.1.1.Final"), text);
        assertTrue(text.contains("Framework/container") && text.contains("WildFly 34.0.1.Final"), text);
        assertTrue(text.contains("Diagnostics level") && text.contains("INFO"), text);
        assertTrue(text.contains("State file") && text.contains("/home/alice/.logaperture/instances/abc123-app.state.yaml"),
                text);
    }

    @Test
    void env_json_isAFlatObjectIncludingLogctlsOwnVersion() {
        mbean.environmentReport = new org.logaperture.control.jmx.EnvironmentReportData(
                "0.1.0-alpha.2", "21.0.4", "Eclipse Adoptium", "Linux", "6.10.3", "x86_64",
                null, null, null, null, null, null);

        run(Commands.env(true));

        String text = output().strip();
        assertTrue(text.contains("\"agentVersion\":\"0.1.0-alpha.2\""), text);
        assertTrue(text.contains("\"cliVersion\":"), "logctl's own version is a JSON field too: " + text);
        assertTrue(text.contains("\"backendName\":null"), text);
        assertTrue(text.contains("\"stateFilePath\":null"), text);
    }
}
