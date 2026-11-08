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

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/logctl-attach.md #4: what each way a load can fail tells the operator. */
class VmAttacherTest {

    private static final long LIVE = ProcessHandle.current().pid();

    @Test
    void dynamicLoadingTurnedOffIsAnAttachDeniedWithTheWayOut() {
        CliError error = VmAttacher.loadFailure(LIVE, new AgentLoadException(
                "Dynamic agent loading is not enabled. Use -XX:+EnableDynamicAgentLoading to launch target VM."));

        assertEquals(CliError.ATTACH_DENIED, error.exitCode());
        assertTrue(error.getMessage().contains("-XX:-EnableDynamicAgentLoading"), error.getMessage());
        assertTrue(error.getMessage().contains("-javaagent:logaperture-agent.jar"), error.getMessage());
        assertTrue(error.getMessage().contains("-XX:+EnableDynamicAgentLoading"), "names the future-default caveat");
    }

    @Test
    void anotherLoadErrorNamesTheJar() {
        CliError error = VmAttacher.loadFailure(LIVE, new AgentLoadException("boom"));
        assertEquals(CliError.UNEXPECTED, error.exitCode());
        assertTrue(error.getMessage().contains("could not load the agent jar: boom"));
    }

    @Test
    void anAgentThatFailsToStartIsSaid() {
        CliError error = VmAttacher.loadFailure(LIVE, new AgentInitializationException("agentmain threw"));
        assertEquals(CliError.UNEXPECTED, error.exitCode());
        assertTrue(error.getMessage().contains("failed to start: agentmain threw"));
    }

    @Test
    void aProcessThatIsGoneIsExitThree() {
        CliError error = VmAttacher.loadFailure(Long.MAX_VALUE, new IOException("gone"));
        assertEquals(CliError.NO_JVM, error.exitCode());
    }

    /** A Windows host whose JDK view cannot see {@code pid} but whose {@code tasklist} lists it, or does not. */
    private static HostProcesses windowsHost(String tasklistOutput) {
        return new HostProcesses(true, pid -> false, command -> tasklistOutput, Path.of("no-such-root"), 99L);
    }

    @Test
    void aRunningProcessTheJdkCannotOpen_isAnAttachProblem_notNoSuchProcess() {
        HostProcesses host = windowsHost("\"java.exe\",\"7100\",\"Services\",\"0\",\"7,656,664 K\"\n");
        CliError error = VmAttacher.loadFailure(7100, new IOException("Access is denied"), host);
        assertEquals(CliError.ATTACH_DENIED, error.exitCode());
        assertTrue(error.getMessage().contains("Administrator"), error.getMessage());
    }

    @Test
    void whenTasklistCannotRun_theAttachReportsItsOwnOutcome() {
        CliError error = VmAttacher.loadFailure(7100, new IOException("Access is denied"), windowsHost(null));
        assertEquals(CliError.ATTACH_DENIED, error.exitCode());
    }

    @Test
    void aProcessTasklistDoesNotListIsGone() {
        CliError error = VmAttacher.loadFailure(7100, new IOException("gone"),
                windowsHost("\"java.exe\",\"4242\",\"Services\",\"0\",\"1 K\"\n"));
        assertEquals(CliError.NO_JVM, error.exitCode());
    }

    @Test
    void otherAccountsJvms_areTheTasklistOnesTheJdkListDidNotName_withoutThisProcess() {
        String tasklist = "\"java.exe\",\"7100\",\"Services\",\"0\",\"1 K\"\n"
                + "\"javaw.exe\",\"7200\",\"Console\",\"1\",\"1 K\"\n"
                + "\"java.exe\",\"99\",\"Console\",\"1\",\"1 K\"\n"
                + "\"notepad.exe\",\"7300\",\"Console\",\"1\",\"1 K\"\n";
        assertEquals(List.of(7100L), VmAttacher.unlisted(Set.of(7200L), windowsHost(tasklist)));
    }

