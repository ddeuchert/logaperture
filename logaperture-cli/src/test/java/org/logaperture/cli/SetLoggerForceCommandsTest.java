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
import org.logaperture.control.jmx.DescendantLevelData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code logctl set logger --force} -- doc/specs/set-logger-force.md, driven through {@link Main#run}. */
class SetLoggerForceCommandsTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();

    SetLoggerForceCommandsTest() {
        mbean.loggers = new ArrayList<>(List.of(
                logger("com.acme", null, false, null),
                logger("com.acme.other", "INFO", false, null),
                logger("com.acme.db.Pool", "WARN", false, null),
                logger("com.acme.plain", null, false, null)));
    }

    private static LoggerInfoData logger(String name, String configured, boolean overridden, String forcedBy) {
        return new LoggerInfoData(name, configured, configured == null ? "INFO" : configured, overridden,
                overridden ? "jmx" : null, null, overridden ? "SESSION" : null, null, null, null, false, null,
                forcedBy);
    }

    private static LevelOverrideData override(String name, String level, String forcedBy) {
        return new LevelOverrideData(name, level, null, "2026-10-03T00:00:00Z", "jmx", "SESSION", null, forcedBy);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private int run(String answers, boolean terminal, String... args) {
        return Main.run(args, out, err, explicitPid -> new ControlPlane() {
            @Override
            public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                return mbean;
            }

            @Override
            public void close() {
            }
        }, new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8)), terminal);
    }

    private static String lines(String... answers) {
        return String.join("\n", answers) + "\n";
    }

    @Test
    void force_isPassedThrough_andForcedAndKeptLoggersAreShown() {
        mbean.setLevelResult = new SetLevelResultData(
                List.of(override("com.acme", "TRACE", null), override("com.acme.other", "TRACE", "com.acme")),
                List.of(), List.of(new DescendantLevelData("com.acme.batch", "DEBUG", "OPERATOR_OVERRIDE")));

        assertEquals(CliError.OK, run("", false, "set", "logger", "com.acme", "TRACE", "--force"));

        assertEquals(List.of(true), mbean.forceCalls);
        assertTrue(out().contains("com.acme.other → TRACE   (SESSION — until the JVM stops, forced)"), out());
        assertTrue(out().contains("Kept: com.acme.batch (DEBUG, set by you)"), out());
        assertFalse(out().contains("NOTE:"), out());
    }

    @Test
    void aPlainSet_notesTheLoggersThatDidntFollow_withTheForceCommand() {
        mbean.setLevelResult = new SetLevelResultData(List.of(override("com.acme", "TRACE", null)), List.of(),
                List.of(new DescendantLevelData("com.acme.db.Pool", "WARN", "OWN_LEVEL"),
                        new DescendantLevelData("com.acme.other", "INFO", "OWN_LEVEL")));

        run("", false, "set", "logger", "com.acme", "TRACE", "sticky");

        assertTrue(mbean.forceCalls.isEmpty(), "a plain set uses the operation older agents have too");
        assertTrue(out().contains("NOTE: 2 loggers under com.acme keep their own level and won't follow TRACE: "
                + "com.acme.db.Pool (WARN), com.acme.other (INFO)."), out());
        assertTrue(out().contains("To set them too: logctl set logger com.acme TRACE sticky --force"), out());
    }

    @Test
    void theNote_namesAtMostFive() {
        List<DescendantLevelData> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            many.add(new DescendantLevelData("com.acme.l" + i, "INFO", "OWN_LEVEL"));
        }
        mbean.setLevelResult = new SetLevelResultData(List.of(override("com.acme", "TRACE", null)), List.of(), many);

        run("", false, "set", "logger", "com.acme", "TRACE");

        assertTrue(out().contains("com.acme.l5 (INFO), … and 3 more."), out());
        assertFalse(out().contains("com.acme.l6"), out());
    }

    @Test
    void anEarlierForce_isNamedAsSuchInTheNote() {
        mbean.setLevelResult = new SetLevelResultData(List.of(override("com.acme", "DEBUG", null)), List.of(),
                List.of(new DescendantLevelData("com.acme.other", "TRACE", "FORCED_EARLIER")));

        run("", false, "set", "logger", "com.acme", "DEBUG");

        assertTrue(out().contains("com.acme.other (TRACE, forced earlier)"), out());
    }

    @Test
    void guided_asksToForceTheLoggersThatKeepTheirOwnLevel_defaultYes() {
        run(lines("TRACE", "", "", "", ""), true, "set", "logger", "com.acme");

        assertTrue(out().contains("2 loggers under com.acme have their own level and won't follow TRACE:"), out());
        assertTrue(out().contains("com.acme.db.Pool  WARN"), out());
        assertTrue(out().contains("Set them to TRACE too? [Y/n]"), out());
        assertTrue(out().contains("logctl set logger com.acme TRACE --force"), out());
        assertEquals(List.of(true), mbean.forceCalls);
    }

    @Test
    void guided_answeringNo_setsJustTheTarget() {
        run(lines("TRACE", "", "", "n", ""), true, "set", "logger", "com.acme");

        assertFalse(out().contains("--force"), out());
        assertTrue(mbean.forceCalls.isEmpty());
    }

    @Test
    void guided_noQuestion_whenNothingKeepsItsOwnLevel_orForceWasGiven() {
        run(lines("WARN", "", "", ""), true, "set", "logger", "com.acme.db"); // Pool is already at WARN
        assertFalse(out().contains("too? [Y/n]"), out());

        outBytes.reset();
        run(lines("TRACE", "", "", ""), true, "set", "logger", "com.acme", "--force");
        assertFalse(out().contains("too? [Y/n]"), out());
        assertEquals(List.of(true), mbean.forceCalls);
    }

    @Test
    void guided_countsAndListsAnEarlierForceAtADifferentLevel() {
        mbean.loggers = new ArrayList<>(List.of(logger("com.acme", null, false, null),
                logger("com.acme.other", "TRACE", true, "com.acme")));

        run(lines("DEBUG", "", "", "", ""), true, "set", "logger", "com.acme");

        assertTrue(out().contains("1 logger under com.acme has its own level and won't follow DEBUG:"), out());
    }

    @Test
    void reset_showsTheForcedLoggersItPutBack_andTheStickyOnesItLeft() {
        mbean.exactResetOutcome = new org.logaperture.control.jmx.ResetOutcomeData(
                List.of("com.acme", "com.acme.other"), List.of("com.acme.db.Pool"));

        run("", false, "reset", "logger", "com.acme");

        assertTrue(out().contains("com.acme.other → INFO"), out());
        assertTrue(out().contains("(forced by com.acme)"), out());
        assertTrue(out().contains("Left 1 sticky forced override(s) in place (pass --include-sticky to include them): "
                + "com.acme.db.Pool"), out());
        assertFalse(out().contains("nothing was overridden"), out());
    }

    @Test
    void guided_offersAVendorDefaultedDescendant_byItsVendorLevel() {
        mbean.loggers = new ArrayList<>(List.of(logger("com.acme", null, false, null),
                new LoggerInfoData("com.acme.vendored", null, "WARN", false, null, null, null, null, null, "WARN",
                        false, null, null)));

        run(lines("TRACE", "", "", "n", ""), true, "set", "logger", "com.acme");

        assertTrue(out().contains("com.acme.vendored  WARN"), out());
    }

    @Test
    void forceOnAnotherCommand_isAUsageError() {
        for (String[] argv : new String[][] {
                {"set", "handler", "CONSOLE", "DEBUG", "--force"},
                {"reset", "logger", "com.acme", "--force"}}) {
            CliError error = assertThrows(CliError.class, () -> Parser.parse(argv), String.join(" ", argv));
            assertEquals(CliError.USAGE, error.exitCode());
            assertTrue(error.getMessage().contains("'set logger'"), error.getMessage());
        }
    }

    @Test
    void listLoggers_showsWhoForcedAnOverride() {
        mbean.loggers = new ArrayList<>(List.of(logger("com.acme.other", "INFO", true, "com.acme")));

        run("", false, "list", "loggers", "com.acme");

        assertTrue(out().contains("forced by com.acme"), out());
    }

    @Test
    void json_carriesTheDescendants() {
        mbean.setLevelResult = new SetLevelResultData(List.of(override("com.acme", "TRACE", null)), List.of(),
                List.of(new DescendantLevelData("com.acme.other", "INFO", "OWN_LEVEL")));

        run("", false, "set", "logger", "com.acme", "TRACE", "--json");

        assertTrue(out().contains("\"descendants\":[{\"loggerName\":\"com.acme.other\",\"level\":\"INFO\","
                + "\"kind\":\"OWN_LEVEL\"}]"), out());
    }
}
