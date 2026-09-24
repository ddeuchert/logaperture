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
import org.junit.jupiter.api.function.Executable;
import org.logaperture.cli.Parser.TierChoice;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    // --- tier resolution -----------------------------------------------------

    @Test
    void bareTierDefaultsToFourHourFor() {
        TierChoice choice = Parser.resolveTier(List.of());
        assertEquals("FOR", choice.tierName());
        assertEquals(Duration.ofHours(4).toSeconds(), choice.forSeconds());
    }

    @Test
    void sessionAndStickyKeywords() {
        assertEquals("SESSION", Parser.resolveTier(List.of("session")).tierName());
        assertEquals(0L, Parser.resolveTier(List.of("session")).forSeconds());
        assertEquals("STICKY", Parser.resolveTier(List.of("sticky")).tierName());
    }

    @Test
    void forWithDuration() {
        TierChoice choice = Parser.resolveTier(List.of("for", "30m"));
        assertEquals("FOR", choice.tierName());
        assertEquals(1800L, choice.forSeconds());
    }

    @Test
    void malformedTierTokensAreUsageErrors() {
        assertUsage(() -> Parser.resolveTier(List.of("for")));
        assertUsage(() -> Parser.resolveTier(List.of("session", "30m")));
        assertUsage(() -> Parser.resolveTier(List.of("sticky", "5m")));
        assertUsage(() -> Parser.resolveTier(List.of("forever")));
        assertUsage(() -> Parser.resolveTier(List.of("for", "30m", "extra")));
    }

    // --- command grammar ---------------------------------------------------

    @Test
    void helpAndVersionShortCircuit() {
        assertTrue(Parser.parse(new String[] {"--help"}).help());
        assertTrue(Parser.parse(new String[] {"-h"}).help());
        assertTrue(Parser.parse(new String[] {"--version"}).version());
        assertNull(Parser.parse(new String[] {"--help"}).command());
    }

    @Test
    void noArgumentsIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {}));
    }

    @Test
    void unknownCommandAndUnknownFlagAreUsageErrors() {
        assertUsage(() -> Parser.parse(new String[] {"wibble"}));
        assertUsage(() -> Parser.parse(new String[] {"list", "loggers", "--nope"}));
    }

    // --- bare 'levels'/'handlers' verbs, fully retired (doc/specs/list-command-surface.md) ----

    @Test
    void bareLevelsVerbNoLongerExists() {
        assertUsage(() -> Parser.parse(new String[] {"levels"}));
        assertUsage(() -> Parser.parse(new String[] {"levels", "com.acme"}));
    }

    @Test
    void bareHandlersVerbNoLongerExists() {
        assertUsage(() -> Parser.parse(new String[] {"handlers"}));
    }

    // --- list (doc/specs/list-command-surface.md) ---------------------------------------------

    @Test
    void listHandlersTakesOnlyShowAll() {
        Parser.parse(new String[] {"list", "handlers"});                       // OK
        Parser.parse(new String[] {"list", "handlers", "--json"});             // OK
        Parser.parse(new String[] {"list", "handlers", "--show-all"});         // OK
        assertUsage(() -> Parser.parse(new String[] {"list", "handlers", "CONSOLE"}));
    }

    @Test
    void listUnknownNounIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"list"}));
        assertUsage(() -> Parser.parse(new String[] {"list", "everything"}));
    }

    @Test
    void listRulesTakesNoArguments() {
        Parser.parse(new String[] {"list", "rules"});           // OK
        Parser.parse(new String[] {"list", "rules", "--json"}); // OK
        assertUsage(() -> Parser.parse(new String[] {"list", "rules", "com.acme"}));
    }

    @Test
    void listRulesRejectsShowAll() {
        // doc/specs/rule-pipeline-foundation.md Decision #4 -- no distinct
        // meaning yet for --show-all on this noun.
        assertUsage(() -> Parser.parse(new String[] {"list", "rules", "--show-all"}));
    }

    @Test
    void levelNamedVerbsNoLongerExist() {
        // doc/specs/set-command-surface.md Decision #3 -- debug/trace/info/warn/error
        // are retired entirely, no alias kept; each names 'set logger' as the fix.
        assertUsage(() -> Parser.parse(new String[] {"debug"}));
        assertUsage(() -> Parser.parse(new String[] {"debug", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"trace", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"info", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"warn", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"error", "com.acme"}));
    }

    @Test
    void bareSetOfALoggerNameNoLongerExists() {
        // The bare "set <target> <level>" spelling is retired; "com.acme" isn't
        // one of the two recognized nouns (doc/specs/set-command-surface.md).
        assertUsage(() -> Parser.parse(new String[] {"set", "com.acme", "DEBUG"}));
        assertUsage(() -> Parser.parse(new String[] {"set"}));
    }

    @Test
    void unknownLevelForSetLoggerIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"set", "logger", "com.acme", "LOUD"}));
    }

    @Test
    void levelIsCaseInsensitive() {
        // Parses without throwing — the resolved Command is opaque, so "no exception" is the assertion.
        Parser.parse(new String[] {"set", "logger", "com.acme", "debug"});
    }

    @Test
    void pidMustBeANumber() {
        assertUsage(() -> Parser.parse(new String[] {"--pid", "abc", "status"}));
        assertEquals(1234L, Parser.parse(new String[] {"--pid", "1234", "status"}).pid());
    }

    @Test
    void reasonAndYesRejectedForNonMutatingCommands() {
        assertUsage(() -> Parser.parse(new String[] {"list", "loggers", "--reason", "x"}));
        assertUsage(() -> Parser.parse(new String[] {"status", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "com.acme", "--reason", "x"}));
    }

    @Test
    void yesIsAcceptedOnSetLoggerButNotSetHandler() {
        // doc/specs/pattern-level-targeting.md "Confirmation and CLI
        // behavior" -- Parser does no pattern detection of its own (same as
        // a plain logger name, the target string passes through untouched);
        // --yes just needs to not be rejected as out of place here.
        Parser.parse(new String[] {"set", "logger", "org.apache.*", "DEBUG", "--yes"});
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "--yes"}));
    }

    @Test
    void includeStickyOnlyAppliesToReset() {
        assertUsage(() -> Parser.parse(new String[] {"list", "loggers", "--include-sticky"}));
        Parser.parse(new String[] {"reset", "logger", "com.acme", "--include-sticky"}); // fine
    }

    @Test
    void showAllOnlyAppliesToList() {
        assertUsage(() -> Parser.parse(new String[] {"status", "--show-all"}));
        Parser.parse(new String[] {"list", "loggers", "--show-all"}); // fine
        Parser.parse(new String[] {"list", "handlers", "--show-all"}); // fine
    }

    // --- reset (doc/specs/reset-command-surface.md) -------------------------------------------

    @Test
    void resetAllNoLongerExists() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "--all"}));
    }

    @Test
    void bareResetOfALoggerNameNoLongerExists() {
        // The bare "reset <logger>" spelling is retired; "logger" isn't
        // recognized as a noun on its own, and "com.acme" isn't one of the
        // four recognized nouns either -- both are usage errors naming the
        // replacement (doc/specs/reset-command-surface.md).
        assertUsage(() -> Parser.parse(new String[] {"reset", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"reset"}));
    }

    @Test
    void resetLoggerNeedsExactlyOneTarget() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "logger"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "logger", "a", "b"}));
        Parser.parse(new String[] {"reset", "logger", "com.acme"}); // fine
        Parser.parse(new String[] {"reset", "logger", "com.acme", "--include-sticky"}); // fine
    }

    @Test
    void resetLoggersTakesNoArguments() {
        Parser.parse(new String[] {"reset", "loggers"}); // fine
        Parser.parse(new String[] {"reset", "loggers", "--include-sticky"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"reset", "loggers", "com.acme"}));
    }

    @Test
    void resetHandlerNeedsExactlyOneName() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "handler"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "handler", "CONSOLE", "FILE"}));
        Parser.parse(new String[] {"reset", "handler", "CONSOLE"}); // fine
        Parser.parse(new String[] {"reset", "handler", "CONSOLE", "--include-sticky"}); // fine
    }

    @Test
    void resetRuleNeedsExactlyOneId() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "rule"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "rule", "r1", "r2"}));
        Parser.parse(new String[] {"reset", "rule", "r1"}); // fine
        Parser.parse(new String[] {"reset", "rule", "r1", "--include-sticky"}); // fine
    }

    @Test
    void resetRulesTakesNoArguments() {
        Parser.parse(new String[] {"reset", "rules"}); // fine
        Parser.parse(new String[] {"reset", "rules", "--include-sticky"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"reset", "rules", "r1"}));
    }

    @Test
    void resetHandlersTakesNoArguments() {
        Parser.parse(new String[] {"reset", "handlers"}); // fine
        Parser.parse(new String[] {"reset", "handlers", "--include-sticky"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"reset", "handlers", "CONSOLE"}));
    }

    @Test
    void resetUnknownNounIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "everything"}));
    }

    @Test
    void listLoggersTakesAtMostOneFilter() {
        Parser.parse(new String[] {"list", "loggers"});
        Parser.parse(new String[] {"list", "loggers", "com.acme"});
        assertUsage(() -> Parser.parse(new String[] {"list", "loggers", "a", "b"}));
    }

    @Test
    void doctorTakesNoArguments() {
        Parser.parse(new String[] {"doctor"});
        Parser.parse(new String[] {"doctor", "--json"});
        assertUsage(() -> Parser.parse(new String[] {"doctor", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"doctor", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"doctor", "--reason", "x"}));
    }

    @Test
    void topTakesNoArguments() {
        Parser.parse(new String[] {"top"});
        Parser.parse(new String[] {"top", "--json"});
        Parser.parse(new String[] {"top", "--limit", "5"});
        assertUsage(() -> Parser.parse(new String[] {"top", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"top", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"top", "--reason", "x"}));
    }

    @Test
    void limitAppliesOnlyToTop() {
        assertUsage(() -> Parser.parse(new String[] {"doctor", "--limit", "5"}));
        assertUsage(() -> Parser.parse(new String[] {"status", "--limit", "5"}));
    }

    @Test
    void envTakesNoArguments() {
        Parser.parse(new String[] {"env"});
        Parser.parse(new String[] {"env", "--json"});
        assertUsage(() -> Parser.parse(new String[] {"env", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"env", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"env", "--reason", "x"}));
        assertUsage(() -> Parser.parse(new String[] {"env", "--limit", "5"}));
    }

    @Test
    void limitNeedsANumericValue() {
        assertUsage(() -> Parser.parse(new String[] {"top", "--limit", "soon"}));
        assertUsage(() -> Parser.parse(new String[] {"top", "--limit"}));
    }

    @Test
    void debugFlagIsCarried() {
        assertTrue(Parser.parse(new String[] {"--debug", "status"}).debug());
    }

    // --- bare 'handler' verb, fully retired (doc/specs/set-command-surface.md) ---------------

    @Test
    void bareHandlerVerbNoLongerExists() {
        assertUsage(() -> Parser.parse(new String[] {"handler"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "TRACE"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "AUTO"}));
    }

    @Test
    void bareHandlerResetNamesResetHandlerAsTheFix() {
        // doc/specs/reset-command-surface.md -- retired in favor of
        // "reset handler <name>", no alias kept (pre-1.0).
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "reset"}));
    }

    // --- set handler (doc/specs/set-command-surface.md, doc/specs/handler-floor-control.md) ----

    @Test
    void setHandlerNeedsANameAndALevel() {
        assertUsage(() -> Parser.parse(new String[] {"set", "handler"}));
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE"}));
    }

    @Test
    void setHandlerParsesWithBareTierAndWithATierToken() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE"}); // bare -- defaults to for 4h
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "session"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "for", "30m"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "sticky"});
    }

    @Test
    void setHandlerAcceptsReasonButNotYes() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "--reason", "INC-1"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "--yes"}));
    }

    @Test
    void unknownLevelForSetHandlerIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE", "LOUD"}));
    }

    // --- set handler AUTO (doc/specs/handler-floor-control.md "AUTO handler level", issue #20) ----

    @Test
    void setHandlerAutoParsesWithBareTierAndWithATierToken() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO"}); // bare -- defaults to for 4h
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "session"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "for", "30m"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "sticky"});
    }

    @Test
    void setHandlerAutoIsCaseInsensitive() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "auto"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "Auto"});
    }

    @Test
    void setHandlerAutoAcceptsReason() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "--reason", "INC-1"}); // fine
    }

    // --- set/reset default-handler (doc/specs/handler-floor-control.md "Default handler group", issue #28) ----

    @Test
    void setDefaultHandlerParsesWithNames() {
        Parser.parse(new String[] {"set", "default-handler", "FILE", "CONSOLE"});
        Parser.parse(new String[] {"set", "default-handler", "CONSOLE"});
    }

    @Test
    void resetDefaultHandlerParsesWithNoArguments() {
        Parser.parse(new String[] {"reset", "default-handler"});
    }

    @Test
    void setDefaultHandlerNeedsAtLeastOneName() {
        assertUsage(() -> Parser.parse(new String[] {"set", "default-handler"}));
    }

    @Test
    void resetDefaultHandlerRejectsArguments() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "default-handler", "CONSOLE"}));
    }

    @Test
    void setDefaultHandlerRejectsReasonAndYes() {
        // No --reason/tier token for this command (doc/specs/handler-floor-control.md
        // "Default handler group": always persisted, no lifetime to reason about).
        assertUsage(() -> Parser.parse(
                new String[] {"set", "default-handler", "CONSOLE", "--reason", "INC-1"}));
        assertUsage(() -> Parser.parse(new String[] {"set", "default-handler", "CONSOLE", "--yes"}));
    }

    // --- add rule drop (doc/specs/drop-rule.md) -------------------------------

    @Test
    void addRuleDropNeedsAContentMatcher() {
        assertUsage(() -> Parser.parse(new String[] {"add", "rule", "drop", "com.acme.Worker"}));
    }

    @Test
    void addRuleDropRejectsATrailingWildcard() {
        assertUsage(() -> Parser.parse(
                new String[] {"add", "rule", "drop", "com.acme.*", "--message-contains", "x"}));
    }

    @Test
    void addRuleDropRejectsBothMessageContainsForms() {
        assertUsage(() -> Parser.parse(new String[] {"add", "rule", "drop", "com.acme.Worker",
                "--message-contains", "x", "--message-contains-ignore-case", "y"}));
    }

    @Test
    void addRuleDropRejectsBothSampleFullForms() {
        assertUsage(() -> Parser.parse(new String[] {"add", "rule", "drop", "com.acme.Worker",
                "--message-contains", "x", "--sample-full", "5m", "--no-sample-full"}));
    }

    /**
     * A code-review finding against the first cut of this parser: an omitted
     * {@code --below} left {@code belowLevel} {@code null} all the way to
     * {@code CompiledMatchers.levelAtMost}, silently dropping the
     * spec-mandated ERROR keep-floor default entirely (doc/specs/
     * drop-rule.md "Safety set") instead of sparing ERROR and above.
     */
    @Test
    void addRuleDropOmittedBelow_defaultsToTheErrorKeepFloor_notUnbounded() {
        var mbean = new FakeLevelControlMXBean();
        mbean.addRuleDropResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "drop", "WARN", "noisy", false, null, null, false, null, "FOR", null, null,
                null, 0L, null, null);

        Invocation invocation = Parser.parse(
                new String[] {"add", "rule", "drop", "com.acme.Worker", "--message-contains", "noisy"});
        invocation.command().run(mbean, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                java.io.InputStream.nullInputStream(), false);

        // Index 6 is belowLevel in Commands.addRuleDrop's argument order.
        assertEquals("WARN", mbean.addRuleDropCalls.get(0)[6],
                "omitted --below must resolve to WARN (the level just more verbose than ERROR), "
                        + "not stay null/unbounded");
    }

    @Test
    void addRuleDropBelowFatal_resolvesToError() {
        var mbean = new FakeLevelControlMXBean();
        mbean.addRuleDropResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "drop", "ERROR", "noisy", false, null, null, false, null, "FOR", null, null,
                null, 0L, null, null);

        Invocation invocation = Parser.parse(new String[] {"add", "rule", "drop", "com.acme.Worker",
                "--message-contains", "noisy", "--below", "FATAL"});
        invocation.command().run(mbean, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                java.io.InputStream.nullInputStream(), false);

        assertEquals("ERROR", mbean.addRuleDropCalls.get(0)[6]);
    }

    // --- add rule trim (doc/specs/trim-rule.md) -------------------------------

    @Test
    void addRuleTrimNeedsNoContentMatcher_unlikeDrop() {
        // doc/specs/trim-rule.md "Matchers in use": a bare level-bounded trim is a first-class
        // case, unlike 'add rule drop'.
        var mbean = new FakeLevelControlMXBean();
        mbean.addRuleTrimResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "trim", "WARN", null, false, null, null, false, null, "FOR", null, null,
                null, 0L, 0, false);

        assertDoesNotThrow(() -> Parser.parse(new String[] {"add", "rule", "trim", "com.acme.Worker"}));
    }

    @Test
    void addRuleTrimRejectsATrailingWildcard() {
        assertUsage(() -> Parser.parse(new String[] {"add", "rule", "trim", "com.acme.*"}));
    }

    @Test
    void addRuleTrimOmittedBelow_defaultsToTheErrorKeepFloor_notUnbounded() {
        var mbean = new FakeLevelControlMXBean();
        mbean.addRuleTrimResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "trim", "WARN", null, false, null, null, false, null, "FOR", null, null,
                null, 0L, 0, false);

        Invocation invocation = Parser.parse(new String[] {"add", "rule", "trim", "com.acme.Worker"});
        invocation.command().run(mbean, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                java.io.InputStream.nullInputStream(), false);

        // Index 6 is belowLevel in Commands.addRuleTrim's argument order.
        assertEquals("WARN", mbean.addRuleTrimCalls.get(0)[6]);
        // Index 7 is frames -- defaults to 0 (bare one-liner) when --frames is omitted.
        assertEquals(0, mbean.addRuleTrimCalls.get(0)[7]);
    }

    @Test
    void addRuleTrimOmittedFrames_defaultsToZero() {
        var mbean = new FakeLevelControlMXBean();
        mbean.addRuleTrimResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "trim", "WARN", null, false, null, null, false, null, "FOR", null, null,
                null, 0L, 3, false);

        Invocation invocation = Parser.parse(
                new String[] {"add", "rule", "trim", "com.acme.Worker", "--frames", "3"});
        invocation.command().run(mbean, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                java.io.InputStream.nullInputStream(), false);

        assertEquals(3, mbean.addRuleTrimCalls.get(0)[7]);
    }

    @Test
    void addRuleTrimRejectsNegativeFrames() {
        assertUsage(() -> Parser.parse(
                new String[] {"add", "rule", "trim", "com.acme.Worker", "--frames", "-1"}));
    }

    @Test
    void addRuleTrimCollapseCauses_passedThrough() {
        var mbean = new FakeLevelControlMXBean();
        mbean.addRuleTrimResult = new org.logaperture.control.jmx.RuleData(
                "r1", "com.acme.Worker", "trim", "WARN", null, false, null, null, false, null, "FOR", null, null,
                null, 0L, 0, true);

        Invocation invocation = Parser.parse(
                new String[] {"add", "rule", "trim", "com.acme.Worker", "--collapse-causes"});
        invocation.command().run(mbean, new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                java.io.InputStream.nullInputStream(), false);

        // Index 8 is collapseCauses in Commands.addRuleTrim's argument order.
        assertEquals(true, mbean.addRuleTrimCalls.get(0)[8]);
    }

    @Test
    void frameOptions_applyOnlyToAddRuleTrim() {
        assertUsage(() -> Parser.parse(new String[] {"list", "rules", "--frames", "3"}));
        assertUsage(() -> Parser.parse(new String[] {"list", "rules", "--collapse-causes"}));
    }

    @Test
    void sampleFullOptions_applyOnlyToAddRuleDrop_notTrim() {
        assertUsage(() -> Parser.parse(
                new String[] {"add", "rule", "trim", "com.acme.Worker", "--sample-full", "5m"}));
        assertUsage(() -> Parser.parse(
                new String[] {"add", "rule", "trim", "com.acme.Worker", "--no-sample-full"}));
    }

    private static void assertUsage(Executable call) {
        CliError error = assertThrows(CliError.class, call);
        assertSame(CliError.class, error.getClass());
        assertEquals(CliError.USAGE, error.exitCode());
    }
}
