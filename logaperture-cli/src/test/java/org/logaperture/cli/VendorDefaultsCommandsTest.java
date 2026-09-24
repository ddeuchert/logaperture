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
import org.logaperture.control.jmx.RuleData;
import org.logaperture.control.jmx.RuleResetOutcomeData;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "Surfaces" -- the {@code logctl} side. */
class VendorDefaultsCommandsTest {

    private static final String PATH = "/opt/app/conf/vendor-defaults.yaml";

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int run(Command command) {
        return command.run(mbean, out, InputStream.nullInputStream(), false);
    }

    private int run(String... argv) {
        return run(Parser.parse(argv).command());
    }

    private static LoggerInfoData logger(String name, String configured, String effective, boolean overridden,
            String vendor) {
        return new LoggerInfoData(name, configured, effective, overridden, overridden ? "jmx" : null, null,
                overridden ? "SESSION" : null, null, "system", vendor);
    }

    private static EnvironmentReportData env(String path, String status) {
        return new EnvironmentReportData("0.1.0-alpha.3", "21.0.4", "Eclipse Adoptium", "Linux", "6.10.3", "x86_64",
                null, null, null, null, null, null, path, status);
    }

    private static RuleData rule(String id, String origin, boolean suspended) {
        return new RuleData(id, "com.acme.health", "drop", "INFO", "ping ok", false, null, null, false, null,
                "SESSION", null, "2026-09-24T12:00:00Z", "system", 3, null, null, origin, suspended);
    }

    @Test
    void listLoggers_defaultView_includesVendorDefaultedLoggers_withAVendorColumn() {
        mbean.loggers = new ArrayList<>(List.of(
                logger("ROOT", "INFO", "INFO", false, null),
                logger("org.hibernate.SQL", "DEBUG", "WARN", false, "WARN"),
                logger("com.acme.Worker", null, "TRACE", true, null)));

        assertEquals(CliError.OK, run("list", "loggers"));

        String text = output();
        assertTrue(text.contains("VENDOR"), text);
        assertTrue(text.contains("org.hibernate.SQL"), text);
        assertTrue(text.contains("com.acme.Worker"), text);
        assertFalse(text.contains("ROOT"), "neither overridden nor vendor-defaulted: " + text);
    }

    @Test
    void listLoggers_withNoVendorDefaults_hasNoVendorColumn() {
        mbean.loggers = new ArrayList<>(List.of(logger("com.acme.Worker", null, "TRACE", true, null)));

        run("list", "loggers");

        assertFalse(output().contains("VENDOR"), output());
    }

    @Test
    void listHandlers_defaultView_includesVendorDefaultedHandlers() {
        mbean.handlerCatalog = new ArrayList<>(List.of(
                new HandlerInfoData("FILE", "WARN", true, "/var/log/server.log", Boolean.TRUE, false, null, null,
                        null, null, null, "system", "WARN"),
                new HandlerInfoData("CONSOLE", "INFO", false, null, Boolean.TRUE, false, null, null, null, null,
                        null, "system", null)));

        run("list", "handlers");

        String text = output();
        assertTrue(text.contains("VENDOR") && text.contains("FILE"), text);
        assertFalse(text.contains("CONSOLE"), text);
    }

    @Test
    void listRules_showsVendorOriginInsteadOfATier() {
        mbean.rules = new ArrayList<>(List.of(
                rule("vendor:healthcheck-noise", "vendor-defaults", false),
                rule("vendor:autoupdate-trace", "vendor-defaults", true)));

        run("list", "rules");

        String text = output();
        assertTrue(text.contains("vendor-defaults (suspended)"), text);
        assertTrue(text.lines().anyMatch(line -> line.contains("vendor:healthcheck-noise")
                && line.contains("vendor-defaults") && !line.contains("suspended")), text);
    }

    @Test
    void status_showsOneVendorLine_whenAFileIsConfigured() {
        mbean.environmentReport = env(PATH, "loaded (2 loggers, 1 rule)");

        run("status");

        assertTrue(output().startsWith("Vendor defaults: " + PATH + " — 2 loggers, 1 rule"), output());
    }

    @Test
    void status_pointsAtDoctor_whenTheFileWasRejected() {
        mbean.environmentReport = env(PATH, "rejected (3 errors)");

        run("status");

        assertTrue(output().contains("Vendor defaults: " + PATH + " — REJECTED, see logctl doctor"), output());
    }

    @Test
    void status_hasNoVendorLine_whenNoneConfigured() {
        mbean.environmentReport = env(null, "not configured");

        run("status");

        assertFalse(output().contains("Vendor defaults"), output());
    }

    @Test
    void env_showsTheVendorDefaultsRow() {
        mbean.environmentReport = env(PATH, "loaded (2 loggers)");

        run("env");

        assertTrue(output().contains("Vendor defaults") && output().contains(PATH + "  loaded (2 loggers)"),
                output());
    }

    @Test
    void resetRule_passesTheFlag_andReportsSuspension() {
        mbean.resetRuleResult = rule("vendor:healthcheck-noise", "vendor-defaults", true);

        assertEquals(CliError.OK, run("reset", "rule", "vendor:healthcheck-noise", "--include-vendor-defaults"));

        assertTrue(mbean.lastIncludeVendorDefaults);
        assertTrue(output().contains("suspended until the application restarts"), output());
    }

    @Test
    void resetRules_reportsSkippedVendorRules() {
        mbean.resetAllRulesResult = new RuleResetOutcomeData(List.of("r1"), List.of(),
                List.of("vendor:healthcheck-noise"));

        run("reset", "rules");

        assertFalse(mbean.lastIncludeVendorDefaults);
        assertTrue(output().contains("Left 1 vendor default rule(s) in place (pass --include-vendor-defaults"),
                output());
    }

    @Test
    void theFlag_isAUsageErrorOutsideRuleAndLoggerResets() {
        for (String[] argv : List.of(
                new String[] {"reset", "handlers", "--include-vendor-defaults"},
                new String[] {"reset", "loggers", "--include-vendor-defaults"},
                new String[] {"list", "rules", "--include-vendor-defaults"})) {
            CliError error = assertThrows(CliError.class, () -> Parser.parse(argv));
            assertSame(CliError.class, error.getClass());
            assertEquals(CliError.USAGE, error.exitCode());
        }
    }
}
