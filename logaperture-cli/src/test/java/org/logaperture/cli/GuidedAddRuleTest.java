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
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.core.Capability;
import org.logaperture.core.CapabilityDeniedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guided {@code add rule} and pattern targets (doc/specs/guided-add-rule.md), driven through {@link Main#run}. */
class GuidedAddRuleTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();

    GuidedAddRuleTest() {
        mbean.loggers = new ArrayList<>(List.of(
                logger("com.acme.batch.Deployer"),
                logger("org.jboss.as.ejb3.deployment.Deployer"),
                logger("org.jboss.as.server.deployment.Deployer"),
                logger("com.acme.Worker")));
    }

    private static LoggerInfoData logger(String name) {
        return new LoggerInfoData(name, "INFO", "INFO", false, null, null, null, null);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** Runs on a terminal, answering each question with the next line of {@code answers}. */
    private int guided(String answers, String... args) {
        return Main.run(args, out, err, explicitPid -> controlPlane(),
                new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)), true);
    }

    private int scripted(String... args) {
        return Main.run(args, out, err, explicitPid -> controlPlane(), java.io.InputStream.nullInputStream(), false);
    }

    private ControlPlane controlPlane() {
        return new ControlPlane() {
            @Override
            public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                return mbean;
            }

            @Override
            public void close() {
            }
        };
    }

    private static String lines(String... answers) {
        return String.join("\n", answers) + "\n";
    }

    // --- the sample session ---------------------------------------------------------------------

    @Test
    void theSpecsSampleSession_attachesTheRuleAndPrintsItsCommand() {
        int exit = guided(lines("3", "trim", "Failed to connect", "", "java.net.ConnectException", "", "", "FATAL",
                "", "", "for 1d", "perfmon auto-update noise", ""), "add", "rule", "*.Deployer");

        assertEquals(CliError.OK, exit, err());
        assertEquals(1, mbean.addRuleTrimCalls.size());
        assertArrayEquals(new Object[] {"org.jboss.as.server.deployment.Deployer", "Failed to connect", false,
                "java.net.ConnectException", null, false, "ERROR", 0, false, "perfmon auto-update noise", "FOR",
                86_400L}, mbean.addRuleTrimCalls.get(0));
        assertTrue(out().contains("  1  com.acme.batch.Deployer\n  2  org.jboss.as.ejb3.deployment.Deployer\n"
                + "  3  org.jboss.as.server.deployment.Deployer\n"), out());
        assertTrue(out().contains("  logctl add rule trim org.jboss.as.server.deployment.Deployer --message-contains "
                + "\"Failed to connect\" --throwable java.net.ConnectException --below FATAL for 1d --reason "
                + "\"perfmon auto-update noise\"\n"), out());
        assertTrue(out().contains("r1   org.jboss.as.server.deployment.Deployer → trim"), out());
    }

    /** G7: the printed command, run as-is, makes exactly the call the guided answers made. */
    @Test
    void thePrintedCommand_parsesBackToTheSameCall() {
        guided(lines("1", "drop", "it's \"quoted\" $HOME", "y", "a.B$C", "x y", "y", "INFO", "2m", "session", "why", ""),
                "add", "rule", "*.Deployer");
        String printed = out().lines().filter(l -> l.startsWith("  logctl add rule")).findFirst().orElseThrow();
        List<String> argv = RuleExpressionTest.shellSplit(printed.trim().substring("logctl ".length()));

        FakeLevelControlMXBean replay = new FakeLevelControlMXBean();
        Parser.parse(argv.toArray(String[]::new)).command()
                .run(replay, new PrintStream(new ByteArrayOutputStream()), java.io.InputStream.nullInputStream(), false);

        assertArrayEquals(mbean.addRuleDropCalls.get(0), replay.addRuleDropCalls.get(0));
    }

    @Test
    void defaults_areLeftOutOfThePrintedCommand() {
        guided(lines("noisy", "", "", "", "", "", "", "", ""), "add", "rule", "drop", "com.acme.Worker");

        assertTrue(out().contains("  logctl add rule drop com.acme.Worker --message-contains noisy\n"), out());
        assertArrayEquals(new Object[] {"com.acme.Worker", "noisy", false, null, null, false, "WARN", true, 300_000L,
                null, "FOR", 14_400L}, mbean.addRuleDropCalls.get(0));
    }

    // --- what is asked (G1, G6) -----------------------------------------------------------------

    @Test
    void partsGivenOnTheCommandLine_areNotAsked() {
        int exit = guided(lines("noisy", "", "", "", "", ""), "add", "rule", "drop", "com.acme.Worker", "--below",
                "WARN", "sticky", "--reason", "INC-1");

        assertEquals(CliError.OK, exit, err());
        assertFalse(out().contains("below which level"), out());
        assertFalse(out().contains("How long"), out());
        assertFalse(out().contains("Reason"), out());
        assertFalse(out().contains("Drop or trim"), out());
        assertEquals("STICKY", mbean.addRuleDropCalls.get(0)[10]);
    }

    @Test
    void ignoreCase_isAskedOnlyAfterAMessage_andAnyCauseOnlyAfterAnExceptionMatcher() {
        guided(lines("trim", "", "", "", "", "", "", "", "", ""), "add", "rule", "com.acme.Worker");

        assertEquals(1, mbean.addRuleTrimCalls.size(), out());
        assertFalse(out().contains("Ignore case"), out());
        assertFalse(out().contains("anywhere in the cause chain"), out());
    }

    @Test
    void aDropWithNoMatcher_asksTheMatchersAgain() {
        guided(lines("", "", "", "noisy", "", "", "", "", "", "", "", ""), "add", "rule", "drop", "com.acme.Worker");

        assertTrue(out().contains("A drop rule needs at least one of these"), out());
        assertEquals("noisy", mbean.addRuleDropCalls.get(0)[1]);
    }

    @Test
    void invalidAnswers_areExplainedAndAskedAgain() {
        guided(lines("drip", "trim", "", "", "", "LOUD", "WARN", "-1", "many", "3", "", "forever", "30m", "", ""),
                "add", "rule", "com.acme.Worker");

        assertTrue(out().contains("Answer drop or trim."), out());
        assertTrue(out().contains("Unknown level 'LOUD'"), out());
        assertTrue(out().contains("Frames must be 0 or more."), out());
        assertTrue(out().contains("'many' is not a number."), out());
        assertTrue(out().contains("Unparseable duration 'forever'"), out());
        assertArrayEquals(new Object[] {"com.acme.Worker", null, false, null, null, false, "INFO", 3, false, null,
                "FOR", 1_800L}, mbean.addRuleTrimCalls.get(0));
    }

    @Test
    void endOfInput_cancelsAtAnyQuestion() {
        for (int answered = 0; answered < 8; answered++) {
            List<String> answers = List.of("1", "drop", "noisy", "", "", "", "", "");
            outBytes.reset();
            int exit = guided(String.join("\n", answers.subList(0, answered)) + (answered == 0 ? "" : "\n"),
                    "add", "rule", "*.Deployer");

            assertEquals(CliError.OK, exit);
            assertTrue(out().endsWith("Not applied.\n"), "after " + answered + " answers: " + out());
            assertTrue(mbean.addRuleDropCalls.isEmpty());
        }
    }

    @Test
    void answeringNoToApply_attachesNothing() {
        guided(lines("noisy", "", "", "", "", "", "", "", "n"), "add", "rule", "drop", "com.acme.Worker");

        assertTrue(out().endsWith("Not applied.\n"), out());
        assertTrue(mbean.addRuleDropCalls.isEmpty());
    }

    // --- picking loggers (G4, G5) ---------------------------------------------------------------

    @Test
    void aBareWordTypedAtThePrompt_searchesAsTheLastSegment() {
        guided(lines("Deployer", "2", "trim", "", "", "", "", "", "", "", "", ""), "add", "rule");

        assertTrue(mbean.listLoggersFilters.contains("*.Deployer"), mbean.listLoggersFilters.toString());
        assertEquals("org.jboss.as.ejb3.deployment.Deployer", mbean.addRuleTrimCalls.get(0)[0]);
    }

    @Test
    void aBareWordOnTheCommandLine_isAnExactName_notTheShorthand() {
        guided(lines("n", ""), "add", "rule", "drop", "Deployer");

        assertTrue(out().contains("No logger named Deployer exists yet; attach anyway? [y/N]"), out());
        assertFalse(mbean.listLoggersFilters.contains("*.Deployer"));
    }

    @Test
    void aSelectionOfSeveral_attachesOneRulePerLogger_andPrintsOneCommandEach() {
        guided(lines("1,3", "trim", "", "", "", "", "", "", "", "", ""), "add", "rule", "*.Deployer");

        assertEquals(List.of("com.acme.batch.Deployer", "org.jboss.as.server.deployment.Deployer"),
                mbean.addRuleTrimCalls.stream().map(call -> call[0]).toList());
        assertTrue(out().contains("These are the commands:\n  logctl add rule trim com.acme.batch.Deployer\n"
                + "  logctl add rule trim org.jboss.as.server.deployment.Deployer\n"), out());
    }

    @Test
    void anInvalidSelection_isAskedAgain() {
        guided(lines("4", "x", "3-1", "2-3", "trim", "", "", "", "", "", "", "", "", ""), "add", "rule",
                "*.Deployer");

        assertTrue(out().contains("4 isn't in the list -- choose from 1 to 3."), out());
        assertTrue(out().contains("'x' isn't a selection"), out());
        assertTrue(out().contains("backwards range"), out());
        assertEquals(2, mbean.addRuleTrimCalls.size());
    }

    @Test
    void parseSelection_acceptsListsRangesAndAll() {
        assertEquals(List.of(1, 3, 4, 5), AddRuleCommand.parseSelection("5, 1,3-4", 5));
        assertEquals(List.of(1, 2, 3), AddRuleCommand.parseSelection("ALL", 3));
        assertThrows(IllegalArgumentException.class, () -> AddRuleCommand.parseSelection("0", 3));
    }

    @Test
    void oneMatch_isUsedWithoutASelectionQuestion() {
        guided(lines("trim", "", "", "", "", "", "", "", "", ""), "add", "rule", "*.batch.Deployer");

        assertTrue(out().contains("1 logger matches '*.batch.Deployer': com.acme.batch.Deployer"), out());
        assertFalse(out().contains("Which?"), out());
        assertEquals("com.acme.batch.Deployer", mbean.addRuleTrimCalls.get(0)[0]);
    }

    @Test
    void noMatch_asksForAnotherPattern() {
        guided(lines("Worker", "trim", "", "", "", "", "", "", "", "", ""), "add", "rule", "*.Nothing");

        assertTrue(out().contains("No logger matches '*.Nothing'."), out());
        assertEquals("com.acme.Worker", mbean.addRuleTrimCalls.get(0)[0]);
    }

    /** Code-review finding: a pattern the agent rejects, typed at the prompt, is asked again rather than fatal. */
    @Test
    void anInvalidPatternTypedAtThePrompt_isExplainedAndAskedAgain() {
        mbean.invalidFilters.add("*");
        int exit = guided(lines("*", "Worker", "trim", "", "", "", "", "", "", "", "", ""), "add", "rule");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("logctl: invalid filter '*'"), out());
        assertEquals("com.acme.Worker", mbean.addRuleTrimCalls.get(0)[0]);
    }

    @Test
    void anInvalidPatternOnTheCommandLine_isStillAUsageError() {
        mbean.invalidFilters.add("*.Deploy?");
        assertEquals(CliError.USAGE, guided("", "add", "rule", "*.Deploy?"));
        assertTrue(err().contains("invalid filter"), err());
    }

    @Test
    void moreThanThirtyMatches_areNotListed() {
        for (int i = 0; i < AddRuleCommand.MAX_LISTED + 1; i++) {
            mbean.loggers.add(logger("com.many.M" + i + ".Deployer"));
        }
        guided(lines("*.batch.Deployer", "trim", "", "", "", "", "", "", "", "", ""), "add", "rule", "*.Deployer");

        assertTrue(out().contains("34 loggers match '*.Deployer' -- too many to list."), out());
        assertEquals("com.acme.batch.Deployer", mbean.addRuleTrimCalls.get(0)[0]);
    }

    // --- without a terminal, --yes, --json (G1, G2, G9) ------------------------------------------

    @Test
    void anIncompleteCommandWithoutATerminal_isTheUsualUsageError_plusTheHint() {
        assertEquals(CliError.USAGE, scripted("add", "rule", "drop", "com.acme.Worker"));
        assertTrue(err().contains("'add rule drop' needs at least one content matcher"), err());
        assertTrue(err().contains(Parser.PROMPT_HINT), err());
    }

    @Test
    void yesAndJson_eachTurnGuidedModeOff() {
        assertEquals(CliError.USAGE, guided("", "add", "rule", "*.Deployer", "--yes"));
        assertEquals(CliError.USAGE, guided("", "add", "rule", "*.Deployer", "--json"));
        assertTrue(mbean.listLoggersFilters.isEmpty(), "a usage error is raised before any JVM is contacted");
    }

    @Test
    void aPatternOnACompleteCommand_isPickedOnATerminal() {
        int exit = guided(lines("2"), "add", "rule", "trim", "*.Deployer");

        assertEquals(CliError.OK, exit, err());
        assertEquals("org.jboss.as.ejb3.deployment.Deployer", mbean.addRuleTrimCalls.get(0)[0]);
        assertFalse(out().contains("This is the command"), "a complete command asks nothing else: " + out());
    }

    @Test
    void aPatternWithoutATerminal_needsYes() {
        assertEquals(CliError.USAGE, scripted("add", "rule", "trim", "*.Deployer"));
        assertTrue(err().contains("'*.Deployer' matches 3 currently-known loggers. Pass --yes"), err());
        assertTrue(mbean.addRuleTrimCalls.isEmpty());
    }

    @Test
    void aPatternWithYes_attachesToEveryMatch() {
        assertEquals(CliError.OK, scripted("add", "rule", "trim", "*.Deployer", "--yes"));
        assertEquals(3, mbean.addRuleTrimCalls.size());
    }

    @Test
    void aPatternWithJson_printsAnArray_whileAnExactNameKeepsOneObject() {
        scripted("add", "rule", "trim", "*.Deployer", "--yes", "--json");
        assertTrue(out().startsWith("[{") && out().trim().endsWith("}]"), out());

        outBytes.reset();
        scripted("add", "rule", "trim", "com.acme.Worker", "--json");
        assertTrue(out().startsWith("{"), out());
    }

    @Test
    void sampleFullWithoutTheType_isAUsageError() {
        assertEquals(CliError.USAGE, guided("", "add", "rule", "com.acme.Worker", "--sample-full", "5m"));
        assertTrue(err().contains("name the type"), err());
    }

    // --- one of several refused (G10) -----------------------------------------------------------

    @Test
    void aRefusedLogger_doesNotStopTheOthers() {
        mbean.addRuleRefusals.put("org.jboss.as.ejb3.deployment.Deployer",
                new CapabilityDeniedException(Capability.SUPPRESS));

        int exit = scripted("add", "rule", "trim", "*.Deployer", "--yes");

        assertEquals(CliError.REFUSED, exit);
        assertEquals(3, mbean.addRuleTrimCalls.size());
        assertTrue(out().contains("com.acme.batch.Deployer → trim"), out());
        assertTrue(out().contains("org.jboss.as.server.deployment.Deployer → trim"), out());
        assertTrue(err().contains("org.jboss.as.ejb3.deployment.Deployer: Refused:"), err());
    }
}