    @Test
    void probingOtherAccountsJvms_skipsOnesThatCannotBeAttached_andNamesNoOneTwice() {
        String tasklist = "\"java.exe\",\"7100\",\"Services\",\"0\",\"1 K\"\n"
                + "\"java.exe\",\"7200\",\"Services\",\"0\",\"1 K\"\n"
                + "\"java.exe\",\"7300\",\"Console\",\"1\",\"1 K\"\n";
        List<Long> asked = new ArrayList<>();
        VmAttacher.PidProbe probe = pid -> {
            asked.add(pid);
            if (pid == 7200) {
                throw new IOException("Access is denied");
            }
            return new JvmAttacher.Target(pid, "", null, null, null, null);
        };
        List<JvmAttacher.Target> found = VmAttacher.probeUnlisted(Set.of(7300L), windowsHost(tasklist), probe);
        assertEquals(List.of(7100L, 7200L), asked, "7300 was already listed by the JDK");
        assertEquals(1, found.size());
        assertEquals(7100L, found.get(0).pid());
        assertEquals("", found.get(0).displayName());
    }

    @Test
    void offWindows_noOtherAccountsJvmsAreAdded() {
        HostProcesses linux = new HostProcesses(false, pid -> true, command -> "x", Path.of("/"), 99L);
        assertTrue(VmAttacher.unlisted(Set.of(), linux).isEmpty());
    }

    @Test
    void theEmptyListMessage_namesTheWindowsServiceCaseOnWindowsOnly() {
        assertTrue(AttachCommand.noJvmMessage(true).contains("Windows service"));
        assertTrue(AttachCommand.noJvmMessage(true).contains("as Administrator"));
        assertTrue(!AttachCommand.noJvmMessage(false).contains("Windows"));
        assertTrue(AttachCommand.noJvmMessage(false).startsWith("No JVM to attach to: none is running as this user"));
    }

    @Test
    void anIoErrorOnALiveProcessIsAPermissionProblem() {
        CliError error = VmAttacher.loadFailure(LIVE, new IOException("Permission denied"));
        assertEquals(CliError.ATTACH_DENIED, error.exitCode());
        assertTrue(error.getMessage().contains("run as the user that owns that process"));
    }

    @Test
    void theMissingModuleMessageSaysToUseAJdk() {
        assertTrue(VmAttacher.noJdkAttach().getMessage().contains("JDK, not a JRE"));
    }

    @Test
    void javaFeatureVersions() {
        assertEquals(17, VmAttacher.javaFeatureOf("17"));
        assertEquals(21, VmAttacher.javaFeatureOf("21"));
        assertEquals(8, VmAttacher.javaFeatureOf("1.8"));
        assertEquals(0, VmAttacher.javaFeatureOf(null));
        assertEquals(0, VmAttacher.javaFeatureOf("x"));
    }

    @Test
    void aPollThatFailsAfterTheLoadIsLoadedNotConfirmedNotAFailure() throws Exception {
        int[] calls = {0};
        VmAttacher.Properties_ dropsOnTheSecondAsk = () -> {
            if (++calls[0] == 2) {
                throw new IOException("connection reset");
            }
            return new java.util.Properties();
        };

        JvmAttacher.Loaded loaded = VmAttacher.awaitMarker(dropsOnTheSecondAsk, java.time.Duration.ofSeconds(5), 1);

        assertEquals(null, loaded.version());
        assertEquals(2, calls[0]);
    }

    @Test
    void theMarkerEndsThePollAtOnce() throws Exception {
        java.util.Properties up = new java.util.Properties();
        up.setProperty(Discovery.MARKER_PROPERTY, "1.1.0");
        up.setProperty("java.specification.version", "21");

        JvmAttacher.Loaded loaded = VmAttacher.awaitMarker(() -> up, java.time.Duration.ofSeconds(5), 1);

        assertEquals("1.1.0", loaded.version());
        assertEquals(21, loaded.javaVersion());
    }

    @Test
    void noMarkerWithinTheWaitIsLoadedNotActive() throws Exception {
        JvmAttacher.Loaded loaded = VmAttacher.awaitMarker(java.util.Properties::new, java.time.Duration.ofMillis(50), 5);
        assertEquals(null, loaded.version());
    }
}
