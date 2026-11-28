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

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/wait-for-log-manager.md #7: logctl leaves alone a JVM whose agent is waiting for the log manager. */
class AgentConnectionWaitingTest {

    @Test
    void refusesAJvmThatIsStillWaiting() {
        Properties target = new Properties();
        target.setProperty("logaperture.waiting", "java.util.logging");

        CliError error = assertThrows(CliError.class, () -> AgentConnection.refuseWhileWaiting(42, target));

        assertEquals(CliError.NO_JVM, error.exitCode());
        assertTrue(error.getMessage().contains("PID 42 is not active yet"), error.getMessage());
    }

    @Test
    void connectsOnceTheMarkerIsSet() {
        Properties target = new Properties();
        target.setProperty("logaperture.waiting", "java.util.logging");
        target.setProperty(Discovery.MARKER_PROPERTY, "1.1.0");

        assertDoesNotThrow(() -> AgentConnection.refuseWhileWaiting(42, target));
    }

    @Test
    void connectsToAJvmThatNeverWaited() {
        assertDoesNotThrow(() -> AgentConnection.refuseWhileWaiting(42, new Properties()));
    }
}
