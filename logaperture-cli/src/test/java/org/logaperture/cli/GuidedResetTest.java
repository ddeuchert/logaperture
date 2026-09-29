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

/** Guided {@code reset} (doc/specs/guided-commands.md #11–#15, slice (b)), driven through {@link Main#run}. */
class GuidedResetTest {

    private static final String DEPLOYER = "org.jboss.as.server.deployment.Deployer";

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();

    /*
     * The list this fixture produces:
     *   Loggers           1 com.acme.Vendored (vendor baseline)  2 com.acme.batch (sticky)  3 DEPLOYER
     *   Handlers          4 CONSOLE
     *   Rules             5 r12  6 vendor:quiet-perfmon (altered)  7 vendor:other (unaltered)
     *   Default handlers  8 CONSOLE, FILE
     */
    GuidedResetTest() {
        String inThreeHours = Instant.now().plus(3, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES).toString();
        mbean.loggers = new ArrayList<>(List.of(
                new LoggerInfoData(DEPLOYER, "INFO", "DEBUG", true, "jmx", null, "FOR", inThreeHours),
                new LoggerInfoData("com.acme.batch", "INFO", "TRACE", true, "jmx", null, "STICKY", null),
                new LoggerInfoData("com.acme.Quiet", "INFO", "INFO", false, null, null, null, null),
                new LoggerInfoData("com.acme.Vendored", "INFO", "WARN", true, "jmx", null, "SESSION", null, null,
                        "INFO")));
        mbean.handlerCatalog = new ArrayList<>(List.of(
                new HandlerInfoData("CONSOLE", "DEBUG", false, null, null, true, "DEBUG", "LEVEL", "SESSION", null,
                        null, null, null),
                new HandlerInfoData("FILE", "INFO", false, null, null, false, null, null, null, null, null, null,
                        null),
                new HandlerInfoData("DEFAULT_HANDLERS", null, false, null, null, false, null, null, null, null,
                        "CONSOLE, FILE", null, null)));
        mbean.rules = new ArrayList<>(List.of(
                rule("r12", "trim", DEPLOYER, "FOR", inThreeHours, null, false),
                rule("vendor:quiet-perfmon", "drop", "com.acme.perf", "SESSION", null, "vendor", true),
                rule("vendor:other", "drop", "com.acme.other", null, null, "vendor", false)));
    }

    private static RuleData rule(String id, String action, String logger, String tier, String expiresAt,
            String origin, boolean altered) {
        return new RuleData(id, logger, action, "WARN", "x", false, null, null, false, null, tier, expiresAt, null,
                null, 0L, null, null, origin, false, null, null, altered);
    }

