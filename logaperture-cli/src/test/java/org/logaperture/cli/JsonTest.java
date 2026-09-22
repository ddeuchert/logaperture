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
import org.logaperture.control.jmx.EnvironmentReportData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerByteCountData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.StormData;
import org.logaperture.control.jmx.StormReportData;
import org.logaperture.control.jmx.TopReportData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void loggerObjectCarriesEveryFieldWithNullsAsJsonNull() {
        LoggerInfoData row = new LoggerInfoData("com.acme", null, "INFO", false, null, null, null, null);
        assertEquals(
                "{\"name\":\"com.acme\",\"configuredLevel\":null,\"effectiveLevel\":\"INFO\","
                        + "\"overrideActive\":false,\"overrideSource\":null,\"overrideReason\":null,"
                        + "\"tier\":null,\"expiresAt\":null}",
                Json.logger(row));
    }

    @Test
    void overrideObjectKeyOrderMatchesTheSpec() {
        LevelOverrideData data = new LevelOverrideData(
                "com.acme", "DEBUG", "INC-1", "2026-08-25T00:00:00Z", "jmx", "FOR", "2026-08-25T04:00:00Z");
        assertEquals(
                "{\"loggerName\":\"com.acme\",\"level\":\"DEBUG\",\"reason\":\"INC-1\","
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"FOR\","
                        + "\"expiresAt\":\"2026-08-25T04:00:00Z\"}",
                Json.override(data));
    }

    @Test
    void stringsAreEscaped() {
        LoggerInfoData row =
                new LoggerInfoData("a", "INFO", "INFO", true, "jmx", "line1\nline2 \"quoted\" \\ tab\t", "STICKY", null);
        String json = Json.logger(row);
        assertTrue(json.contains("\"line1\\nline2 \\\"quoted\\\" \\\\ tab\\t\""), json);
    }

    @Test
    void arrayWrapsElements() {
        LoggerInfoData a = new LoggerInfoData("a", "INFO", "INFO", false, null, null, null, null);
        LoggerInfoData b = new LoggerInfoData("b", "INFO", "WARN", false, null, null, null, null);
        String json = Json.loggers(List.of(a, b));
        assertTrue(json.startsWith("[{"), json);
        assertTrue(json.endsWith("}]"), json);
        assertTrue(json.contains("},{"), json);
    }

    @Test
    void resetAllLoggersReportsRevertedAndSkippedSticky() {
        assertEquals("{\"revertedLoggerNames\":[\"a\",\"b\"],\"skippedStickyLoggerNames\":[\"c\"]}",
                Json.resetAllLoggers(List.of("a", "b"), List.of("c")));
    }

    @Test
    void resetAllHandlersReportsRevertedAndSkippedSticky() {
        assertEquals("{\"revertedHandlerRefs\":[\"CONSOLE\"],\"skippedStickyHandlerRefs\":[]}",
                Json.resetAllHandlers(List.of("CONSOLE"), List.of()));
    }

    @Test
    void statusWrapsLoggersAndHandlerOverridesUnderTheirOwnKeys() {
        LoggerInfoData logger = new LoggerInfoData("a", "INFO", "DEBUG", true, "jmx", null, "STICKY", null);
        HandlerLevelOverrideData handler = new HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "FIXED", null, "2026-08-25T00:00:00Z", "jmx", "STICKY", null, List.of());

        String json = Json.status(List.of(logger), List.of(handler));

        assertTrue(json.startsWith("{\"loggers\":[{"), json);
        assertTrue(json.contains("\"handlerOverrides\":[{\"handlerRef\":\"CONSOLE\""), json);
    }

    @Test
    void statusWithNothingActiveStillEmitsBothEmptyArrays() {
        assertEquals("{\"loggers\":[],\"handlerOverrides\":[]}", Json.status(List.of(), List.of()));
    }

    // --- top (doc/specs/top.md) -----------------------------------------------------------------

    @Test
    void topEmitsRawCountsKeyOrderAndTrackedCount() {
        TopReportData report = new TopReportData(
                List.of(new LoggerByteCountData("org.apache.http", 1_000L, 980L, null)),
                "2026-09-05T14:02:11Z", 1);

        assertEquals(
                "{\"loggers\":[{\"loggerName\":\"org.apache.http\",\"totalBytes\":1000,\"stackTraceBytes\":980,"
                        + "\"context\":null}],\"measurementStartedAt\":\"2026-09-05T14:02:11Z\",\"trackedCount\":1}",
                Json.top(report));
    }

    @Test
    void topWithNoTrackedLoggers_emitsAnEmptyArrayAndNullStartedAt() {
        TopReportData report = new TopReportData(List.of(), null, 0);

        assertEquals("{\"loggers\":[],\"measurementStartedAt\":null,\"trackedCount\":0}", Json.top(report));
    }

    @Test
    void topEmitsTheTrueTrackedCount_notTheLimitTruncatedRowCount() {
        // --limit already truncated "loggers" to one row server-side; trackedCount
        // must still report every logger actually being tracked (doc/specs/top.md).
        TopReportData report = new TopReportData(
                List.of(new LoggerByteCountData("org.apache.http", 1_000L, 980L, null)),
                "2026-09-05T14:02:11Z", 200);

        assertTrue(Json.top(report).contains("\"trackedCount\":200"), Json.top(report));
    }

    // --- storms (doc/specs/storm-detection.md) ---------------------------------------------------

    @Test
    void stormsEmitsRawCountsAndTrueTrackedAndOngoingCounts() {
        StormReportData report = new StormReportData(List.of(
                new StormData("com.acme.Worker", "ERROR", "org.acme.SlotException", "no capacity", null, "ONGOING",
                        "2026-09-05T03:14:02Z", "2026-09-05T03:15:02Z", null, 1_000L, "boom", null)),
                5, 3, "2026-09-05T14:02:11Z", 0);

        String json = Json.storms(report);

        assertTrue(json.startsWith("{\"storms\":[{\"loggerName\":\"com.acme.Worker\""), json);
        assertTrue(json.contains("\"trackedCount\":5"), json);
        assertTrue(json.contains("\"ongoingCount\":3"), json);
        assertTrue(json.contains("\"measurementStartedAt\":\"2026-09-05T14:02:11Z\""), json);
        assertTrue(json.contains("\"notRetainedCount\":0"), json);
    }

    @Test
    void stormsWithNoStorms_emitsAnEmptyArray() {
        StormReportData report = new StormReportData(List.of(), 0, 0, null, 0);

        assertEquals("{\"storms\":[],\"trackedCount\":0,\"ongoingCount\":0,\"measurementStartedAt\":null,"
                + "\"notRetainedCount\":0}", Json.storms(report));
    }

    @Test
    void stormsEmitsTopFramesArray_whenFrameEscalationRan() {
        StormReportData report = new StormReportData(List.of(
                new StormData("com.acme.Worker", "ERROR", "java.lang.RuntimeException", "boom",
                        List.of("com.acme.Worker.reserve(Worker.java:88)"), "ONGOING",
                        "2026-09-05T03:14:02Z", "2026-09-05T03:15:02Z", null, 1_000L, null, null)),
                1, 1, null, 0);

        String json = Json.storms(report);

        assertTrue(json.contains("\"topFrames\":[\"com.acme.Worker.reserve(Worker.java:88)\"]"), json);
    }

    // --- handlers (doc/specs/handler-floor-control.md "The handler catalog") -------------------

    @Test
    void handlersEmitsNullsWhereFieldsDoNotApply() {
        org.logaperture.control.jmx.HandlerInfoData allHandlers = new org.logaperture.control.jmx.HandlerInfoData(
                "ALL_HANDLERS", null, false, null, null, false, null, null, null, null, null, "system");
        org.logaperture.control.jmx.HandlerInfoData file = new org.logaperture.control.jmx.HandlerInfoData(
                "FILE", "INFO", true, "/var/log/server.log", Boolean.TRUE, true, "DEBUG", "FIXED", "FOR",
                "2026-09-07T14:32:00Z", null, "system");

        assertEquals(
                "{\"handlers\":[{\"ref\":\"ALL_HANDLERS\",\"level\":null,\"persistent\":false,\"targetPath\":null,"
                        + "\"autoFlush\":null,\"overrideActive\":false,\"overrideLevel\":null,\"overrideMode\":null,"
                        + "\"overrideTier\":null,"
                        + "\"overrideExpiresAt\":null,\"membersSummary\":null,\"context\":\"system\"},"
                        + "{\"ref\":\"FILE\",\"level\":\"INFO\",\"persistent\":true,\"targetPath\":\"/var/log/server.log\","
                        + "\"autoFlush\":true,\"overrideActive\":true,\"overrideLevel\":\"DEBUG\",\"overrideMode\":\"FIXED\","
                        + "\"overrideTier\":\"FOR\","
                        + "\"overrideExpiresAt\":\"2026-09-07T14:32:00Z\",\"membersSummary\":null,\"context\":\"system\"}]}",
                Json.handlers(List.of(allHandlers, file)));
    }

    @Test
    void handlersWithNothingToList_emitsAnEmptyArray() {
        assertEquals("{\"handlers\":[]}", Json.handlers(List.of()));
    }

    // --- env (doc/specs/environment-report.md) -----------------------------------------------

    @Test
    void envEmitsAFlatObjectWithCliVersionStitchedInAfterAgentVersion() {
        EnvironmentReportData report = new EnvironmentReportData(
                "0.1.0-alpha.2", "21.0.4", "Eclipse Adoptium", "Linux", "6.10.3", "x86_64",
                "JBoss LogManager", "3.1.1.Final", "WildFly", "34.0.1.Final", "INFO",
                "/home/alice/.logaperture/instances/abc123-app.state.yaml");

        assertEquals(
                "{\"agentVersion\":\"0.1.0-alpha.2\",\"cliVersion\":\"0.1.0-alpha.2\",\"javaVersion\":\"21.0.4\","
                        + "\"javaVendor\":\"Eclipse Adoptium\",\"osName\":\"Linux\",\"osVersion\":\"6.10.3\","
                        + "\"osArch\":\"x86_64\",\"backendName\":\"JBoss LogManager\","
                        + "\"backendVersion\":\"3.1.1.Final\",\"containerName\":\"WildFly\","
                        + "\"containerVersion\":\"34.0.1.Final\",\"diagnosticsLevel\":\"INFO\","
                        + "\"stateFilePath\":\"/home/alice/.logaperture/instances/abc123-app.state.yaml\"}",
                Json.env(report, "0.1.0-alpha.2"));
    }

    @Test
    void envWithNoBackendOrContainerDetected_emitsNullFields() {
        EnvironmentReportData report = new EnvironmentReportData(
                "0.1.0-alpha.2", "21.0.4", "Eclipse Adoptium", "Linux", "6.10.3", "x86_64",
                null, null, null, null, null, null);

        String json = Json.env(report, "0.1.0-alpha.2");

        assertTrue(json.contains("\"backendName\":null") && json.contains("\"containerName\":null"), json);
        assertTrue(json.contains("\"diagnosticsLevel\":null"), json);
        assertTrue(json.contains("\"stateFilePath\":null"), json);
    }
}
