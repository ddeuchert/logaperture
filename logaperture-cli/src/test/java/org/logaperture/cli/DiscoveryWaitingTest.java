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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/wait-for-log-manager.md #8: discovery names waiting JVMs when there is no JVM to use. */
class DiscoveryWaitingTest {

    private final Map<Long, Discovery.Probe> jvms = new LinkedHashMap<>();

    @Test
    void withNoCandidate_everyWaitingJvmIsNamed_inPidOrder() {
        jvms.put(20L, Discovery.Probe.WAITING);
        jvms.put(10L, Discovery.Probe.WAITING);
        jvms.put(30L, Discovery.Probe.NOT_APPLICABLE);

        CliError error = assertThrows(CliError.class, this::resolve);

        assertEquals(CliError.NO_JVM, error.exitCode());
        String message = error.getMessage();
        assertTrue(message.contains("PID 10 is waiting for the application to set up java.util.logging"), message);
        assertTrue(message.indexOf("PID 10 ") < message.indexOf("PID 20 "), message);
        assertFalse(message.contains("PID 30"), message);
    }

    @Test
    void aWaitingJvmIsNotACandidate() {
        jvms.put(10L, Discovery.Probe.WAITING);
        jvms.put(20L, Discovery.Probe.of(new Discovery.Candidate(20, "app", "1.1.0", null, "/w")));

        assertEquals(20L, resolve());
    }

    private long resolve() {
        HostProcesses linux = new HostProcesses(false, pid -> true, command -> {
            throw new AssertionError("no command should run off Windows");
        }, Path.of("/"), 99L);
        Discovery.Jvms fake = new Discovery.Jvms() {
            @Override
            public List<Discovery.Listed> listed() {
                List<Discovery.Listed> listed = new ArrayList<>();
                jvms.keySet().forEach(pid -> listed.add(new Discovery.Listed(pid, "app")));
                return listed;
            }

            @Override
            public Discovery.Probe probe(long pid, String displayName) {
                return jvms.get(pid);
            }
        };
        return Discovery.resolveTargetPid(null, null, linux, fake);
    }
}
