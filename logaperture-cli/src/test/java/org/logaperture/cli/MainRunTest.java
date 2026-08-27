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
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.throwOnNextCall = new RuntimeMBeanException(new CapabilityDeniedException(Capability.LEVEL_RAISE));

        assertEquals(6, run(new String[] {"debug", "com.acme"}, connectorFor(mbean)));
        assertTrue(err().contains("Refused: this JVM's policy does not grant LEVEL_RAISE."), err());
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

    /** A connector that must not be reached (help/version/usage paths never open a connection). */
    private static Connector unusableConnector() {
        return explicitPid -> {
            throw new AssertionError("connector should not have been called");
        };
    }
}
