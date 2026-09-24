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
import org.logaperture.control.jmx.SquelchedLoggerData;
import org.logaperture.control.jmx.StormData;
import org.logaperture.control.jmx.StormReportData;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Every command but a pattern-targeted {@code setLogger} ignores {@code in}/{@code interactive}. */
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
    void listLoggersRendersATableAndPassesTheFilterThrough() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Worker", "INFO", "DEBUG", true, "jmx", "INC-1", "FOR",
                        Instant.now().plus(30, ChronoUnit.MINUTES).toString()),
                new LoggerInfoData("com.acme.Idle", null, "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.listLoggers("com.acme", true, false)));

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
    void listLoggersPassesAGlobFilterThroughVerbatim() {
        // The CLI does no name matching or grammar validation of its own — a
        // pattern must reach listLoggers unchanged for the server-side
        // NameFilter to apply and validate it. (The fake MXBean
        // prefix-matches, so it returns nothing here; what matters is the
        // filter string arriving untouched.)
        assertEquals(CliError.OK, run(Commands.listLoggers("*.infinispan", true, false)));

        assertEquals(List.of("*.infinispan"), mbean.listLoggersFilters);
    }

    @Test
    void listLoggersEmptyResultsGiveAFriendlyLineNotAnError() {
        assertEquals(CliError.OK, run(Commands.listLoggers("no.such", true, false)));
        assertTrue(output().contains("No loggers match 'no.such'."));

        captured.reset();
        assertEquals(CliError.OK, run(Commands.listLoggers(null, true, false)));
        assertTrue(output().contains("No loggers known yet."));
    }

    @Test
    void listLoggersJsonEmitsAnArray() {
        mbean.loggers = List.of(new LoggerInfoData("a", "INFO", "INFO", false, null, null, null, null));
        run(Commands.listLoggers(null, true, true));
        assertTrue(output().strip().startsWith("[{"));
    }

    @Test
    void listLoggersDefaultsToOverridesOnly() {
        // doc/specs/list-command-surface.md Decision #2 -- without --show-all,
        // a row with no active override is dropped before rendering.
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Loud", "INFO", "DEBUG", true, "jmx", null, "FOR", null),
                new LoggerInfoData("com.acme.Quiet", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.listLoggers(null, false, false)));

        String text = output();
        assertTrue(text.contains("com.acme.Loud"));
        assertFalse(text.contains("com.acme.Quiet"));
    }

    @Test
    void listLoggersShowAllRestoresTheFullCatalog() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Loud", "INFO", "DEBUG", true, "jmx", null, "FOR", null),
                new LoggerInfoData("com.acme.Quiet", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.listLoggers(null, true, false)));

        String text = output();
        assertTrue(text.contains("com.acme.Loud"));
        assertTrue(text.contains("com.acme.Quiet"));
    }

    @Test
    void listLoggersOverridesOnlyEmptyMessageDiffersFromShowAll() {
        mbean.loggers = List.of(new LoggerInfoData("com.acme.Quiet", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.listLoggers(null, false, false)));
        assertTrue(output().contains("No loggers have an active override."));

        captured.reset();
        mbean.loggers = List.of();
        assertEquals(CliError.OK, run(Commands.listLoggers(null, true, false)));
        assertTrue(output().contains("No loggers known yet."));
    }

    @Test
    void listLoggersFilterMatchingRealLoggersWithNoOverrideIsNotReportedAsNoMatch() {
        // A code-review finding against an earlier version: the filter matched
        // real loggers under com.acme, just none with an active override --
        // that must not be reported as "No loggers match 'com.acme'." (which
        // implies the prefix matched nothing).
        mbean.loggers = List.of(new LoggerInfoData("com.acme.Quiet", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.listLoggers("com.acme", false, false)));

        String text = output();
        assertFalse(text.contains("No loggers match 'com.acme'."), text);
        assertTrue(text.contains("No loggers matching 'com.acme' have an active override."), text);
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
                "CONSOLE", "TRACE", "FIXED", "INC-1", Instant.now().toString(), "jmx", "STICKY", null, List.of()));

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
                "CONSOLE", "TRACE", "FIXED", null, Instant.now().toString(), "jmx", "SESSION", null, List.of()));

        assertEquals(CliError.OK, run(Commands.status(false)));

        assertFalse(output().contains("No active overrides."));
    }

    @Test
    void status_jsonWrapsLoggersAndHandlerOverrides() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.acme.Loud", "INFO", "DEBUG", true, "jmx", null, "STICKY", null));
        mbean.handlerOverrides = List.of(new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", null, Instant.now().toString(), "jmx", "STICKY", null, List.of()));

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
    void doctor_printsAPointerLine_whenStormsAreOngoing() {
        mbean.findings = List.of(new DoctorFindingData("logger.verbosity-left-on", "OK", "ROOT",
                "no excess verbosity found at root or on a known-chatty logger.", null, null, null));
        mbean.stormReport = new StormReportData(List.of(
                new StormData("com.acme.Worker", "ERROR", "org.acme.SlotException", "no capacity", null, "ONGOING",
                        Instant.now().toString(), Instant.now().toString(), null, 5000L, null, null)),
                1, 1, Instant.now().toString(), 0);

        assertEquals(CliError.OK, run(Commands.doctor(false)));

        assertTrue(output().contains("logctl storms"), output());
    }

    @Test
    void doctor_noPointerLine_whenNoStormsAreOngoing() {
        mbean.findings = List.of(new DoctorFindingData("logger.verbosity-left-on", "OK", "ROOT",
                "no excess verbosity found at root or on a known-chatty logger.", null, null, null));

        assertEquals(CliError.OK, run(Commands.doctor(false)));

        assertFalse(output().contains("logctl storms"), output());
    }

    // --- storms (doc/specs/storm-detection.md) -----------------------------------------------------

    @Test
    void storms_rendersOngoingAndEndedEntries() {
        Instant firstEventAt = Instant.now().minus(27, ChronoUnit.MINUTES);
        Instant endedAt = Instant.now().minus(12, ChronoUnit.MINUTES);
        Instant endedFirst = endedAt.minus(7, ChronoUnit.MINUTES).minus(29, ChronoUnit.SECONDS);
        mbean.stormReport = new StormReportData(List.of(
                new StormData("com.acme.batch.Worker", "ERROR", "org.acme.SlotException", "no capacity", null,
                        "ONGOING", firstEventAt.toString(), Instant.now().toString(), null, 3_104_772L,
                        "03:14:02 ERROR [com.acme.batch.Worker] Unable to reserve slot", null),
                new StormData("org.apache.http.impl.conn", "ERROR", null, "connection reset by peer", null,
                        "ENDED", endedFirst.toString(), endedAt.toString(), endedAt.toString(), 812_004L, null,
                        null)),
                2, 1, Instant.now().toString(), 0);

        assertEquals(CliError.OK, run(Commands.storms(0, false)));

        String text = output();
        assertTrue(text.contains("[ONGOING]"), text);
        assertTrue(text.contains("com.acme.batch.Worker"), text);
        assertTrue(text.contains("org.acme.SlotException"), text);
        assertTrue(text.contains("3,104,772 events"), text);
        assertTrue(text.contains("first occurrence:"), text);
        assertTrue(text.contains("[ENDED]"), text);
        assertTrue(text.contains("(no exception)"), text);
        assertTrue(text.contains("812,004 events"), text);
        assertTrue(text.contains("2 storms tracked — 1 ongoing, 1 ended."), text);
    }

    @Test
    void storms_noStormsDetected_printsANoteInsteadOfThrowing() {
        mbean.stormReport = new StormReportData(List.of(), 0, 0, null, 0);

        assertEquals(CliError.OK, run(Commands.storms(0, false)));

        assertTrue(output().contains("No log storms detected."));
    }

    @Test
    void storms_json_usesTrueTrackedAndOngoingCounts_notStormsSize() {
        mbean.stormReport = new StormReportData(List.of(
                new StormData("a.Logger", "ERROR", null, "boom", null, "ONGOING",
                        Instant.now().toString(), Instant.now().toString(), null, 10L, null, null)),
                5, 3, Instant.now().toString(), 0);

        run(Commands.storms(1, true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"storms\":[{"), text);
        assertTrue(text.contains("\"trackedCount\":5"), text);
        assertTrue(text.contains("\"ongoingCount\":3"), text);
        assertEquals(List.of(1), mbean.activeStormsLimits);
    }

    @Test
    void storms_showsContextPrefixOnlyWhenResultSpansMultipleContexts() {
        mbean.stormReport = new StormReportData(List.of(
                new StormData("a.Logger", "ERROR", null, "boom", null, "ONGOING",
                        Instant.now().toString(), Instant.now().toString(), null, 10L, null, "system"),
                new StormData("b.Logger", "ERROR", null, "boom", null, "ONGOING",
                        Instant.now().toString(), Instant.now().toString(), null, 10L, null, "myapp.war")),
                2, 2, Instant.now().toString(), 0);

        run(Commands.storms(0, false));

        String text = output();
        assertTrue(text.contains("[system]"), text);
        assertTrue(text.contains("[myapp.war]"), text);
    }

    @Test
    void listHandlers_rendersATableWithLevelSinkTargetAndOverride() {
        mbean.handlerCatalog = List.of(
                new org.logaperture.control.jmx.HandlerInfoData(
                        "ALL_HANDLERS", null, false, null, null, false, null, null, null, null, null, null),
                new org.logaperture.control.jmx.HandlerInfoData(
                        "CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null, null, null),
                new org.logaperture.control.jmx.HandlerInfoData(
                        "FILE", "DEBUG", true, "/opt/server.log", Boolean.TRUE, true, "DEBUG", "FIXED", "FOR",
                        Instant.now().plus(30, ChronoUnit.MINUTES).toString(), null, null));

        assertEquals(CliError.OK, run(Commands.listHandlers(true, false)));

        String text = output();
        assertTrue(text.contains("HANDLER") && text.contains("LEVEL") && text.contains("TARGET"), text);
        assertTrue(text.contains("ALL_HANDLERS"), text);
        assertTrue(text.contains("CONSOLE") && text.contains("INFO"), text);
        assertTrue(text.contains("FILE") && text.contains("/opt/server.log"), text);
        assertTrue(text.contains("DEBUG (FOR"), "the override cell shows level + tier: " + text);
    }

    @Test
    void listHandlers_defaultHandlersRow_showsMembersSummaryInTheTargetColumn() {
        // doc/specs/handler-floor-control.md "Data model", issue #28 -- the
        // TARGET column is otherwise unused for a group ref.
        mbean.handlerCatalog = List.of(
                new org.logaperture.control.jmx.HandlerInfoData(
                        "DEFAULT_HANDLERS", null, false, null, null, false, null, null, null, null,
                        "(auto: CONSOLE)", null));

        assertEquals(CliError.OK, run(Commands.listHandlers(true, false)));

        String text = output();
        assertTrue(text.contains("DEFAULT_HANDLERS"), text);
        assertTrue(text.contains("(auto: CONSOLE)"), text);
    }

    @Test
    void listHandlers_defaultsToOverridesOnly() {
        // doc/specs/list-command-surface.md Decision #2 -- same default as list loggers.
        mbean.handlerCatalog = List.of(
                new org.logaperture.control.jmx.HandlerInfoData(
                        "CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null, null, null),
                new org.logaperture.control.jmx.HandlerInfoData(
                        "FILE", "DEBUG", true, "/opt/server.log", Boolean.TRUE, true, "DEBUG", "FIXED", "FOR",
                        Instant.now().plus(30, ChronoUnit.MINUTES).toString(), null, null));

        assertEquals(CliError.OK, run(Commands.listHandlers(false, false)));

        String text = output();
        assertTrue(text.contains("FILE"));
        assertFalse(text.contains("CONSOLE"));
    }

    @Test
    void listHandlers_overridesOnlyEmptyMessageDiffersFromShowAll() {
        mbean.handlerCatalog = List.of(new org.logaperture.control.jmx.HandlerInfoData(
                "CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.listHandlers(false, false)));
        assertTrue(output().contains("No handlers have an active override."));

        captured.reset();
        mbean.handlerCatalog = List.of();
        assertEquals(CliError.OK, run(Commands.listHandlers(true, false)));
        assertTrue(output().contains("no level of their own"));
    }

    @Test
    void listHandlers_emptyCatalogPrintsNoLevelNoteEvenWithoutShowAll() {
        // A code-review finding against an earlier version: a genuinely empty
        // catalog (e.g. Logback, which has no addressable handlers) must not
        // be conflated with "nothing overridden" just because --show-all was
        // omitted -- the showAll flag never even reaches an empty catalog.
        mbean.handlerCatalog = List.of();

        assertEquals(CliError.OK, run(Commands.listHandlers(false, false)));

        assertTrue(output().contains("no level of their own"), output());
        assertFalse(output().contains("No handlers have an active override."), output());
    }

    @Test
    void listHandlers_nothingToList_printsANote() {
        mbean.handlerCatalog = List.of();
        assertEquals(CliError.OK, run(Commands.listHandlers(true, false)));
        assertTrue(output().contains("no level of their own"), output());
    }

    @Test
    void listHandlers_json_wrapsTheCatalog() {
        mbean.handlerCatalog = List.of(new org.logaperture.control.jmx.HandlerInfoData(
                "CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null, null, null));

        run(Commands.listHandlers(true, true));

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
                "com.acme", "DEBUG", "INC-1", Instant.now().toString(), "jmx", "FOR", expiresAt), List.of());

        run(Commands.setLogger("com.acme", "DEBUG", "INC-1", "FOR", 1800L, false, false));

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
                "com.acme", "WARN", null, Instant.now().toString(), "jmx", "STICKY", null), List.of());
        run(Commands.setLogger("com.acme", "WARN", null, "STICKY", 0L, false, false));
        assertTrue(output().contains("(STICKY — until reset)"));

        captured.reset();
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "WARN", null, Instant.now().toString(), "jmx", "SESSION", null), List.of());
        run(Commands.setLogger("com.acme", "WARN", null, "SESSION", 0L, false, false));
        assertTrue(output().contains("(SESSION — until the JVM stops)"));
    }

    @Test
    void setLevelJsonEmitsTheOverrideObject() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "DEBUG", null, "2026-08-25T00:00:00Z", "jmx", "SESSION", null), List.of());
        run(Commands.setLogger("com.acme", "DEBUG", null, "SESSION", 0L, false, true));
        assertEquals(
                "{\"overrides\":[{\"loggerName\":\"com.acme\",\"level\":\"DEBUG\","
                        + "\"reason\":null,\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\","
                        + "\"tier\":\"SESSION\",\"expiresAt\":null}],\"warnings\":[]}",
                output().strip());
    }

    @Test
    void setLevel_oneBlockingHandler_printsTheActionableWarning() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "TRACE", null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of(new HandlerFloorData("CONSOLE", "INFO")));

        run(Commands.setLogger("com.acme", "TRACE", null, "SESSION", 0L, false, false));

        String text = output();
        assertTrue(text.contains("WARN: handler CONSOLE is at INFO"));
        assertTrue(text.contains("logctl set handler CONSOLE TRACE"));
    }

    @Test
    void setLevel_multipleBlockingHandlers_printsOneCommandEach() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "TRACE", null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of(new HandlerFloorData("CONSOLE", "INFO"), new HandlerFloorData("FILE", "DEBUG")));

        run(Commands.setLogger("com.acme", "TRACE", null, "SESSION", 0L, false, false));

        String text = output();
        assertTrue(text.contains("2 handlers"));
        assertTrue(text.contains("logctl set handler CONSOLE TRACE"));
        assertTrue(text.contains("logctl set handler FILE TRACE"));
    }

    @Test
    void setLevel_noBlockingHandlers_printsNoWarning() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "DEBUG", null, Instant.now().toString(), "jmx", "SESSION", null), List.of());

        run(Commands.setLogger("com.acme", "DEBUG", null, "SESSION", 0L, false, false));

        assertFalse(output().contains("WARN"));
    }

    @Test
    void setLevelJson_withBlockingHandlers_emitsWarningsArray() {
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "com.acme", "TRACE", null, "2026-08-25T00:00:00Z", "jmx", "SESSION", null),
                List.of(new HandlerFloorData("CONSOLE", "INFO")));

        run(Commands.setLogger("com.acme", "TRACE", null, "SESSION", 0L, false, true));

        assertTrue(output().contains("\"warnings\":[{\"handlerRef\":\"CONSOLE\",\"currentLevel\":\"INFO\"}]"));
    }

    // --- setLogger on a pattern: one-time selection (doc/specs/pattern-selection-semantics.md) -

    @Test
    void setLevel_pattern_confirmedViaYesFlag_appliesWithoutPrompting() {
        mbean.loggers = List.of(new LoggerInfoData("org.apache.Worker", "INFO", "INFO", false, null, null, null, null));
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "org.apache.Worker", "DEBUG", null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of());

        int exit = run(Commands.setLogger("*.Worker", "DEBUG", null, "SESSION", 0L, true, false));

        assertEquals(CliError.OK, exit);
        Object[] call = mbean.setLevelCalls.get(0);
        assertEquals(true, call[5], "confirmed");
        assertFalse(output().contains("Apply? [y/N]"), "--yes must skip the prompt entirely");
        assertTrue(output().contains("org.apache.Worker → DEBUG"), output());
    }

    @Test
    void setLevel_pattern_interactiveTypedY_previewsThenApplies() {
        mbean.loggers = List.of(new LoggerInfoData("org.apache.Worker", "INFO", "INFO", false, null, null, null, null));
        mbean.setLevelResult = setLevelResult(new LevelOverrideData(
                "org.apache.Worker", "DEBUG", null, Instant.now().toString(), "jmx", "SESSION", null),
                List.of());

        int exit = run(Commands.setLogger("*.Worker", "DEBUG", null, "SESSION", 0L, false, false), "y", true);

        assertEquals(CliError.OK, exit);
        String text = output();
        assertTrue(text.contains("This will set DEBUG on 1 currently-known logger"), text);
        assertTrue(text.contains("org.apache.Worker"), "the preview lists the current match: " + text);
        assertTrue(text.contains("not affected"), "reworded to drop the standing-rule framing: " + text);
        assertTrue(mbean.setLevelCalls.get(0)[5].equals(true), "a typed 'y' confirms exactly like --yes");
    }

    @Test
    void setLevel_pattern_interactiveTypedN_declinesWithoutCallingSetLevel() {
        int exit = run(Commands.setLogger("*.Worker", "DEBUG", null, "SESSION", 0L, false, false), "n", true);

        assertEquals(CliError.OK, exit);
        assertTrue(mbean.setLevelCalls.isEmpty(), "declining must never reach the server");
        assertTrue(output().contains("Not applied."), output());
    }

    @Test
    void setLevel_pattern_nonInteractiveWithoutYes_isAUsageErrorNamingTheFlag() {
        // Decision #4: fail fast rather than block forever on a read from a
        // stdin nothing will ever write to.
        CliError error = org.junit.jupiter.api.Assertions.assertThrows(CliError.class,
                () -> run(Commands.setLogger("*.Worker", "DEBUG", null, "SESSION", 0L, false, false)));

        assertEquals(CliError.USAGE, error.exitCode());
        String message = error.getMessage();
        assertTrue(message.contains("--yes"), message);
        assertTrue(message.contains("DEBUG"), message);
        assertTrue(message.contains("matches"), message);
        assertFalse(message.contains("standing rule"), message);
        assertTrue(mbean.setLevelCalls.isEmpty());
    }

    @Test
    void setLevel_pattern_zeroCurrentMatches_printsNothingToSet() {
        mbean.setLevelResult = new SetLevelResultData(List.of(), List.of());

        int exit = run(Commands.setLogger("*.brandnew", "DEBUG", null, "SESSION", 0L, true, false));

        assertEquals(CliError.OK, exit);
        assertTrue(output().contains("matches no currently-known logger; nothing to set."), output());
    }

    @Test
    void setLevel_pattern_multipleMatches_printsOneLinePerLogger() {
        mbean.setLevelResult = new SetLevelResultData(List.of(
                new LevelOverrideData("org.apache.A", "DEBUG", null, Instant.now().toString(),
                        "jmx", "SESSION", null),
                new LevelOverrideData("org.apache.B", "DEBUG", null, Instant.now().toString(),
                        "jmx", "SESSION", null)),
                List.of());

        run(Commands.setLogger("*.A", "DEBUG", null, "SESSION", 0L, true, false));

        String text = output();
        assertTrue(text.contains("org.apache.A → DEBUG"), text);
        assertTrue(text.contains("org.apache.B → DEBUG"), text);
    }

    @Test
    void setLevel_pattern_json_wrapsOverridesAsAList() {
        mbean.setLevelResult = new SetLevelResultData(List.of(
                new LevelOverrideData("org.apache.A", "DEBUG", null, "2026-08-25T00:00:00Z",
                        "jmx", "SESSION", null)),
                List.of());

        run(Commands.setLogger("*.A", "DEBUG", null, "SESSION", 0L, true, true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"overrides\":[{"), text);
        assertTrue(text.contains("\"loggerName\":\"org.apache.A\""), text);
    }

    @Test
    void setLevel_trailingWildcard_skipsThePreviewAndLetsTheServersUsageErrorPropagate() {
        // doc/specs/pattern-selection-semantics.md, Decision #5: rejected
        // before confirmation is even evaluated, whether or not --yes was
        // passed and whether or not the call is interactive.
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> run(Commands.setLogger("org.apache.*", "DEBUG", null, "SESSION", 0L, false, false)));

        assertTrue(e.getMessage().contains("trailing wildcard"), e.getMessage());
        assertFalse(output().contains("Apply? [y/N]"), "no preview is ever shown for a trailing-star target");
        assertEquals(1, mbean.setLevelCalls.size(), "the call reaches the server directly, no preview round-trip");
    }

    // --- reset on a pattern (doc/specs/pattern-selection-semantics.md) -----------------------

    @Test
    void reset_pattern_revertsCurrentlyOverriddenMatches() {
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.A", "INFO", "DEBUG", true, "jmx", null, "FOR", null),
                new LoggerInfoData("org.apache.B", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", false, false)));

        assertEquals(List.of("org.apache.*"), mbean.resetLevelCalls);
        String text = output();
        assertTrue(text.contains("org.apache.A → INFO (baseline)"), text);
        assertFalse(text.contains("org.apache.B"), "never-overridden matches aren't reported as reverted: " + text);
    }

    @Test
    void reset_pattern_nothingCurrentlyOverridden_printsNothingWasOverridden() {
        mbean.loggers = List.of(new LoggerInfoData("org.apache.A", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", false, false)));

        assertEquals("'org.apache.*' — nothing was overridden.", output().strip());
    }

    @Test
    void reset_pattern_json_reportsRevertedNames() {
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.A", "INFO", "DEBUG", true, "jmx", null, "FOR", null));

        run(Commands.resetLogger("org.apache.*", false, true));

        String text = output().strip();
        assertTrue(text.contains("\"pattern\":\"org.apache.*\""), text);
        assertTrue(text.contains("\"revertedLoggerNames\":[\"org.apache.A\"]"), text);
    }

    @Test
    void reset_pattern_json_nothingOverridden_reportsAnEmptyRevertedList() {
        run(Commands.resetLogger("org.apache.*", false, true));

        String text = output().strip();
        assertTrue(text.contains("\"revertedLoggerNames\":[]"), text);
    }

    @Test
    void reset_pattern_stickyMatch_leftInPlaceAndReported() {
        // doc/specs/reset-command-surface.md, Decision #1: a pattern names a
        // set, however large -- a sticky member is skipped and reported, not
        // refused.
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.A", "INFO", "DEBUG", true, "jmx", null, "FOR", null),
                new LoggerInfoData("org.apache.Sticky", "WARN", "TRACE", true, "jmx", null, "STICKY", null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", false, false)));

        String text = output();
        assertTrue(text.contains("org.apache.A → INFO (baseline)"), text);
        assertTrue(text.contains("Left 1 sticky override(s) in place (pass --include-sticky to include them): "
                + "org.apache.Sticky"), text);
    }

    @Test
    void reset_pattern_includeSticky_revertsTheStickyMatchToo() {
        mbean.loggers = List.of(
                new LoggerInfoData("org.apache.Sticky", "WARN", "TRACE", true, "jmx", null, "STICKY", null));

        assertEquals(CliError.OK, run(Commands.resetLogger("org.apache.*", true, false)));

        String text = output();
        assertTrue(text.contains("org.apache.Sticky → WARN (baseline)"), text);
        assertFalse(text.contains("Left"), text);
    }

    // --- handler (doc/specs/handler-floor-control.md) -----------------------------------------

    @Test
    void handlerSetForwardsEveryArgumentAndPrintsAConfirmation() {
        mbean.setHandlerLevelResult = new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", "INC-1", Instant.now().toString(), "jmx", "SESSION", null, List.of());

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
                "CONSOLE", "TRACE", "FIXED", null, "2026-08-25T00:00:00Z", "jmx", "STICKY", null, List.of());

        run(Commands.setHandlerLevel("CONSOLE", "TRACE", null, "STICKY", 0L, true));

        assertEquals(
                "{\"handlerRef\":\"CONSOLE\",\"level\":\"TRACE\",\"mode\":\"FIXED\",\"reason\":null,"
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"STICKY\","
                        + "\"expiresAt\":null,\"warnings\":[]}",
                output().strip());
    }

    @Test
    void handlerSet_oneSquelchedLogger_printsTheWarning() {
        // doc/specs/handler-floor-control.md "Squelch warning" (issue #16).
        mbean.setHandlerLevelResult = new HandlerLevelOverrideData(
                "CONSOLE", "INFO", "FIXED", null, Instant.now().toString(), "jmx", "SESSION", null,
                List.of(new SquelchedLoggerData("com.acme.Worker", "DEBUG")));

        run(Commands.setHandlerLevel("CONSOLE", "INFO", null, "SESSION", 0L, false));

        String text = output();
        assertTrue(text.contains("WARN: handler CONSOLE is now INFO and will drop DEBUG records from "
                + "com.acme.Worker."), text);
        assertTrue(text.contains("logctl set handler CONSOLE DEBUG"), text);
    }

    @Test
    void handlerSet_multipleSquelchedLoggers_listsEachAndSuggestsTheMostVerboseLevel() {
        mbean.setHandlerLevelResult = new HandlerLevelOverrideData(
                "CONSOLE", "INFO", "FIXED", null, Instant.now().toString(), "jmx", "SESSION", null,
                List.of(new SquelchedLoggerData("com.acme.Worker", "DEBUG"),
                        new SquelchedLoggerData("com.acme.Payments", "TRACE")));

        run(Commands.setHandlerLevel("CONSOLE", "INFO", null, "SESSION", 0L, false));

        String text = output();
        assertTrue(text.contains("will drop records from 2 loggers"), text);
        assertTrue(text.contains("com.acme.Worker"), text);
        assertTrue(text.contains("com.acme.Payments"), text);
        assertTrue(text.contains("logctl set handler CONSOLE TRACE"), text); // most verbose of DEBUG/TRACE
    }

    @Test
    void handlerSetJson_squelchedLogger_includesItInTheWarningsArray() {
        mbean.setHandlerLevelResult = new HandlerLevelOverrideData(
                "CONSOLE", "INFO", "FIXED", null, "2026-08-25T00:00:00Z", "jmx", "SESSION", null,
                List.of(new SquelchedLoggerData("com.acme.Worker", "DEBUG")));

        run(Commands.setHandlerLevel("CONSOLE", "INFO", null, "SESSION", 0L, true));

        assertEquals(
                "{\"handlerRef\":\"CONSOLE\",\"level\":\"INFO\",\"mode\":\"FIXED\",\"reason\":null,"
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"SESSION\","
                        + "\"expiresAt\":null,\"warnings\":[{\"loggerName\":\"com.acme.Worker\",\"level\":\"DEBUG\"}]}",
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
                "CONSOLE", "DEBUG", "AUTO", "INC-1", Instant.now().toString(), "jmx", "SESSION", null, List.of());

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
                "CONSOLE", "TRACE", "AUTO", null, "2026-08-25T00:00:00Z", "jmx", "STICKY", null, List.of());

        run(Commands.setHandlerAuto("CONSOLE", null, "STICKY", 0L, true));

        assertEquals(
                "{\"handlerRef\":\"CONSOLE\",\"level\":\"TRACE\",\"mode\":\"AUTO\",\"reason\":null,"
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"STICKY\","
                        + "\"expiresAt\":null,\"warnings\":[]}",
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
                        "STICKY", null, List.of()),
                new HandlerLevelOverrideData("FILE", "WARN", "FIXED", null, Instant.now().toString(), "jmx",
                        "STICKY", null, List.of()));

        assertEquals(CliError.OK, run(Commands.status(false)));

        String text = output();
        assertTrue(text.contains("MODE"), text);
        assertTrue(text.contains("AUTO"), text);
        assertTrue(text.contains("FIXED"), text);
    }

    @Test
    void listHandlers_catalogRowInAutoMode_showsTheTrackedLevelPrefixed() {
        mbean.handlerCatalog = List.of(new org.logaperture.control.jmx.HandlerInfoData(
                "CONSOLE", "DEBUG", false, null, Boolean.TRUE, true, "DEBUG", "AUTO", "STICKY", null, null, null));

        assertEquals(CliError.OK, run(Commands.listHandlers(true, false)));

        assertTrue(output().contains("AUTO → DEBUG"), output());
    }

    @Test
    void handlerResetCallsResetHandler() {
        assertEquals(CliError.OK, run(Commands.resetHandler("CONSOLE", false, false)));
        assertEquals(List.of("CONSOLE"), mbean.resetHandlerCalls);
        assertEquals("handler CONSOLE → reset to its previous level.", output().strip());
    }

    @Test
    void handlerReset_noActiveOverride_saysNothingWasOverridden() {
        mbean.handlerHasOverrideToReset = false;

        assertEquals(CliError.OK, run(Commands.resetHandler("CONSOLE", false, false)));
        assertEquals("handler CONSOLE — nothing was overridden.", output().strip());
    }

    @Test
    void handlerResetJsonEmitsAnObject() {
        assertEquals(CliError.OK, run(Commands.resetHandler("CONSOLE", false, true)));
        assertEquals("{\"handlerRef\":\"CONSOLE\",\"reset\":true}", output().strip());
    }

    @Test
    void resetAllHandlersJsonEmitsRevertedAndSkippedStickyArrays() {
        assertEquals(CliError.OK, run(Commands.resetAllHandlers(false, true)));
        assertEquals("{\"revertedHandlerRefs\":[\"CONSOLE\"],\"skippedStickyHandlerRefs\":[]}", output().strip());
        assertEquals(1, mbean.resetAllHandlersCalls);
    }

    @Test
    void resetCallsResetLoggerThenReportsTheRestoredLevel() {
        mbean.loggers = List.of(new LoggerInfoData("com.acme", "INFO", "INFO", false, null, null, null, null));
        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme", false, false)));
        assertEquals(List.of("com.acme"), mbean.resetLevelCalls);
        assertEquals("com.acme → INFO (baseline)", output().strip());
    }

    @Test
    void resetOfAnUnknownLoggerSaysNothingWasOverridden() {
        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme.ghost", false, false)));
        assertEquals("com.acme.ghost — nothing was overridden.", output().strip());
    }

    @Test
    void resetOfAnOverriddenButNotYetInstantiatedLoggerReportsTheRevertNotNothing() {
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme.Known", null, "DEBUG", true, "jmx", "resumed", "FOR", null)));
        mbean.forgetOnReset.add("com.acme.Known");

        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme.Known", false, false)));
        assertEquals(List.of("com.acme.Known"), mbean.resetLevelCalls);
        assertEquals("com.acme.Known → baseline (not yet instantiated, so no level to show)", output().strip());
    }

    @Test
    void resetJsonEmitsAnObjectEvenWhenNoPostResetLoggerRemains() {
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme.Known", null, "DEBUG", true, "jmx", null, "FOR", null)));
        mbean.forgetOnReset.add("com.acme.Known");

        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme.Known", false, true)));
        assertEquals(
                "{\"name\":\"com.acme.Known\",\"overrideActive\":false,\"wasOverridden\":true,"
                        + "\"removedRuleIds\":[],\"skippedStickyRuleIds\":[]}",
                output().strip());
    }

    @Test
    void resetOfAStickyExactNameTarget_propagatesTheRefusal() {
        // doc/specs/reset-command-surface.md, Decision #1: naming one
        // specific sticky target without --include-sticky refuses outright
        // -- Commands lets the server's IllegalArgumentException propagate,
        // same as every other usage-shaped server rejection (Main maps it
        // to exit 2).
        mbean.loggers = List.of(new LoggerInfoData("com.acme.Payments", "WARN", "TRACE", true, "jmx", null,
                "STICKY", null));

        assertThrows(IllegalArgumentException.class,
                () -> run(Commands.resetLogger("com.acme.Payments", false, false)));
    }

    @Test
    void resetAllLoggers_revertsAndReportsSkippedSticky() {
        mbean.loggers = List.of(
                new LoggerInfoData("a", "INFO", "DEBUG", true, "jmx", null, "FOR", null),
                new LoggerInfoData("b", "INFO", "INFO", false, null, null, null, null),
                new LoggerInfoData("c", "INFO", "TRACE", true, "jmx", null, "SESSION", null),
                new LoggerInfoData("d", "WARN", "DEBUG", true, "jmx", null, "STICKY", null));

        assertEquals(CliError.OK, run(Commands.resetAllLoggers(false, false)));
        assertEquals(1, mbean.resetAllLoggersCalls);
        String text = output();
        assertTrue(text.contains("Reverted 2 override(s)."), text);
        assertTrue(text.contains("Left 1 sticky override(s) in place (pass --include-sticky to include them): d"),
                text);
    }

    @Test
    void resetAllLoggersJsonEmitsRevertedAndSkippedStickyArrays() {
        assertEquals(CliError.OK, run(Commands.resetAllLoggers(false, true)));
        assertEquals("{\"revertedLoggerNames\":[],\"skippedStickyLoggerNames\":[]}", output().strip());
    }

    @Test
    void resetAllLoggers_nothingToReset_printsNoOverridesLine() {
        assertEquals(CliError.OK, run(Commands.resetAllLoggers(false, false)));
        assertEquals("No overrides to reset.", output().strip());
    }

    // --- CONTEXT column (doc/specs/wildfly-support.md, Slice 3) ------------------------------------

    @Test
    void listLoggers_showsContextColumn_onlyWhenTheResultSpansMoreThanOneContext() {
        mbean.loggers = List.of(
                new LoggerInfoData("com.shared.Util", "INFO", "DEBUG", true, "jmx", null, "STICKY", null, "system"),
                new LoggerInfoData("com.myapp.Svc", null, "INFO", false, null, null, null, null, "myapp.war"));

        assertEquals(CliError.OK, run(Commands.listLoggers(null, true, false)));

        String text = output();
        assertTrue(text.contains("CONTEXT"), "column header present");
        assertTrue(text.contains("system"));
        assertTrue(text.contains("myapp.war"));
    }

    @Test
    void listLoggers_hidesContextColumn_whenEveryRowSharesOneContext() {
        mbean.loggers = List.of(
                new LoggerInfoData("a", "INFO", "DEBUG", true, "jmx", null, "STICKY", null, "system"),
                new LoggerInfoData("b", "INFO", "INFO", false, null, null, null, null, "system"));

        assertEquals(CliError.OK, run(Commands.listLoggers(null, true, false)));

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

    // --- set/reset default-handler (doc/specs/handler-floor-control.md "Default handler group", issue #28) ----

    @Test
    void setDefaultHandlerMembers_forwardsNamesAndPrintsTheNewMembership() {
        mbean.defaultHandlerMembersResult = List.of("FILE", "CONSOLE");

        run(Commands.setDefaultHandlerMembers(List.of("FILE", "CONSOLE"), false));

        assertEquals(List.of("FILE", "CONSOLE"), mbean.setDefaultHandlerMembersCalls.get(0));
        assertEquals("DEFAULT_HANDLERS → FILE, CONSOLE", output().strip());
    }

    @Test
    void setDefaultHandlerMembers_noNames_printsClearedNotAnEmptyList() {
        mbean.defaultHandlerMembersResult = List.of();

        run(Commands.setDefaultHandlerMembers(List.of(), false));

        assertEquals(List.of(), mbean.setDefaultHandlerMembersCalls.get(0));
        assertTrue(output().contains("DEFAULT_HANDLERS cleared"), output());
    }

    @Test
    void setDefaultHandlerMembers_json_emitsTheMemberArray() {
        mbean.defaultHandlerMembersResult = List.of("CONSOLE");

        run(Commands.setDefaultHandlerMembers(List.of("CONSOLE"), true));

        assertEquals("{\"defaultHandlerMembers\":[\"CONSOLE\"]}", output().strip());
    }

    // --- add rule drop (doc/specs/drop-rule.md) -----------------------------------------------

    @Test
    void addRuleDrop_passesEveryArgumentThroughAndRendersTheCreatedRule() {
        mbean.addRuleDropResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "drop", "WARN", "noisy", false, null, null, false, "INC-1", "FOR",
                Instant.now().plusSeconds(3600).toString(), Instant.now().toString(), null, 0L, null, null);

        int exit = run(Commands.addRuleDrop("com.acme.Worker", "noisy", false, null, null, false, "WARN", true,
                300_000L, "INC-1", "FOR", 3600L, false));

        assertEquals(CliError.OK, exit);
        assertArrayEquals(new Object[] {"com.acme.Worker", "noisy", false, null, null, false, "WARN", true,
                300_000L, "INC-1", "FOR", 3600L}, mbean.addRuleDropCalls.get(0));
        assertTrue(output().contains("r1") && output().contains("com.acme.Worker") && output().contains("drop"),
                output());
    }

    @Test
    void addRuleDrop_json() {
        mbean.addRuleDropResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "drop", "WARN", "noisy", false, null, null, false, null, "SESSION", null,
                Instant.now().toString(), null, 0L, null, null);

        run(Commands.addRuleDrop("com.acme.Worker", "noisy", false, null, null, false, "WARN", true, 300_000L, null,
                "SESSION", 0L, true));

        assertTrue(output().strip().startsWith("{\"id\":\"r1\""), output());
    }

    @Test
    void addRuleDrop_rejectsAPatternTargetClientSide() {
        assertThrows(CliError.class,
                () -> run(Commands.addRuleDrop("*.Worker", "noisy", false, null, null, false, "WARN", true,
                        300_000L, null, "SESSION", 0L, false)));
        assertTrue(mbean.addRuleDropCalls.isEmpty(), "never reaches the mbean for an unsupported pattern target");
    }

    // --- add rule trim (doc/specs/trim-rule.md) -----------------------------------------------

    @Test
    void addRuleTrim_passesEveryArgumentThroughAndRendersTheCreatedRule() {
        mbean.addRuleTrimResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "trim", "WARN", null, false, null, null, false, "INC-1", "FOR",
                Instant.now().plusSeconds(3600).toString(), Instant.now().toString(), null, 0L, 3, false);

        int exit = run(Commands.addRuleTrim("com.acme.Worker", null, false, null, null, false, "WARN", 3, false,
                "INC-1", "FOR", 3600L, false));

        assertEquals(CliError.OK, exit);
        assertArrayEquals(new Object[] {"com.acme.Worker", null, false, null, null, false, "WARN", 3, false,
                "INC-1", "FOR", 3600L}, mbean.addRuleTrimCalls.get(0));
        assertTrue(output().contains("r1") && output().contains("com.acme.Worker") && output().contains("trim"),
                output());
    }

    @Test
    void addRuleTrim_json() {
        mbean.addRuleTrimResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "trim", "WARN", null, false, null, null, false, null, "SESSION", null,
                Instant.now().toString(), null, 0L, 0, false);

        run(Commands.addRuleTrim("com.acme.Worker", null, false, null, null, false, "WARN", 0, false, null,
                "SESSION", 0L, true));

        assertTrue(output().strip().startsWith("{\"id\":\"r1\""), output());
    }

    @Test
    void addRuleTrim_rejectsAPatternTargetClientSide() {
        assertThrows(CliError.class,
                () -> run(Commands.addRuleTrim("*.Worker", null, false, null, null, false, "WARN", 0, false, null,
                        "SESSION", 0L, false)));
        assertTrue(mbean.addRuleTrimCalls.isEmpty(), "never reaches the mbean for an unsupported pattern target");
    }

    // --- list rules / reset rule / reset rules (doc/specs/rule-pipeline-foundation.md) ------------

    @Test
    void listRules_rendersATableWithIdLoggerActionTierExpiry() {
        mbean.rules = List.of(new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "TestRule", "ERROR", "This happens a lot", false, null, null, false,
                "INC-123", "STICKY", null, Instant.now().toString(), null, 0L, null, null));

        assertEquals(CliError.OK, run(Commands.listRules(false)));

        String text = output();
        assertTrue(text.contains("ID") && text.contains("LOGGER") && text.contains("ACTION"), text);
        assertTrue(text.contains("r1") && text.contains("com.acme.Worker") && text.contains("STICKY"), text);
    }

    @Test
    void listRules_empty_printsANote() {
        assertEquals(CliError.OK, run(Commands.listRules(false)));
        assertEquals("No rules attached.", output().strip());
    }

    @Test
    void listRules_json_wrapsTheRows() {
        mbean.rules = List.of(new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "TestRule", null, null, false, null, null, false, null, "SESSION", null,
                Instant.now().toString(), null, 0L, null, null));

        run(Commands.listRules(true));

        String text = output().strip();
        assertTrue(text.startsWith("{\"rules\":[{"), text);
        assertTrue(text.contains("\"id\":\"r1\""), text);
    }

    @Test
    void resetRule_removed_reportsIt() {
        mbean.resetRuleResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "TestRule", null, null, false, null, null, false, null, "SESSION", null,
                Instant.now().toString(), null, 0L, null, null);

        assertEquals(CliError.OK, run(Commands.resetRule("r1", false, false)));

        assertArrayEquals(new Object[] {"r1", false}, mbean.resetRuleCalls.get(0));
        assertEquals("rule r1 → reset.", output().strip());
    }

    @Test
    void resetRule_unknownId_saysNoSuchRule() {
        assertEquals(CliError.OK, run(Commands.resetRule("no-such-id", false, false)));
        assertEquals("rule no-such-id — no such rule.", output().strip());
    }

    @Test
    void resetAllRules_revertsAndReportsSkippedSticky() {
        mbean.resetAllRulesResult = new org.logaperture.control.jmx.RuleResetOutcomeData(
                List.of("r1"), List.of("r2"));

        assertEquals(CliError.OK, run(Commands.resetAllRules(false, false)));

        String text = output();
        assertTrue(text.contains("Removed 1 rule(s)."), text);
        assertTrue(text.contains("r2"), text);
    }

    @Test
    void resetAllRules_nothingToReset_printsANote() {
        assertEquals(CliError.OK, run(Commands.resetAllRules(false, false)));
        assertEquals("No rules to reset.", output().strip());
    }

    @Test
    void resetLogger_alsoRemovesRulesAttachedDirectlyToIt_andReportsBoth() {
        mbean.loggers = List.of(new LoggerInfoData("com.acme", "INFO", "INFO", false, null, null, null, null));
        mbean.resetRulesForLoggerResult = new org.logaperture.control.jmx.RuleResetOutcomeData(
                List.of("r1"), List.of());

        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme", false, false)));

        assertArrayEquals(new Object[] {"com.acme", false}, mbean.resetRulesForLoggerCalls.get(0));
        String text = output();
        assertTrue(text.contains("com.acme → INFO (baseline)"), text);
        assertTrue(text.contains("Removed 1 rule(s) attached to com.acme."), text);
    }

    @Test
    void resetLogger_noRulesAttached_printsNoExtraLine() {
        mbean.loggers = List.of(new LoggerInfoData("com.acme", "INFO", "INFO", false, null, null, null, null));

        assertEquals(CliError.OK, run(Commands.resetLogger("com.acme", false, false)));

        assertEquals("com.acme → INFO (baseline)", output().strip());
    }

    @Test
    void resetLogger_json_includesTheRuleRemovalOutcome() {
        // A code-review finding: the JSON branch used to compute rulesOutcome
        // and then never write it, making a real server-side removal
        // invisible to any script consuming --json output.
        mbean.loggers = List.of(new LoggerInfoData("com.acme", "INFO", "INFO", false, null, null, null, null));
        mbean.resetRulesForLoggerResult = new org.logaperture.control.jmx.RuleResetOutcomeData(
                List.of("r1"), List.of("r2"));

        run(Commands.resetLogger("com.acme", false, true));

        String text = output().strip();
        assertTrue(text.contains("\"removedRuleIds\":[\"r1\"]"), text);
        assertTrue(text.contains("\"skippedStickyRuleIds\":[\"r2\"]"), text);
    }
}
