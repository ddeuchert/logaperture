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
package org.logaperture.container.quarkus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkusContainerIntegrationTest {

    private static QuarkusContainerIntegration with(boolean entryPoint, String command, String classPath) {
        return new QuarkusContainerIntegration(Duration.ofMinutes(1), () -> command, () -> classPath, () -> entryPoint);
    }

    @Test
    void id_isQuarkus() {
        assertEquals("quarkus", new QuarkusContainerIntegration().id());
    }

    @Test
    void detect_followsTheLaunchRules() {
        assertTrue(with(true, "/q/quarkus-app/quarkus-run.jar", "/q/quarkus-app/quarkus-run.jar").detect());
        assertFalse(with(true, "com.acme.Main", "/libs/x.jar").detect());
        assertFalse(with(false, "/q/quarkus-app/quarkus-run.jar", "/q/quarkus-app/quarkus-run.jar").detect());
    }

    @Test
    void detect_inThisTestJvm_isFalse() {
        // the real probes: no QuarkusEntryPoint on this classpath, so nothing is claimed
        assertFalse(new QuarkusContainerIntegration().detect());
    }

    @Test
    void version_isEmptyForAnUnreadableLaunch() {
        assertEquals(Optional.empty(), with(true, "com.acme.Main", "/libs/x.jar").version());
    }

    @Test
    void guidance_namesTheJavaagentFlagAndTheContainerImageVariable() {
        var guidance = new QuarkusContainerIntegration().guidance();
        assertTrue(guidance.summary().contains("Quarkus"));
        assertTrue(guidance.steps().stream().anyMatch(s -> s.contains("-javaagent:") && s.contains("quarkus-run.jar")));
        assertTrue(guidance.steps().stream().anyMatch(s -> s.contains("JAVA_OPTS_APPEND")));
    }
}
