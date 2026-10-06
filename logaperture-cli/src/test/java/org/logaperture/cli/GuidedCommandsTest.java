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
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.LoggerInfoData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guided {@code list} and {@code set} (doc/specs/guided-commands.md, slice (a)), driven through {@link Main#run}. */
class GuidedCommandsTest {

    private static final long FOUR_HOURS = 4 * 3600L;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();

    GuidedCommandsTest() {
        mbean.loggers = new ArrayList<>(List.of(
                logger("org.jboss.as.server.deployment.Deployer", "INFO", false),
                logger("com.acme.batch.Worker", "WARN", true),
                logger("com.acme.Worker", "INFO", false)));
        mbean.handlerCatalog = new ArrayList<>(List.of(
                handler("CONSOLE", "INFO", false, null),
                handler("FILE", "DEBUG", true, null),
                handler("ALL_HANDLERS", null, false, null),
                handler("DEFAULT_HANDLERS", null, false, "(auto: CONSOLE)")));
    }

    private static LoggerInfoData logger(String name, String level, boolean overridden) {
        return new LoggerInfoData(name, level, level, overridden, overridden ? "jmx" : null, null,
                overridden ? "SESSION" : null, null);
    }

    private static HandlerInfoData handler(String ref, String level, boolean overridden, String members) {
        return new HandlerInfoData(ref, level, false, null, null, overridden, overridden ? level : null,
                overridden ? "LEVEL" : null, overridden ? "SESSION" : null, null, members, null, null);
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

    // --- list (#2, #4, #5) ----------------------------------------------------------------------

    @Test
    void list_aTypedNameIsSearchedAsAShortNameAndImpliesShowAll() {
        int exit = guided(lines("l", "Deployer"), "list");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("List loggers, handlers, rules or recipes? [loggers/handlers/rules/recipes]"), out());
        assertTrue(out().contains("Command: logctl list loggers \"*.Deployer\" --show-all\n"), out());
        assertTrue(out().contains("org.jboss.as.server.deployment.Deployer"), "a logger with no override is listed: "
                + out());
        assertEquals("*.Deployer", mbean.listLoggersFilters.get(mbean.listLoggersFilters.size() - 1));
    }

    @Test
    void list_enterForTheFilterListsOverriddenLoggersOnly() {
        guided(lines("loggers", ""), "list");

        assertTrue(out().contains("Command: logctl list loggers\n"), out());
        assertTrue(out().contains("com.acme.batch.Worker"), out());
        assertFalse(out().contains("org.jboss.as.server.deployment.Deployer"), out());
    }

    @Test
    void list_aPrefixIsKeptAsGiven() {
        guided(lines("l", "com.acme"), "list");

        assertTrue(out().contains("Command: logctl list loggers com.acme --show-all\n"), out());
    }

    @Test
    void list_anInvalidFilterIsExplainedAndAskedAgain() {
        mbean.invalidFilters.add("*.bad*x");

        int exit = guided(lines("l", "*.bad*x", "Worker"), "list");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("invalid filter '*.bad*x'"), out());
        assertTrue(out().contains("Command: logctl list loggers \"*.Worker\" --show-all"), out());
    }

    @Test
    void list_handlersAndRules() {
        guided(lines("h"), "list");
        assertTrue(out().contains("Command: logctl list handlers\n"), out());

        guided(lines("rules"), "list");
        assertTrue(out().contains("Command: logctl list rules\n"), out());
    }

    @Test
    void list_anUnknownAnswerIsAskedAgain() {
        guided(lines("everything", "h"), "list");

        assertTrue(out().contains("Answer loggers, handlers, rules or recipes."), out());
        assertTrue(out().contains("Command: logctl list handlers"), out());
    }

    @Test
    void list_showAllGivenWithTheBareListIsKept() {
        guided(lines("h"), "list", "--show-all");

        assertTrue(out().contains("Command: logctl list handlers --show-all\n"), out());
    }

    @Test
    void list_withoutATerminal_isTheUsageErrorPlusTheHint() {
        assertEquals(CliError.USAGE, scripted("list"));

        assertTrue(err().contains("'list' needs 'loggers [filter]', 'handlers', 'rules', or 'recipes'."), err());
        assertTrue(err().contains(Parser.PROMPT_HINT), err());
    }

    @Test
    void list_withJson_neverAsks() {
        assertEquals(CliError.USAGE, guided(lines("l"), "list", "--json"));

        assertTrue(err().contains(Parser.PROMPT_HINT), err());
        assertFalse(out().contains("List loggers"), out());
    }

    @Test
    void list_endOfInputEndsQuietly() {
        assertEquals(CliError.OK, guided("", "list"));
    }

    // --- set logger (#6, #7) --------------------------------------------------------------------

    /** The spec's sample session. */
    @Test
    void setLogger_theSampleSession() {
        int exit = guided(lines("DEBUG", "", "chasing the redeploy loop", ""), "set", "logger", "Deployer");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("1 logger matches '*.Deployer': org.jboss.as.server.deployment.Deployer "
                + "(INFO, native)\n"), out());
        assertTrue(out().contains("This is the command:\n  logctl set logger org.jboss.as.server.deployment.Deployer "
                + "DEBUG --reason \"chasing the redeploy loop\"\nApply? [Y/n]\n"), out());
        assertArrayEquals(new Object[] {"org.jboss.as.server.deployment.Deployer", "DEBUG",
                "chasing the redeploy loop", "FOR", FOUR_HOURS, true}, mbean.setLevelCalls.get(0));
        assertTrue(out().contains("org.jboss.as.server.deployment.Deployer → DEBUG"), out());
    }

    @Test
    void set_aloneAsksWhichThenWalksThroughTheLogger() {
        int exit = guided(lines("l", "Worker", "1,2", "trace", "30m", "", ""), "set");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("Set a logger, a handler, or the default handlers? "
                + "[logger/handler/default-handler]"), out());
        assertTrue(out().contains("  1  com.acme.Worker       (INFO, native)\n"
                + "  2  com.acme.batch.Worker (WARN, override)\n"), out());
        assertTrue(out().contains("These are the commands:\n  logctl set logger com.acme.Worker TRACE for 30m\n"
                + "  logctl set logger com.acme.batch.Worker TRACE for 30m\n"), out());
        assertEquals(2, mbean.setLevelCalls.size());
        assertArrayEquals(new Object[] {"com.acme.batch.Worker", "TRACE", null, "FOR", 1800L, true},
                mbean.setLevelCalls.get(1));
    }

    @Test
    void setLogger_anInvalidLevelOrLifetimeIsExplainedAndAskedAgain() {
        guided(lines("LOUD", "WARN", "forever", "session", "", ""), "set", "logger", "com.acme.Worker");

        assertTrue(out().contains("Unknown level 'LOUD'"), out());
        assertTrue(out().contains("Unparseable duration 'forever'"), out());
        assertTrue(out().contains("  logctl set logger com.acme.Worker WARN session\n"), out());
        assertArrayEquals(new Object[] {"com.acme.Worker", "WARN", null, "SESSION", 0L, true},
                mbean.setLevelCalls.get(0));
    }

    @Test
    void setLogger_aReasonOnTheCommandLineIsNotAskedFor() {
        guided(lines("DEBUG", "sticky", ""), "set", "logger", "com.acme.Worker", "--reason", "INC-7");

        assertFalse(out().contains("Reason (optional)"), out());
        assertTrue(out().contains("  logctl set logger com.acme.Worker DEBUG sticky --reason INC-7\n"), out());
    }

    @Test
    void setLogger_aNameNoLoggerHasYetIsSetAfterAsking() {
        guided(lines("y", "DEBUG", "", "", ""), "set", "logger", "com.acme.NotYet");

        assertTrue(out().contains("No logger named com.acme.NotYet exists yet; set it anyway? [y/N]"), out());
        assertEquals("com.acme.NotYet", mbean.setLevelCalls.get(0)[0]);
    }

    @Test
    void setLogger_aTypedTrailingWildcard_picksFromTheSubtree() {
        // Issue #139: the pattern only builds the pick list; the picked exact name is what gets set.
        guided(lines("com.acme.*", "2", "DEBUG", "", "", ""), "set", "logger");

        assertTrue(out().contains("2 loggers match 'com.acme.*'"), out());
        assertFalse(out().contains("trailing '.*'"), out());
        assertEquals(1, mbean.setLevelCalls.size());
        assertEquals("com.acme.batch.Worker", mbean.setLevelCalls.get(0)[0]);
    }

    @Test
    void setLogger_anIncompleteCommandsTrailingWildcard_picksFromTheSubtree() {
        guided(lines("1", "DEBUG", "", "", ""), "set", "logger", "*.acme.*");

        assertEquals("com.acme.Worker", mbean.setLevelCalls.get(0)[0]);
    }

    @Test
    void setLogger_aPackageWithLoggersUnderIt_isSetWithoutAsking() {
        // Issue #140: com.acme isn't a logger itself (as org.wildfly often isn't just after a restart).
        guided(lines("DEBUG", "", "", "n", ""), "set", "logger", "com.acme"); // n: don't force (#142)

        assertTrue(out().contains("com.acme isn't a logger itself yet; 2 loggers under it inherit from it "
                + "(e.g. com.acme.Worker)."), out());
        assertFalse(out().contains("exists yet;"), out());
        assertEquals("com.acme", mbean.setLevelCalls.get(0)[0]);
    }

    @Test
    void setLogger_aNameThatIsOnlyAPrefixOfLoggers_stillAsks() {
        // com.acme.Worker starts with "com.ac" but isn't under it.
        guided(lines("n", ""), "set", "logger", "com.ac");

        assertTrue(out().contains("No logger named com.ac exists yet; set it anyway? [y/N]"), out());
        assertTrue(mbean.setLevelCalls.isEmpty());
    }

    @Test
    void setLogger_declinedOrEndOfInputAppliesNothing() {
        guided(lines("DEBUG", "", "", "n"), "set", "logger", "com.acme.Worker");
        assertTrue(out().contains("Not applied."), out());

        guided(lines("DEBUG"), "set", "logger", "com.acme.Worker");
        assertTrue(mbean.setLevelCalls.isEmpty());
    }

    @Test
    void setLogger_severalLoggersOneRefused_theOthersAreSetAndTheExitCodeIsTheFailures() {
        mbean.refused.add("com.acme.Worker");

        int exit = guided(lines("all", "DEBUG", "", "", ""), "set", "logger", "*.Worker");

        assertEquals(CliError.USAGE, exit);
        assertTrue(out().contains("com.acme.batch.Worker → DEBUG"), out());
        assertTrue(err().contains("com.acme.Worker: logctl: 'com.acme.Worker' is protected"), err());
    }

    /** #1: the printed command, run as-is, makes exactly the call the guided answers made. */
    @Test
    void setLogger_thePrintedCommandParsesBackToTheSameCall() {
        guided(lines("1", "ERROR", "for 2h", "it's \"quoted\" $HOME", ""), "set", "logger", "*.Worker");
        String printed = out().lines().filter(l -> l.startsWith("  logctl set logger")).findFirst().orElseThrow();
        List<String> argv = RuleExpressionTest.shellSplit(printed.trim().substring("logctl ".length()));

        FakeLevelControlMXBean replay = new FakeLevelControlMXBean();
        Connector connectToReplay = explicitPid -> new ControlPlane() {
            @Override
            public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                return replay;
            }

            @Override
            public void close() {
            }
        };
        Main.run(argv.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), connectToReplay, java.io.InputStream.nullInputStream(),
                false);

        assertArrayEquals(mbean.setLevelCalls.get(0), replay.setLevelCalls.get(0));
    }

    @Test
    void setLogger_withoutATerminalOrWithYes_isTheUsageErrorPlusTheHint() {
        assertEquals(CliError.USAGE, scripted("set", "logger", "com.acme.Worker"));
        assertTrue(err().contains("'set logger' needs <target> <level>"), err());
        assertTrue(err().contains(Parser.PROMPT_HINT), err());

        assertEquals(CliError.USAGE, guided(lines("DEBUG"), "set", "logger", "com.acme.Worker", "--yes"));
        assertTrue(mbean.setLevelCalls.isEmpty());
    }

    @Test
    void set_withoutATerminal_isTheUsageErrorPlusTheHint() {
        assertEquals(CliError.USAGE, scripted("set"));

        assertTrue(err().contains("'set' needs 'logger <target> <level>'"), err());
        assertTrue(err().contains(Parser.PROMPT_HINT), err());
    }

    // --- set handler (#8) -----------------------------------------------------------------------

    @Test
    void setHandler_picksFromTheCatalogAndAcceptsAuto() {
        int exit = guided(lines("1", "auto", "sticky", "", ""), "set", "handler");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("  1  CONSOLE           INFO\n  2  FILE              DEBUG, override\n"
                + "  3  ALL_HANDLERS      every handler\n  4  DEFAULT_HANDLERS  now (auto: CONSOLE)\n"), out());
        assertTrue(out().contains("  logctl set handler CONSOLE AUTO sticky\n"), out());
        assertArrayEquals(new Object[] {"CONSOLE", null, "STICKY", 0L}, mbean.setHandlerAutoCalls.get(0));
    }

    @Test
    void setHandler_namedOnTheCommandLineIsNotPicked() {
        guided(lines("WARN", "", "", ""), "set", "handler", "FILE");

        assertFalse(out().contains("Which?"), out());
        assertArrayEquals(new Object[] {"FILE", "WARN", null, "FOR", FOUR_HOURS}, mbean.setHandlerLevelCalls.get(0));
    }

    @Test
    void setHandler_severalOneRefused_theOthersAreSet() {
        mbean.refused.add("CONSOLE");

        int exit = guided(lines("1-2", "DEBUG", "", "", ""), "set", "handler");

        assertEquals(CliError.USAGE, exit);
        assertEquals(2, mbean.setHandlerLevelCalls.size());
        assertTrue(err().contains("CONSOLE: logctl: 'CONSOLE' is protected"), err());
    }

    @Test
    void setHandler_aFrameworkWithoutHandlerLevels_saysSo() {
        mbean.handlerCatalog = new ArrayList<>();

        assertEquals(CliError.OK, guided(lines(), "set", "handler"));

        assertTrue(out().contains("handlers have no level of their own; nothing to set."), out());
    }

    // --- set default-handler (#9) ---------------------------------------------------------------

    @Test
    void setDefaultHandler_showsTheMembershipAndPicksTheNewOne() {
        int exit = guided(lines("d", "1,2", ""), "set");

        assertEquals(CliError.OK, exit, err());
        assertTrue(out().contains("DEFAULT_HANDLERS is now: (auto: CONSOLE)\n"), out());
        assertFalse(out().contains("ALL_HANDLERS"), "the pseudo-handlers can't be members: " + out());
        assertTrue(out().contains("  logctl set default-handler CONSOLE FILE\n"), out());
        assertEquals(List.of(List.of("CONSOLE", "FILE")), mbean.setDefaultHandlerMembersCalls);
    }

    @Test
    void setDefaultHandler_withoutATerminal_keepsItsUsageErrorPlusTheHint() {
        assertEquals(CliError.USAGE, scripted("set", "default-handler"));

        assertTrue(err().contains("use 'reset default-handler' to clear it."), err());
        assertTrue(err().contains(Parser.PROMPT_HINT), err());
    }
}
