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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discovery finding a service's JVM from an elevated prompt (doc/specs/service-account-access.md). */
class DiscoveryWindowsTest {

    private static final String JAVA_ROWS = "\"java.exe\",\"7100\",\"Services\",\"0\",\"7,656,664 K\"\r\n";

    @Test
    void aProbeThatDoesNotAnswerInTime_isGivenUpOn_andCountsAsNotInspected() {
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        Discovery.Probe probe = Discovery.probeWithin(1, () -> {
            never.await();
            return Discovery.Probe.NOT_APPLICABLE;
        });
        assertTrue(probe.failed());
        assertEquals(null, probe.candidate());
        never.countDown();
    }

    @Test
    void aProbeThatAnswers_returnsItsOutcome() {
        Discovery.Probe answer = Discovery.Probe.of(candidate(7100L, "x"));
        assertEquals(answer, Discovery.probeWithin(5, () -> answer));
    }

    private static Discovery.Candidate candidate(long pid, String name) {
        return new Discovery.Candidate(pid, name, "1.0.0-beta.3", null, "C:\\app");
    }

    /** JVMs by PID: a candidate, or null for a JVM without our agent; absent means the attach is refused. */
    private static final class FakeJvms implements Discovery.Jvms {
        final List<Discovery.Listed> listed = new ArrayList<>();
        final Map<Long, Discovery.Candidate> agents = new java.util.HashMap<>();
        final List<Long> probed = new ArrayList<>();
        final java.util.Set<Long> withoutAgent = new java.util.HashSet<>();

        @Override
        public List<Discovery.Listed> listed() {
            return listed;
        }

        @Override
        public Discovery.Probe probe(long pid, String displayName) {
            probed.add(pid);
            if (agents.containsKey(pid)) {
                return Discovery.Probe.of(agents.get(pid));
            }
            return withoutAgent.contains(pid) ? Discovery.Probe.NOT_APPLICABLE : Discovery.Probe.FAILED;
        }
    }

    private static HostProcesses windows(HostProcesses.Runner runner) {
        return new HostProcesses(true, pid -> false, runner, Path.of("no-such-windows-root"), 99L);
    }

    private static HostProcesses linux() {
        return new HostProcesses(false, pid -> true, c -> {
            throw new AssertionError("no command should run off Windows");
        }, Path.of("/"), 99L);
    }

    @Test
    void onWindows_aServiceJvmTheJdkDoesNotList_isFound() {
        FakeJvms jvms = new FakeJvms();
        jvms.agents.put(7100L, candidate(7100L, "org.jboss.modules.Main"));
        Discovery.Found found = Discovery.find(windows(c -> JAVA_ROWS), jvms);
        assertEquals(List.of(candidate(7100L, "org.jboss.modules.Main")), found.candidates());
        assertEquals(0, found.uninspectable());
    }

    @Test
    void aJvmInBothTheListAndTasklist_isProbedOnce() {
        FakeJvms jvms = new FakeJvms();
        jvms.listed.add(new Discovery.Listed(7100L, "org.jboss.modules.Main"));
        jvms.agents.put(7100L, candidate(7100L, "org.jboss.modules.Main"));
        Discovery.Found found = Discovery.find(windows(c -> JAVA_ROWS), jvms);
        assertEquals(1, found.candidates().size());
        assertEquals(List.of(7100L), jvms.probed);
    }

    @Test
    void javaProcessesWithoutTheAgent_areIgnored_notCountedAsUninspectable() {
        FakeJvms jvms = new FakeJvms();
        jvms.withoutAgent.add(7100L);
        Discovery.Found found = Discovery.find(windows(c -> JAVA_ROWS), jvms);
        assertTrue(found.candidates().isEmpty());
        assertEquals(0, found.uninspectable());
    }

    @Test
    void aRefusedAttach_isCountedAsUninspectable() {
        Discovery.Found found = Discovery.find(windows(c -> JAVA_ROWS), new FakeJvms());
        assertTrue(found.candidates().isEmpty());
        assertEquals(1, found.uninspectable());
    }

