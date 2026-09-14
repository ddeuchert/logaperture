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

import javax.management.RuntimeMBeanException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exit-code contract from doc/specs/cli-transport.md "Output and exit codes", driven through a fake {@link Connector}. */
class MainRunTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private int run(String[] args, Connector connector) {
        return Main.run(args, out, err, connector);
    }

    private static Connector connectorFor(FakeLevelControlMXBean mbean) {
        return explicitPid -> new ControlPlane() {
            @Override
            public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                return mbean;
            }

            @Override
            public void close() {
            }
        };
    }

    private static Connector connectorThrowing(CliError error) {
        return explicitPid -> {
            throw error;
        };
    }

    @Test
    void helpIsExitZeroOnStdout() {
        assertEquals(0, run(new String[] {"--help"}, unusableConnector()));
        assertTrue(out().contains("Usage:"));
        assertTrue(err().isEmpty());
    }

    @Test
    void versionIsExitZero() {
        assertEquals(0, run(new String[] {"--version"}, unusableConnector()));
        assertTrue(out().startsWith("logctl "));
    }

    @Test
    void noArgumentsIsUsageExitTwoWithHelpOnStderr() {
        assertEquals(2, run(new String[] {}, unusableConnector()));
        assertTrue(err().contains("No command given."));
        assertTrue(err().contains("Usage:"));
    }

    @Test
    void unparseableDurationIsUsageExitTwo() {
        assertEquals(2, run(new String[] {"debug", "com.acme", "for", "soon"}, unusableConnector()));
        assertTrue(err().contains("Unparseable duration"));
    }

    @Test
    void noCandidateJvmIsExitThree() {
        assertEquals(3, run(new String[] {"status"}, connectorThrowing(
                new CliError(CliError.NO_JVM, "No LogAperture-enabled JVM found."))));
        assertTrue(err().contains("No LogAperture-enabled JVM found."));
    }

    @Test
    void ambiguousIsExitFour() {
        assertEquals(4, run(new String[] {"status"}, connectorThrowing(
                new CliError(CliError.AMBIGUOUS, "Several candidates — pass --pid <n>."))));
    }

    @Test
    void attachDeniedIsExitFive() {
        assertEquals(5, run(new String[] {"status"}, connectorThrowing(
                new CliError(CliError.ATTACH_DENIED, "Can't attach to PID 42 — run as its owner or root."))));
    }

    @Test
    void capabilityDenialIsExitSixAndNamesTheCapability() {
        // The real transport (AgentConnection's JMX.newMXBeanProxy) unwraps a
        // RuntimeMBeanException and rethrows the original exception directly
        // -- CapabilityDeniedException arrives here unwrapped, not as this
        // wrapped shape. Covered for real by capabilityDenialUnwrappedIsExitSix
        // below; kept here as coverage of the defensive fallback in Main's
        // RuntimeMBeanException catch, in case some path does still wrap it.
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.throwOnNextCall = new RuntimeMBeanException(new CapabilityDeniedException(Capability.LEVEL_RAISE));

        assertEquals(6, run(new String[] {"debug", "com.acme"}, connectorFor(mbean)));
        assertTrue(err().contains("Refused: this JVM's policy does not grant LEVEL_RAISE."), err());
    }

    @Test
    void capabilityDenialUnwrappedIsExitSix() {
        // What the real proxy transport actually hands back (see the comment
        // above) -- a bare CapabilityDeniedException, never wrapped.
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.throwOnNextCall = new CapabilityDeniedException(Capability.LEVEL_RAISE);

        assertEquals(6, run(new String[] {"debug", "com.acme"}, connectorFor(mbean)));
        assertTrue(err().contains("Refused: this JVM's policy does not grant LEVEL_RAISE."), err());
    }

    @Test
    void invalidFilterFromTheServerIsExitTwoNotOne() {
        // What the real proxy transport actually hands back -- a bare
        // IllegalArgumentException, never wrapped (see the comment on
        // capabilityDenialIsExitSixAndNamesTheCapability above). A NameFilter
        // rejection is server-side validation of a bad argument, not an
        // unexpected failure -- doc/specs/cli-transport.md's "usage error
        // naming the problem" applies here too, even though it only surfaces
        // after the command itself parsed successfully.
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.throwOnNextCall = new IllegalArgumentException("invalid filter 'org.*apache': bad");

        assertEquals(2, run(new String[] {"levels", "org.*apache"}, connectorFor(mbean)));
        assertTrue(err().contains("logctl: invalid filter 'org.*apache': bad"), err());
    }

    @Test
    void invalidFilterWrappedIsStillExitTwo() {
        // Coverage of the defensive fallback in Main's RuntimeMBeanException
        // catch, in case some path does still hand this back wrapped.
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.throwOnNextCall =
                new RuntimeMBeanException(new IllegalArgumentException("invalid filter 'org.*apache': bad"));

        assertEquals(2, run(new String[] {"levels", "org.*apache"}, connectorFor(mbean)));
        assertTrue(err().contains("logctl: invalid filter 'org.*apache': bad"), err());
    }

    @Test
    void anyOtherServerExceptionIsExitOne() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.throwOnNextCall = new RuntimeMBeanException(new IllegalStateException("boom"));

        assertEquals(1, run(new String[] {"status"}, connectorFor(mbean)));
        assertTrue(err().contains("logctl: boom"));
    }

    @Test
    void unexpectedClientExceptionIsExitOne() {
        Connector broken = explicitPid -> {
            throw new IllegalStateException("attach layer blew up");
        };
        assertEquals(1, run(new String[] {"status"}, broken));
        assertTrue(err().contains("logctl: attach layer blew up"));
    }

    @Test
    void happyPathReturnsTheCommandsExitCode() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.loggers = List.of(new LoggerInfoData("a", "INFO", "INFO", false, null, null, null, null));
        assertEquals(0, run(new String[] {"levels"}, connectorFor(mbean)));
        assertTrue(out().contains("LOGGER"));
    }

    // --- handler <name> AUTO (doc/specs/handler-floor-control.md "AUTO handler level", issue #20) --------------

    @Test
    void handlerAuto_dispatchesToSetHandlerAuto_notSetHandlerLevel() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.setHandlerAutoResult = new org.logaperture.control.jmx.HandlerLevelOverrideData(
                "CONSOLE", "DEBUG", "AUTO", null, "2026-09-12T00:00:00Z", "jmx", "SESSION", null);

        assertEquals(0, run(new String[] {"handler", "CONSOLE", "AUTO"}, connectorFor(mbean)));

        assertEquals(1, mbean.setHandlerAutoCalls.size());
        Object[] call = mbean.setHandlerAutoCalls.get(0); // {handlerRef, reason, tier, forSeconds}
        assertEquals("CONSOLE", call[0]);
        assertEquals("FOR", call[2], "a bare AUTO defaults to 'for 4h', same as every other tier grammar here");
        assertEquals(java.time.Duration.ofHours(4).toSeconds(), call[3]);
        assertEquals(0, mbean.setHandlerLevelCalls.size(), "must not be parsed as a literal level named AUTO");
        assertTrue(out().contains("AUTO"), out());
    }

    @Test
    void handlerAuto_isCaseInsensitiveAndAcceptsATierAndAReason() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.setHandlerAutoResult = new org.logaperture.control.jmx.HandlerLevelOverrideData(
                "CONSOLE", "TRACE", "AUTO", "INC-1", "2026-09-12T00:00:00Z", "jmx", "STICKY", null);

        assertEquals(0, run(new String[] {"handler", "CONSOLE", "auto", "sticky", "--reason", "INC-1"},
                connectorFor(mbean)));

        Object[] call = mbean.setHandlerAutoCalls.get(0);
        assertEquals("CONSOLE", call[0]);
        assertEquals("INC-1", call[1]);
        assertEquals("STICKY", call[2]);
    }

    @Test
    void handlerAuto_adapterHasNothingToTrack_printsTheNoOpNote() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.setHandlerAutoResult = null; // no level of its own, or nothing active to track yet

        assertEquals(0, run(new String[] {"handler", "CONSOLE", "AUTO"}, connectorFor(mbean)));

        assertTrue(out().contains("nothing to change"), out());
    }

    // --- setLevel on a pattern: the confirmation-prompt flow through Main itself (doc/specs/
    // pattern-level-targeting.md "Confirmation and CLI behavior") -----------------------------

    @Test
    void patternWithoutYes_nonInteractive_isUsageErrorNamingTheFix() {
        // The default 4-arg run() derives `interactive` from System.console(),
        // which is null in this (and every) test/CI environment -- so this
        // is reachable through the production entry point as-is, unlike the
        // interactive prompt-and-read test below.
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();

        assertEquals(2, run(new String[] {"debug", "org.acme.*"}, connectorFor(mbean)));

        String error = err();
        assertTrue(error.contains("--yes"), error);
        assertTrue(error.contains("standing rule"), error);
        assertEquals(0, mbean.setLevelCalls.size(), "must not have called the server at all");
    }

    @Test
    void patternWithoutYes_interactive_promptsAndAppliesOnAConfirmingAnswer() {
        // Code-review finding: without the 6-arg run(..., in, interactive)
        // seam below, no test could reach this branch through Main at all --
        // `interactive` has no other injection point than System.console().
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.loggers = List.of(new LoggerInfoData("org.acme.Worker", "INFO", "INFO", false, null, null, null, null));
        mbean.setLevelResult = new org.logaperture.control.jmx.SetLevelResultData(
                List.of(new org.logaperture.control.jmx.LevelOverrideData(
                        "org.acme.Worker", "DEBUG", "org.acme.*", null, "2026-09-13T00:00:00Z", "jmx", "STICKY",
                        null)),
                List.of());
        java.io.InputStream typedYes = new java.io.ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8));

        int exitCode = Main.run(new String[] {"debug", "org.acme.*", "sticky"}, out, err, connectorFor(mbean),
                typedYes, true);

        assertEquals(0, exitCode);
        assertTrue(out().contains("Apply this standing rule?"), out());
        assertTrue(out().contains("org.acme.Worker → DEBUG"), out());
        Object[] call = mbean.setLevelCalls.get(0); // {target, level, reason, tier, forSeconds, confirmed}
        assertEquals(true, call[5], "the typed 'y' resolved confirmed=true on the real call to the server");
    }

    @Test
    void patternWithoutYes_interactive_declinedAnswerAppliesNothing() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.loggers = List.of(new LoggerInfoData("org.acme.Worker", "INFO", "INFO", false, null, null, null, null));
        java.io.InputStream typedNo = new java.io.ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8));

        int exitCode = Main.run(new String[] {"debug", "org.acme.*"}, out, err, connectorFor(mbean), typedNo, true);

        assertEquals(0, exitCode);
        assertTrue(out().contains("Not applied."), out());
        assertEquals(0, mbean.setLevelCalls.size(), "declining the prompt must never call the server");
    }

    /** A connector that must not be reached (help/version/usage paths never open a connection). */
    private static Connector unusableConnector() {
        return explicitPid -> {
            throw new AssertionError("connector should not have been called");
        };
    }
}
