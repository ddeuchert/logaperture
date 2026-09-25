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
import org.logaperture.control.jmx.RuleAlterationData;
import org.logaperture.control.jmx.RuleData;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/alter-rule.md "Testing" -- the {@code logctl alter rule} side. */
class AlterRuleCommandsTest {

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    {
        // The parsing tests only look at what was sent; any found rule will do as the reply.
        mbean.alterRuleResult = new RuleAlterationData(trimRow("INFO", "SESSION", null, null, false),
                "--throwable java.net.ConnectException --below INFO --frames 0", "SESSION", null, false);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int run(String... argv) {
        return Parser.parse(argv).command().run(mbean, out, InputStream.nullInputStream(), false);
    }

    /** The {@code alterRule} arguments of the one call made, by parameter name. */
    private Object arg(String name) {
        List<String> names = List.of("id", "messageContains", "messageIgnoreCase", "clearMessage", "throwableType",
                "clearThrowable", "throwableMessageContains", "clearThrowableMessage", "anyCause", "belowLevel",
                "sampleFullEnabled", "sampleFullEveryMillis", "frames", "collapseCauses", "reason", "tier",
                "forSeconds");
        assertEquals(1, mbean.alterRuleCalls.size());
        return mbean.alterRuleCalls.get(0)[names.indexOf(name)];
    }

    private static RuleData trimRow(String levelAtMost, String tier, String expiresAt, String origin,
            boolean altered) {
        return new RuleData("r3", "org.mylogger", "trim", levelAtMost, null, false, "java.net.ConnectException",
                null, false, null, tier, expiresAt, "2026-09-25T12:00:00Z", "system", 0, 0, false, origin, false,
                null, null, altered);
    }

    private static CliError usageError(String... argv) {
        CliError error = assertThrows(CliError.class, () -> Parser.parse(argv));
        assertEquals(CliError.USAGE, error.exitCode());
        return error;
    }

    // --- parsing: patch semantics (A3) -------------------------------------------------------------

    @Test
    void onlyTheNamedParts_areSent_andAnOmittedTierKeepsTheLifetime() {
        run("alter", "rule", "r3", "--below", "WARN");

        assertEquals("r3", arg("id"));
        assertEquals("INFO", arg("belowLevel"), "--below WARN compiles to 'at most INFO', as for add rule");
        assertEquals(null, arg("messageContains"));
        assertEquals(false, arg("clearMessage"));
        assertEquals(null, arg("anyCause"));
        assertEquals(null, arg("sampleFullEnabled"));
        assertEquals(null, arg("frames"));
        assertEquals(null, arg("tier"), "not 'for 4h': the rule keeps its lifetime");
    }

    @Test
    void messageContainsIgnoreCase_setsTheIgnoreCaseForm() {
        run("alter", "rule", "r3", "--message-contains-ignore-case", "green");

        assertEquals("green", arg("messageContains"));
        assertEquals(true, arg("messageIgnoreCase"));
    }

    @Test
    void theNoForms_clearOptionalParts() {
        run("alter", "rule", "r3", "--no-message-contains", "--no-throwable", "--no-throwable-message-contains",
                "--no-any-cause", "--no-collapse-causes", "--no-sample-full");

        assertEquals(true, arg("clearMessage"));
        assertEquals(true, arg("clearThrowable"));
        assertEquals(true, arg("clearThrowableMessage"));
        assertEquals(false, arg("anyCause"));
        assertEquals(false, arg("collapseCauses"));
        assertEquals(false, arg("sampleFullEnabled"));
    }

    @Test
    void aTierAlone_isEnough() {
        run("alter", "rule", "r3", "for", "30m");

        assertEquals("FOR", arg("tier"));
        assertEquals(1800L, arg("forSeconds"));
    }

    @Test
    void reasonAlone_isEnough() {
        run("alter", "rule", "r3", "--reason", "INC-42");

        assertEquals("INC-42", arg("reason"));
    }

    @Test
    void usageErrors() {
        usageError("alter", "rule", "r3");
        usageError("alter", "rule");
        usageError("alter", "logger", "com.acme");
        usageError("alter", "rule", "r3", "--throwable", "x.Y", "--no-throwable");
        usageError("alter", "rule", "r3", "--any-cause", "--no-any-cause");
        usageError("alter", "rule", "r3", "--message-contains", "a", "--message-contains-ignore-case", "b");
        usageError("alter", "rule", "r3", "--sample-full", "5m", "--no-sample-full");
        usageError("add", "rule", "drop", "com.acme", "--message-contains", "x", "--no-throwable");
        usageError("alter", "rule", "r3", "--below", "WARN", "later");
    }

    // --- output ----------------------------------------------------------------------------------

    @Test
    void output_showsWasAndNow_andAnUnchangedTier() {
        mbean.alterRuleResult = new RuleAlterationData(trimRow("INFO", "SESSION", null, null, false),
                "--throwable java.net.ConnectException --below INFO --frames 0", "SESSION", null, true);

        assertEquals(CliError.OK, run("alter", "rule", "r3", "--below", "WARN"));

        assertEquals(String.join(System.lineSeparator(),
                "rule r3 (trim, org.mylogger) altered",
                "  was: --throwable java.net.ConnectException --below INFO --frames 0",
                "  now: --throwable java.net.ConnectException --below WARN --frames 0",
                "  tier: SESSION — until the JVM stops, unchanged"), output().strip());
    }

    @Test
    void output_forAVendorRulesFirstAlteration_showsTheLifetimeChange() {
        mbean.alterRuleResult = new RuleAlterationData(trimRow("INFO", "STICKY", null, "vendor-defaults", true),
                "--throwable java.net.ConnectException --below INFO --frames 0",
                RuleAlterationData.VENDOR_BASELINE_TIER, null, true);

        run("alter", "rule", "vendor:x", "--below", "WARN", "sticky");

        assertTrue(output().contains("  tier: was vendor-defaults, now STICKY — until reset"), output());
    }

    @Test
    void output_noChange() {
        mbean.alterRuleResult = new RuleAlterationData(trimRow("INFO", "SESSION", null, null, false),
                "--throwable java.net.ConnectException --below WARN --frames 0", "SESSION", null, false);

        run("alter", "rule", "r3", "--below", "WARN");

        assertEquals("rule r3 — no change.", output().strip());
    }

    @Test
    void anUnknownId_isAnError() {
        mbean.alterRuleResult = null;

        CliError error = assertThrows(CliError.class, () -> run("alter", "rule", "r99", "--below", "WARN"));

        assertEquals(CliError.USAGE, error.exitCode());
        assertTrue(error.getMessage().contains("r99"), error.getMessage());
    }

    @Test
    void json_isTheRuleRowPlusWhatItWas() {
        mbean.alterRuleResult = new RuleAlterationData(trimRow("INFO", "SESSION", null, null, false),
                "--throwable java.net.ConnectException --below INFO --frames 0", "SESSION", null, true);

        run("alter", "rule", "r3", "--below", "WARN", "--json");

        String json = output().strip();
        assertTrue(json.startsWith("{\"id\":\"r3\""), json);
        assertTrue(json.contains("\"expression\":\"--throwable java.net.ConnectException --below WARN --frames 0\""),
                json);
        assertTrue(json.endsWith("\"previousExpression\":\"--throwable java.net.ConnectException --below INFO "
                + "--frames 0\",\"previousTier\":\"SESSION\",\"previousExpiresAt\":null,\"changed\":true}"), json);
    }

    @Test
    void theFramesOptions_reachAlterRule() {
        run("alter", "rule", "r3", "--frames", "5", "--collapse-causes");

        assertArrayEquals(new Object[] {5, true}, new Object[] {arg("frames"), arg("collapseCauses")});
    }
}