    @Test
    void theCliOwnJvm_isNeverProbed() {
        FakeJvms jvms = new FakeJvms();
        jvms.listed.add(new Discovery.Listed(99L, "org.logaperture.cli.Main"));
        Discovery.find(windows(c -> "\"java.exe\",\"99\",\"Console\",\"1\",\"1 K\"\r\n"), jvms);
        assertTrue(jvms.probed.isEmpty());
    }

    @Test
    void offWindows_onlyTheJdkListIsUsed() {
        FakeJvms jvms = new FakeJvms();
        jvms.listed.add(new Discovery.Listed(41_822L, "com.acme.App"));
        jvms.agents.put(41_822L, candidate(41_822L, "com.acme.App"));
        Discovery.Found found = Discovery.find(linux(), jvms);
        assertEquals(List.of(41_822L), jvms.probed);
        assertEquals(1, found.candidates().size());
    }

    @Test
    void severalMatches_stillAskWhichOrNeedPid() {
        FakeJvms jvms = new FakeJvms();
        jvms.listed.add(new Discovery.Listed(41_822L, "com.acme.App"));
        jvms.agents.put(41_822L, candidate(41_822L, "com.acme.App"));
        jvms.agents.put(7100L, candidate(7100L, "org.jboss.modules.Main"));
        CliError e = assertThrows(CliError.class,
                () -> Discovery.resolveTargetPid(null, null, windows(c -> JAVA_ROWS), jvms));
        assertEquals(CliError.AMBIGUOUS, e.exitCode());
        assertTrue(e.getMessage().contains("--pid"));
    }

    @Test
    void onWindows_noneFound_namesPidForAnotherAccountsJvm() {
        CliError e = assertThrows(CliError.class,
                () -> Discovery.resolveTargetPid(null, null, windows(c -> ""), new FakeJvms()));
        assertEquals(CliError.NO_JVM, e.exitCode());
        assertTrue(e.getMessage().startsWith("No LogAperture-enabled JVM found."));
        assertTrue(e.getMessage().contains("Windows service"));
        assertTrue(e.getMessage().contains("--pid"));
    }

    @Test
    void offWindows_noneFound_hasNoWindowsHint() {
        CliError e = assertThrows(CliError.class,
                () -> Discovery.resolveTargetPid(null, null, linux(), new FakeJvms()));
        assertFalse(e.getMessage().contains("Windows"));
    }

    @Test
    void anExplicitPid_theJdkCannotOpenButTasklistLists_isUsed() {
        assertEquals(7100L, Discovery.resolveTargetPid(7100L, null, windows(c -> JAVA_ROWS), new FakeJvms()));
    }

    @Test
    void anExplicitPid_nothingLists_isNoProcess() {
        CliError e = assertThrows(CliError.class,
                () -> Discovery.resolveTargetPid(31_337L, null, windows(c -> JAVA_ROWS), new FakeJvms()));
        assertEquals("No process with PID 31337.", e.getMessage());
    }

    // --- the attach failure message --------------------------------------------------------------------

    @Test
    void attachRefused_onWindows_tellsAdministratorsWhatToDo() {
        CliError e = AgentConnection.attachFailure(7100L, new java.io.IOException("denied"), windows(c -> JAVA_ROWS));
        assertEquals(CliError.ATTACH_DENIED, e.exitCode());
        assertTrue(e.getMessage().contains("Administrator"), e.getMessage());
    }

    @Test
    void attachRefused_elsewhere_keepsTheOwnerOrRootAdvice() {
        CliError e = AgentConnection.attachFailure(7100L, new java.io.IOException("denied"), linux());
        assertTrue(e.getMessage().contains("run as the user that owns that process, or as root"), e.getMessage());
    }

    @Test
    void attachFailed_forAProcessThatIsGone_saysNoProcess() {
        HostProcesses gone = new HostProcesses(false, pid -> false, c -> null, Path.of("/"), 99L);
        CliError e = AgentConnection.attachFailure(7100L, new java.io.IOException("x"), gone);
        assertEquals(CliError.NO_JVM, e.exitCode());
    }
}
