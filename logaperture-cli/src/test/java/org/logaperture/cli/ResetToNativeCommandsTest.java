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
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.LoggerInfoData;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/reset-to-native.md -- {@code logctl reset ... --to-native} and its surfaces. */
class ResetToNativeCommandsTest {

    private static final String PERFMON = "org.perfmon4j";

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int run(String... argv) {
        return Parser.parse(argv).command().run(mbean, out, InputStream.nullInputStream(), false);
    }

    private static LoggerInfoData logger(String name, String configured, String effective, boolean overridden,
            String vendor, boolean resetToNative) {
        return new LoggerInfoData(name, configured, effective, overridden, overridden ? "jmx" : null, null,
                overridden ? "SESSION" : null, null, "system", vendor, resetToNative);
    }

    private static HandlerInfoData handler(String ref, String level, String vendor, boolean resetToNative,
            String membersSummary) {
        return new HandlerInfoData(ref, level, false, null, null, false, null, null, null, null, membersSummary,
                "system", vendor, resetToNative);
    }

    @Test
    void resetLogger_toNative_passesTheFlag_andSaysWhereItLanded() {
        mbean.loggers = new ArrayList<>(List.of(logger(PERFMON, "DEBUG", "DEBUG", false, "WARN", true)));

        assertEquals(CliError.OK, run("reset", "logger", PERFMON, "--to-native"));

        assertTrue(mbean.lastToNative);
        assertTrue(output().startsWith(PERFMON + " → DEBUG (native default, until restart)"), output());
    }

    @Test
    void aPlainResetLogger_landsOnTheVendorDefault() {
        mbean.lastToNative = true;
        mbean.loggers = new ArrayList<>(List.of(logger(PERFMON, "DEBUG", "WARN", false, "WARN", false)));

        run("reset", "logger", PERFMON);

        assertFalse(mbean.lastToNative, "a plain reset never asks for --to-native");
        assertTrue(output().startsWith(PERFMON + " → WARN (vendor default)"), output());
    }

    @Test
    void resetLoggers_toNative_reportsTheCount() {
        mbean.loggers = new ArrayList<>(List.of(logger(PERFMON, "DEBUG", "TRACE", true, "WARN", false)));

        run("reset", "loggers", "--to-native");

        assertTrue(mbean.lastToNative);
        assertTrue(output().startsWith("Reset 1 logger(s) to their native default (until restart)."), output());
    }

    @Test
    void resetHandler_andHandlers_toNative() {
        run("reset", "handler", "FILE", "--to-native");
        assertTrue(mbean.lastToNative);
        assertTrue(output().contains("handler FILE → reset to its native level (until restart)."), output());

        run("reset", "handlers", "--to-native");
        assertTrue(output().contains("Reset 1 handler(s) to their native level (until restart)."), output());
    }

    @Test
    void resetDefaultHandler_toNative_andPlain() {
        mbean.defaultHandlerMembersResult = List.of("CONSOLE");
        mbean.handlerCatalog = new ArrayList<>(List.of(handler("DEFAULT_HANDLERS", null, null, false,
                "(auto: CONSOLE)")));

        run("reset", "default-handler", "--to-native");

        assertEquals(List.of(true), mbean.resetDefaultHandlerCalls);
        assertTrue(output().contains("back to the automatic pick, ignoring the vendor defaults' list until restart"),
                output());

        captured.reset();
        mbean.defaultHandlerMembersResult = List.of("FILE");
        mbean.handlerCatalog = new ArrayList<>(List.of(handler("DEFAULT_HANDLERS", null, null, false,
                "(vendor: FILE)")));

        run("reset", "default-handler");

        assertEquals(List.of(true), mbean.resetDefaultHandlerCalls, "a plain reset uses the operation every agent has");
        assertEquals(List.of(List.of()), mbean.setDefaultHandlerMembersCalls);
        assertTrue(output().contains("DEFAULT_HANDLERS cleared -- back to the vendor defaults' list: FILE."),
                output());
    }

    @Test
    void resetDefaultHandler_json() {
        mbean.defaultHandlerMembersResult = List.of("CONSOLE");

        run("reset", "default-handler", "--to-native", "--json");

        assertEquals("{\"defaultHandlerMembers\":[],\"membersInEffect\":[\"CONSOLE\"],\"toNative\":true}",
                output().trim());
    }

    @Test
    void theVendorColumn_marksWhatIsResetToNative() {
        mbean.loggers = new ArrayList<>(List.of(logger(PERFMON, "DEBUG", "DEBUG", false, "WARN", true)));
        mbean.handlerCatalog = new ArrayList<>(List.of(handler("FILE", "ALL", "WARN", true, null)));

        run("list", "loggers");
        run("list", "handlers");

        assertEquals(2, output().lines().filter(line -> line.contains("WARN (reset to native)")).count(), output());
    }

    @Test
    void status_carriesTheResetToNativeCount() {
        mbean.environmentReport = new EnvironmentReportData("0.1.0-alpha.3", "21.0.4", "Eclipse Adoptium", "Linux",
                "6.10.3", "x86_64", null, null, null, null, null, null, "/opt/app/conf/vendor-defaults.yaml",
                "loaded (2 loggers), 1 reset to native");

        run("status");

        assertTrue(output().startsWith(
                "Vendor defaults: /opt/app/conf/vendor-defaults.yaml — 2 loggers, 1 reset to native"), output());
    }

    @Test
    void theFlag_isAUsageErrorOutsideLoggerHandlerAndDefaultHandlerResets() {
        for (String[] argv : List.of(
                new String[] {"reset", "rule", "r1", "--to-native"},
                new String[] {"reset", "rules", "--to-native"},
                new String[] {"set", "logger", PERFMON, "INFO", "--to-native"},
                new String[] {"list", "loggers", "--to-native"})) {
            CliError error = assertThrows(CliError.class, () -> Parser.parse(argv));
            assertEquals(CliError.USAGE, error.exitCode(), String.join(" ", argv));
        }
    }
}
