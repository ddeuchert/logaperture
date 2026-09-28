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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guided {@code alter rule} (doc/specs/guided-commands.md #16–#18, slice (c)), driven through {@link Main#run}. */
class GuidedAlterRuleTest {

    private static final String DEPLOYER = "org.jboss.as.server.deployment.Deployer";
    private static final List<String> ARGS = List.of("id", "messageContains", "messageIgnoreCase", "clearMessage",
            "throwableType", "clearThrowable", "throwableMessageContains", "clearThrowableMessage", "anyCause",
            "belowLevel", "sampleFullEnabled", "sampleFullEveryMillis", "frames", "collapseCauses", "reason", "tier",
            "forSeconds");

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();

    /*
     * Parts as listed: trim  1 message 2 ignore case 3 exception class 4 exception message 5 any cause
     *                        6 below 7 frames 8 collapse 9 lifetime 10 reason
     *                  drop  1–6 as trim, 7 keep one full event every, 8 lifetime, 9 reason
     */
    GuidedAlterRuleTest() {
        String later = Instant.now().plus(20, ChronoUnit.HOURS).toString();
        RuleData r12 = new RuleData("r12", DEPLOYER, "trim", "ERROR", "Failed to connect", false,
                "java.net.ConnectException", null, false, "perfmon auto-update noise", "FOR", later, null, null, 0L, 0,
                false, null, false, null, null, false);
        RuleData r3 = new RuleData("r3", "com.acme.Worker", "drop", "INFO", "green", false, null, null, false, null,
                "SESSION", null, null, null, 0L, null, null, null, false, true, 300_000L, false);
        RuleData vendor = new RuleData("vendor:quiet", "com.acme.perf", "drop", "INFO", "x", false, null, null, false,
                null, null, null, null, null, 0L, null, null, "vendor-defaults", false, true, 300_000L, false);
        mbean.rules = new ArrayList<>(List.of(r12, r3, vendor));
        mbean.alterRuleResult = new RuleAlterationData(r12, "--message-contains x", "FOR", later, true);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private int guided(String answers, String... args) {
        return Main.run(args, out, err, explicitPid -> controlPlane(mbean),
                new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)), true);
    }

    private int scripted(String... args) {
        return Main.run(args, out, err, explicitPid -> controlPlane(mbean), java.io.InputStream.nullInputStream(),
                false);
    }

    private static ControlPlane controlPlane(FakeLevelControlMXBean target) {
        return new ControlPlane() {
            @Override
            public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                return target;
            }

            @Override
            public void close() {
            }
        };
    }

    private static String lines(String... answers) {
        return String.join("\n", answers) + "\n";
    }

    /** The one {@code alterRule} call's argument named {@code name}. */
    private Object arg(String name) {
        assertEquals(1, mbean.alterRuleCalls.size(), out());
        return mbean.alterRuleCalls.get(0)[ARGS.indexOf(name)];
    }

    // --- #17: the parts -------------------------------------------------------------------------

    /** The spec's sample session. */
    @Test
    void alterRule_theSampleSession() {
        int exit = guided(lines("3,6", "-", "WARN", ""), "alter", "rule", "r12");

        assertEquals(CliError.OK, exit, err());
        String text = out();
        assertTrue(text.contains("r12  trim " + DEPLOYER + "\n"), text);
        assertTrue(text.contains("   1  Message contains      \"Failed to connect\"\n"), text);
        assertTrue(text.contains("   3  Exception class       java.net.ConnectException\n"), text);
        assertTrue(text.contains("   6  Below level           FATAL\n"), text);
        assertTrue(text.contains("  10  Reason                perfmon auto-update noise\n"), text);
        assertTrue(text.contains("Exception class (fully qualified) [java.net.ConnectException] ('-' to remove)"),
                text);
        assertTrue(text.contains("This is the command:\n  logctl alter rule r12 --no-throwable --below WARN\n"), text);
        assertEquals(true, arg("clearThrowable"));
        assertEquals("INFO", arg("belowLevel"), "below WARN spares WARN: INFO and more verbose");
        assertEquals(null, arg("messageContains"), "an unpicked part is left as it is");
        assertEquals(null, arg("tier"), "the lifetime is kept");
    }

    @Test
    void alterRule_onlyTheActionsOwnPartsAreListed() {
        guided(lines(""), "alter", "rule", "r3");

        assertTrue(out().contains("  7  Keep one full event every  5m\n"), out());
        assertFalse(out().contains("Stack frames"), out());
    }

    @Test
    void alterRule_answersThatChangeNothing_applyNothing() {
        int exit = guided(lines("6,10", "", ""), "alter", "rule", "r12");

        assertEquals(CliError.OK, exit);
        assertTrue(out().contains("Nothing changed. Not applied."), out());
        assertTrue(mbean.alterRuleCalls.isEmpty());
    }

    @Test
    void alterRule_aNewMessageWithIgnoreCase_givesBothTogether() {
        guided(lines("1,2", "blue", "y", ""), "alter", "rule", "r3");

        assertTrue(out().contains("  logctl alter rule r3 --message-contains-ignore-case blue\n"), out());
        assertEquals("blue", arg("messageContains"));
        assertEquals(true, arg("messageIgnoreCase"));
    }

    @Test
    void alterRule_onlyIgnoreCase_restatesTheMessage() {
        guided(lines("2", "y", ""), "alter", "rule", "r3");

        assertTrue(out().contains("  logctl alter rule r3 --message-contains-ignore-case green\n"), out());
    }

    @Test
    void alterRule_aDropRuleLeftWithoutAMatcher_isRefusedAndAskedAgain() {
        guided(lines("1", "-", ""), "alter", "rule", "r3");

        assertTrue(out().contains("A drop rule needs at least one of the message or exception matchers"), out());
        assertEquals(2, out().split("r3  drop com.acme.Worker\n", -1).length - 1, "the parts are listed again");
        assertTrue(out().contains("Not applied."), out());
        assertTrue(mbean.alterRuleCalls.isEmpty());
    }

    @Test
    void alterRule_samplingOff() {
        guided(lines("7", "off", ""), "alter", "rule", "r3");

        assertTrue(out().contains("  logctl alter rule r3 --no-sample-full\n"), out());
        assertEquals(false, arg("sampleFullEnabled"));
    }

    @Test
    void alterRule_aLifetimeOfFor4hIsSpelledOut() {
        guided(lines("8", "4h", ""), "alter", "rule", "r3");

        assertTrue(out().contains("  logctl alter rule r3 for 4h\n"), "without a tier, alter keeps the lifetime: "
                + out());
        assertEquals("FOR", arg("tier"));
        assertEquals(14_400L, arg("forSeconds"));
    }

    @Test
    void alterRule_invalidAnswersAreExplainedAndAskedAgain() {
        guided(lines("6,7", "LOUD", "WARN", "-1", "x", "3", ""), "alter", "rule", "r12");

        assertTrue(out().contains("Unknown level 'LOUD'"), out());
        assertTrue(out().contains("Frames must be 0 or more."), out());
        assertTrue(out().contains("'x' is not a number."), out());
        assertTrue(out().contains("  logctl alter rule r12 --below WARN --frames 3\n"), out());
    }

    @Test
    void alterRule_aVendorRulesLifetimeSaysWhatAChangeDoes() {
        guided(lines(""), "alter", "rule", "vendor:quiet");

        assertTrue(out().contains("Lifetime                   vendor definition (a change makes it for 4h unless you "
                + "pick this)"), out());
    }

    // --- #16: picking the rule --------------------------------------------------------------------

    @Test
    void alterRule_withoutAnId_picksOneRuleThenItsParts() {
        int exit = guided(lines("1", "10", "new reason", ""), "alter", "rule");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("Rules:\n  1  r12           trim " + DEPLOYER), out());
        assertTrue(out().contains("  logctl alter rule r12 --reason \"new reason\"\n"), out());
        assertEquals("new reason", arg("reason"));
    }

    @Test
    void alterRule_moreThanOneRulePicked_isAskedAgain() {
        guided(lines("1,2", "2", ""), "alter", "rule");

        assertTrue(out().contains("Pick one rule -- 'alter rule' changes one at a time."), out());
        assertTrue(out().contains("r3  drop com.acme.Worker\n"), out());
    }

    @Test
    void alterRule_changesGivenWithoutAnId_areKeptForThePickedRule() {
        guided(lines("2", ""), "alter", "rule", "--below", "WARN");

        assertFalse(out().contains("Keep one full event every"), "no parts are asked: " + out());
        assertTrue(out().contains("  logctl alter rule r3 --below WARN\n"), out());
        assertEquals("r3", arg("id"));
        assertEquals("INFO", arg("belowLevel"));
    }

    @Test
    void alterRule_withNoRules_saysSo() {
        mbean.rules = new ArrayList<>();

        assertEquals(CliError.OK, guided(lines(), "alter", "rule"));

        assertTrue(out().contains("No rules are attached; nothing to alter."), out());
    }

    /** The agent refuses to alter a vendor rule switched off until restart: don't offer one. */
    @Test
    void alterRule_withoutAnId_leavesOutSwitchedOffVendorRules() {
        mbean.rules.set(2, switchedOffVendorRule());

        guided(lines("3"), "alter", "rule");

        assertFalse(out().contains("vendor:quiet"), out());
        assertTrue(out().contains("3 isn't in the list -- choose from 1 to 2."), out());
    }

    @Test
    void alterRule_whenEveryRuleIsSwitchedOff_saysSo() {
        mbean.rules = new ArrayList<>(List.of(switchedOffVendorRule()));

        assertEquals(CliError.OK, guided(lines(), "alter", "rule"));

        assertTrue(out().contains("Every rule is a vendor rule switched off until restart -- 'logctl reset rule <id>' "
                + "switches one back on first."), out());
    }

    @Test
    void alterRule_aSwitchedOffVendorRuleById_isRefusedBeforeAnyQuestion() {
        mbean.rules.set(2, switchedOffVendorRule());

        assertEquals(CliError.USAGE, guided(lines("1"), "alter", "rule", "vendor:quiet"));

        assertTrue(err().contains("vendor:quiet is switched off until restart -- 'reset rule vendor:quiet' switches "
                + "it back on first."), err());
        assertFalse(out().contains("Which?"), out());
        assertTrue(mbean.alterRuleCalls.isEmpty());
    }

    private static RuleData switchedOffVendorRule() {
        return new RuleData("vendor:quiet", "com.acme.perf", "drop", "INFO", "x", false, null, null, false, null, null,
                null, null, null, 0L, null, null, "vendor-defaults", true, true, 300_000L, false);
    }

    @Test
    void alterRule_anUnknownId_isAnError() {
        assertEquals(CliError.USAGE, guided(lines(), "alter", "rule", "r99"));

        assertTrue(err().contains("No rule with id 'r99' -- see 'logctl list rules'."), err());
    }

    // --- applying, and not ------------------------------------------------------------------------

    @Test
    void alterRule_declinedOrEndOfInputAppliesNothing() {
        guided(lines("6", "WARN", "n"), "alter", "rule", "r12");
        guided(lines("6"), "alter", "rule", "r12");

        assertTrue(mbean.alterRuleCalls.isEmpty());
        assertTrue(out().contains("Not applied."), out());
    }

    /** #1: the printed command, run as-is, makes exactly the call the guided answers made. */
    @Test
    void alterRule_thePrintedCommandParsesBackToTheSameCall() {
        guided(lines("1,2,4,5,7,8,9,10", "it's \"quoted\" $HOME", "y", "boom", "y", "5", "y", "session", "why", ""),
                "alter", "rule", "r12");
        String printed = out().lines().filter(l -> l.startsWith("  logctl alter rule")).findFirst().orElseThrow();
        List<String> argv = RuleExpressionTest.shellSplit(printed.trim().substring("logctl ".length()));

        FakeLevelControlMXBean replay = new FakeLevelControlMXBean();
        replay.alterRuleResult = mbean.alterRuleResult;
        Main.run(argv.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), explicitPid -> controlPlane(replay),
                java.io.InputStream.nullInputStream(), false);

        assertArrayEquals(mbean.alterRuleCalls.get(0), replay.alterRuleCalls.get(0));
    }

    @Test
    void alterRule_withoutATerminal_isTheUsageErrorPlusTheHint() {
        assertEquals(CliError.USAGE, scripted("alter", "rule"));
        assertTrue(err().contains("'alter rule' needs the id of the rule to change -- see 'logctl list rules'.\n"
                + Parser.PROMPT_HINT), err());

        assertEquals(CliError.USAGE, scripted("alter", "rule", "r12"));
        assertTrue(err().contains("'alter rule' needs something to change"), err());
    }

    @Test
    void alterRule_withJson_neverAsks() {
        assertEquals(CliError.USAGE, guided(lines("1"), "alter", "rule", "r12", "--json"));

        assertFalse(out().contains("r12  trim"), out());
    }
}