    private void withVendorDefaultsFile() {
        mbean.environmentReport = new EnvironmentReportData("0.1.0", "21", "Adoptium", "Linux", "6", "x86_64", null,
                null, null, null, null, null, "/etc/app/vendor-defaults.yaml", "loaded (1 logger)");
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

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

    // --- the list (#11, #12) --------------------------------------------------------------------

    @Test
    void reset_listsEverythingChangedGroupedAndResetsThePicks() {
        int exit = guided(lines("3,5", ""), "reset");

        assertEquals(CliError.OK, exit, err());
        String text = out();
        assertTrue(text.contains("Currently changed:\n  Loggers\n"
                + "    1  com.acme.Vendored                        WARN   session\n"
                + "    2  com.acme.batch                           TRACE  sticky\n"
                + "    3  org.jboss.as.server.deployment.Deployer  DEBUG  reverts in 3h\n"
                + "  Handlers\n    4  CONSOLE  DEBUG  session\n"
                + "  Rules\n"), text);
        assertTrue(text.contains("    7  vendor:other          drop com.acme.other                           vendor\n"), text);
        assertTrue(text.contains("  Default handlers\n    8  CONSOLE, FILE  kept across restarts\n"), text);
        assertFalse(text.contains("com.acme.Quiet"), "a logger with nothing changed isn't listed: " + text);
        assertTrue(text.contains("These are the commands:\n  logctl reset rule r12\n  logctl reset logger "
                + DEPLOYER + "\nApply? [Y/n]\n"), "rules first -- a logger's reset removes its rules: " + text);
        assertEquals(2, mbean.resetCalls.size());
        assertArrayEquals(new Object[] {"rule", "r12", false, false}, mbean.resetCalls.get(0));
        assertArrayEquals(new Object[] {"logger", DEPLOYER, false, false}, mbean.resetCalls.get(1));
    }

    @Test
    void resetRule_withoutAnId_listsOnlyRules() {
        guided(lines("1", ""), "reset", "rule");

        assertTrue(out().contains("Currently changed:\n  Rules\n    1  r12 "), out());
        assertFalse(out().contains("Loggers"), out());
        assertArrayEquals(new Object[] {"rule", "r12", false, false}, mbean.resetCalls.get(0));
    }

    @Test
    void resetLogger_withNothingOverridden_saysSo() {
        mbean.loggers = new ArrayList<>();

        assertEquals(CliError.OK, guided(lines(), "reset", "logger"));

        assertTrue(out().contains("No logger is overridden; nothing to reset."), out());
    }

    @Test
    void reset_withNothingChanged_saysSo() {
        mbean.loggers = new ArrayList<>();
        mbean.handlerCatalog = new ArrayList<>();
        mbean.rules = new ArrayList<>();

        guided(lines(), "reset");

        assertTrue(out().contains("Nothing is changed; nothing to reset."), out());
    }

    @Test
    void reset_theDefaultHandlersMembership() {
        guided(lines("8", ""), "reset");

        assertTrue(out().contains("  logctl reset default-handler\n"), out());
        assertEquals(List.of(List.of()), mbean.setDefaultHandlerMembersCalls);
    }

    @Test
    void reset_theAutomaticPickIsNotAChange() {
        mbean.handlerCatalog = new ArrayList<>(List.of(new HandlerInfoData("DEFAULT_HANDLERS", null, false, null,
                null, false, null, null, null, null, "(auto: CONSOLE)", null, null)));

        guided(lines(""), "reset");

        assertFalse(out().contains("Default handlers"), out());
    }

    // --- sticky (#13) ---------------------------------------------------------------------------

    @Test
    void reset_aStickyPickIsConfirmedAndGetsIncludeSticky() {
        guided(lines("2", "y", ""), "reset");

        assertTrue(out().contains("This is sticky -- it is kept across restarts. Reset it anyway? [y/N]"), out());
        assertTrue(out().contains("  logctl reset logger com.acme.batch --include-sticky\n"), out());
        assertArrayEquals(new Object[] {"logger", "com.acme.batch", true, false}, mbean.resetCalls.get(0));
    }

    @Test
    void reset_declinedStickyIsLeftOutOfTheSelection() {
        guided(lines("2,3", "", ""), "reset");

        assertTrue(out().contains("1 of these is sticky -- it is kept across restarts. Reset it too? [y/N]"), out());
        assertTrue(out().contains("Leaving 1 sticky item as it is."), out());
        assertTrue(out().contains("This is the command:\n  logctl reset logger " + DEPLOYER + "\n"), out());
        assertEquals(1, mbean.resetCalls.size());
    }

    @Test
    void reset_declinedStickyAlone_appliesNothing() {
        guided(lines("2", "n"), "reset");

        assertTrue(out().contains("Not applied."), out());
        assertTrue(mbean.resetCalls.isEmpty());
    }

    @Test
    void reset_includeStickyOnTheCommandLineIsNotAsked() {
        guided(lines("2", ""), "reset", "--include-sticky");

        assertFalse(out().contains("sticky -- it is kept"), out());
        assertArrayEquals(new Object[] {"logger", "com.acme.batch", true, false}, mbean.resetCalls.get(0));
    }

    // --- vendor defaults or native (#14, #15) ---------------------------------------------------

    @Test
    void reset_withAVendorDefaultsFile_asksVendorOrNativeForAVendorBasedPick() {
        withVendorDefaultsFile();

        guided(lines("1,3", "native", ""), "reset");

        assertTrue(out().contains("Go back to the vendor defaults, or to the application's own configuration until "
                + "restart? [vendor/native] (Enter: vendor)"), out());
        assertTrue(out().contains("  logctl reset logger com.acme.Vendored --to-native\n  logctl reset logger "
                + DEPLOYER + "\n"), "only the vendor-based line gets --to-native: " + out());
        assertArrayEquals(new Object[] {"logger", "com.acme.Vendored", false, true}, mbean.resetCalls.get(0));
        assertArrayEquals(new Object[] {"logger", DEPLOYER, false, false}, mbean.resetCalls.get(1));
    }

    @Test
    void reset_vendorIsTheDefaultAnswer() {
        withVendorDefaultsFile();

        guided(lines("1", "", ""), "reset");

        assertTrue(out().contains("  logctl reset logger com.acme.Vendored\n"), out());
        assertArrayEquals(new Object[] {"logger", "com.acme.Vendored", false, false}, mbean.resetCalls.get(0));
    }

    @Test
    void reset_withoutAVendorDefaultsFile_neverAsks() {
        guided(lines("1", ""), "reset");

        assertFalse(out().contains("vendor/native"), out());
    }

    @Test
    void reset_aNonVendorPick_neverAsks() {
        withVendorDefaultsFile();

        guided(lines("3", ""), "reset");

        assertFalse(out().contains("vendor/native"), out());
    }

    @Test
    void reset_anUnalteredVendorRuleAnsweredVendor_isLeftAsItIs() {
        withVendorDefaultsFile();

        int exit = guided(lines("7", ""), "reset");

        assertEquals(CliError.OK, exit);
        assertTrue(out().contains("rule vendor:other is already the vendor's definition.\nNothing to reset."), out());
        assertTrue(mbean.resetCalls.isEmpty());
    }

    @Test
    void reset_anUnalteredVendorRuleAnsweredNative_isSwitchedOff() {
        withVendorDefaultsFile();

        guided(lines("7", "n", ""), "reset");

        assertTrue(out().contains("  logctl reset rule vendor:other --to-native\n"), out());
        assertArrayEquals(new Object[] {"rule", "vendor:other", false, true}, mbean.resetCalls.get(0));
    }

    @Test
    void reset_toNativeOnTheCommandLineIsNotAsked() {
        withVendorDefaultsFile();

        guided(lines("1", ""), "reset", "--to-native");

        assertFalse(out().contains("vendor/native"), out());
        assertTrue(out().contains("  logctl reset logger com.acme.Vendored --to-native\n"), out());
    }

    // --- applying, and not ------------------------------------------------------------------------

    @Test
    void reset_severalOneRefused_theOthersAreResetAndTheExitCodeIsTheFailures() {
        mbean.refused.add(DEPLOYER);

        int exit = guided(lines("3,4", ""), "reset");

        assertEquals(CliError.USAGE, exit);
        assertEquals(2, mbean.resetCalls.size());
        assertTrue(out().contains("handler CONSOLE → reset to its previous level."), out());
        assertTrue(err().contains(DEPLOYER + ": logctl: '" + DEPLOYER + "' is protected"), err());
    }

    @Test
    void reset_declinedOrCancelledAppliesNothing() {
        guided(lines("3", "n"), "reset");
        guided(lines(""), "reset");
        guided("", "reset");

        assertTrue(mbean.resetCalls.isEmpty());
        assertTrue(out().contains("Not applied."), out());
    }

    /** #1: each printed command, run as-is, makes exactly the call the guided answers made. */
    @Test
    void reset_thePrintedCommandsParseBackToTheSameCalls() {
        withVendorDefaultsFile();
        guided(lines("1-2", "y", "native", ""), "reset");
        List<String> printed = out().lines().filter(l -> l.startsWith("  logctl reset")).toList();

        FakeLevelControlMXBean replay = new FakeLevelControlMXBean();
        replay.loggers = mbean.loggers;
        Connector connectToReplay = explicitPid -> new ControlPlane() {
            @Override
            public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                return replay;
            }

            @Override
            public void close() {
            }
        };
        for (String line : printed) {
            List<String> argv = RuleExpressionTest.shellSplit(line.trim().substring("logctl ".length()));
            Main.run(argv.toArray(String[]::new), new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream()), connectToReplay, java.io.InputStream.nullInputStream(),
                    false);
        }

        assertEquals(2, replay.resetCalls.size());
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(mbean.resetCalls.get(i), replay.resetCalls.get(i));
        }
    }

    @Test
    void reset_withoutATerminal_isTheUsageErrorPlusTheHint() {
        assertEquals(CliError.USAGE, scripted("reset"));
        assertTrue(err().contains("'reset' needs 'logger <target>'"), err());
        assertTrue(err().contains(Parser.PROMPT_HINT), err());

        assertEquals(CliError.USAGE, scripted("reset", "logger"));
        assertTrue(err().contains("'reset logger' needs exactly one target.\n" + Parser.PROMPT_HINT), err());
    }

    @Test
    void resetLogger_withTwoTargets_isStillAPlainUsageError() {
        assertEquals(CliError.USAGE, guided(lines(), "reset", "logger", "a", "b"));

        assertTrue(err().contains("'reset logger' needs exactly one target.\n"), err());
        assertFalse(err().contains(Parser.PROMPT_HINT), err());
    }

    @Test
    void reset_withJson_neverAsks() {
        assertEquals(CliError.USAGE, guided(lines("1"), "reset", "--json"));

        assertFalse(out().contains("Currently changed"), out());
    }
}
