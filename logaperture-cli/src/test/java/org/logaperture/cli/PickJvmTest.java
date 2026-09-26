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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Picking the JVM when several are running (doc/specs/pick-jvm.md). */
class PickJvmTest {

    private static final Discovery.Candidate WILDFLY = new Discovery.Candidate(52_310L,
            "org.jboss.modules.Main -mp /opt/wildfly-26/modules org.jboss.as.standalone", "0.1.0-alpha.3",
            Instant.parse("2026-09-26T13:02:00Z"), "/opt/wildfly-26");
    private static final Discovery.Candidate APP = new Discovery.Candidate(41_822L, "com.acme.App", "0.1.0-alpha.3",
            null, null);

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private Prompter answering(String answers) {
        return new Prompter(new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)), err);
    }

    // --- choosing (J3-J6) -----------------------------------------------------------------------

    @Test
    void aListNumber_picksThatRow_inPidOrder() {
        assertEquals(52_310L, Discovery.choose(List.of(WILDFLY, APP), answering("2\n")));
        assertTrue(err().contains("2 LogAperture JVMs are running:"), err());
        assertTrue(err().contains("Using PID 52310 — pass --pid 52310 to skip this question."), err());
        int first = err().indexOf("41822");
        int second = err().indexOf("52310");
        assertTrue(first >= 0 && first < second, "rows in PID order: " + err());
    }

    @Test
    void aListedPid_picksThatJvm() {
        assertEquals(52_310L, Discovery.choose(List.of(WILDFLY, APP), answering("52310\n")));
    }

    @Test
    void anAnswerNotInTheList_isAskedAgain() {
        assertEquals(41_822L, Discovery.choose(List.of(WILDFLY, APP), answering("3\nwildfly\n1\n")));
        assertTrue(err().contains("'3' isn't in the list -- answer 1 to 2, or one of the PIDs shown."), err());
        assertTrue(err().contains("'wildfly' isn't in the list"), err());
    }

    @Test
    void enterOrEndOfInput_isExitFour_noJvmChosen() {
        for (String answers : List.of("\n", "")) {
            CliError e = assertThrows(CliError.class, () -> Discovery.choose(List.of(WILDFLY, APP), answering(answers)));
            assertEquals(CliError.AMBIGUOUS, e.exitCode());
            assertEquals("No JVM chosen.", e.getMessage());
        }
    }

    @Test
    void theQuestion_showsStartedAndDirectory_andQuestionMarksForWhatIsUnknown() {
        Discovery.choose(List.of(WILDFLY, APP), answering("1\n"));

        assertTrue(err().contains("#  PID    VERSION        STARTED           DIRECTORY        COMMAND"), err());
        assertTrue(err().contains("/opt/wildfly-26"), err());
        assertTrue(err().contains("41822  0.1.0-alpha.3  ?                 ?"), err());
    }

    @Test
    void aLongCommand_isShortenedToFitTheLine() {
        Discovery.Candidate verbose = new Discovery.Candidate(1_000L, "x".repeat(300), "v", null, "/d");
        Discovery.choose(List.of(verbose, APP), answering("1\n"));

        for (String line : err().lines().toList()) {
            assertTrue(line.length() <= Discovery.QUESTION_WIDTH, line.length() + ": " + line);
        }
        assertTrue(err().contains("…"), err());
    }

    // --- not asking (J1) ------------------------------------------------------------------------

    @Test
    void oneCandidate_isUsedWithoutAQuestion() {
        assertEquals(41_822L, Discovery.choose(List.of(APP), answering("")));
        assertEquals("", err());
    }

    @Test
    void withNothingToAskOn_severalIsTheTable_withTheNewColumns() {
        CliError e = assertThrows(CliError.class, () -> Discovery.choose(List.of(WILDFLY, APP), null));
        assertEquals(CliError.AMBIGUOUS, e.exitCode());
        assertTrue(e.getMessage().startsWith("PID    VERSION        STARTED           DIRECTORY        COMMAND"),
                e.getMessage());
        assertTrue(e.getMessage().contains(WILDFLY.displayName()), "the full command: " + e.getMessage());
        assertTrue(e.getMessage().endsWith("Several candidates — pass --pid <n>."), e.getMessage());
    }

    /** Records the prompter {@link Main} hands to discovery, then connects to a fake. */
    private static final class RecordingConnector implements Connector {
        final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        final List<Prompter> prompters = new ArrayList<>();
        List<Discovery.Candidate> candidates;

        @Override
        public ControlPlane connect(Long explicitPid) {
            return connect(explicitPid, null);
        }

        @Override
        public ControlPlane connect(Long explicitPid, Prompter jvmQuestion) {
            prompters.add(jvmQuestion);
            if (candidates != null && explicitPid == null) {
                Discovery.choose(candidates, jvmQuestion);
            }
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
    }

    private int run(RecordingConnector connector, String answers, boolean interactive, String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8));
        return Main.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8), err, connector, in, interactive);
    }

    @Test
    void mainOffersTheQuestion_onlyOnATerminal_withoutJsonOrYes() {
        RecordingConnector connector = new RecordingConnector();
        connector.mbean.loggers = List.of();

        run(connector, "", true, "list", "loggers");
        run(connector, "", false, "list", "loggers");
        run(connector, "", true, "list", "loggers", "--json");
        run(connector, "", true, "set", "logger", "com.acme.Worker", "INFO", "--yes");

        assertFalse(connector.prompters.get(0) == null, "terminal, no --json/--yes: may ask");
        assertNull(connector.prompters.get(1), "no terminal");
        assertNull(connector.prompters.get(2), "--json");
        assertNull(connector.prompters.get(3), "--yes");
    }

    /** J9: the JVM question, then guided add rule's own questions, from one input stream. */
    @Test
    void theJvmQuestion_thenGuidedAddRulesQuestions_shareOneInput() {
        RecordingConnector connector = new RecordingConnector();
        connector.candidates = List.of(WILDFLY, APP);
        connector.mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData("com.acme.Worker", "INFO", "INFO", false, null, null, null, null)));

        int exit = run(connector, String.join("\n", "2", "trim", "", "", "", "", "", "", "", "", "") + "\n", true,
                "add", "rule", "com.acme.Worker");

        assertEquals(CliError.OK, exit, err());
        assertTrue(err().contains("Using PID 52310"), err());
        assertEquals("com.acme.Worker", connector.mbean.addRuleTrimCalls.get(0)[0]);
    }

    // --- terminal detection (J8) ----------------------------------------------------------------

    /** Stands in for a JDK 22+ console; public so the reflective call can reach it. */
    public static final class ConsoleWithIsTerminal {
        private final boolean terminal;

        ConsoleWithIsTerminal(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean isTerminal() {
            return terminal;
        }
    }

    @Test
    void isTerminal_followsConsoleIsTerminalWhereItExists() {
        assertFalse(Main.isTerminal(null), "no console");
        assertTrue(Main.isTerminal(new Object()), "a console without isTerminal (before JDK 22) is a terminal");
        assertTrue(Main.isTerminal(new ConsoleWithIsTerminal(true)));
        assertFalse(Main.isTerminal(new ConsoleWithIsTerminal(false)), "JDK 22-24 with redirected input");
    }
}
