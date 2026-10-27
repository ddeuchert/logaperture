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
}
