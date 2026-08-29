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
package org.logaperture.container.wildfly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.logaperture.core.spi.InstallGuidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code detect()} probes only system properties and class presence — never
 * {@code java.util.logging} (the premain gotcha). This module's test JVM
 * runs with {@code -Djava.util.logging.manager=org.jboss.logmanager.LogManager}
 * (see the pom) and jboss-modules on the classpath, so the positive case is
 * exercised for real here.
 */
class WildFlyContainerIntegrationTest {

    private final WildFlyContainerIntegration integration = new WildFlyContainerIntegration();

    @AfterEach
    void clearProperties() {
        System.clearProperty("jboss.domain.base.dir");
    }

    @Test
    void detect_trueWhenJBossLogManagerRequestedAndJBossModulesPresent() {
        assertEquals("org.jboss.logmanager.LogManager", System.getProperty("java.util.logging.manager"),
                "the pom sets this for the test JVM");
        assertTrue(integration.detect());
    }

    @Test
    void detect_falseInDomainMode() {
        System.setProperty("jboss.domain.base.dir", "/opt/wildfly/domain");
        assertFalse(integration.detect(), "domain mode is out of scope for v1");
    }

    @Test
    void detect_falseWhenJBossLogManagerNotRequested() {
        String saved = System.getProperty("java.util.logging.manager");
        System.clearProperty("java.util.logging.manager");
        try {
            assertFalse(integration.detect());
        } finally {
            System.setProperty("java.util.logging.manager", saved);
        }
    }

    @Test
    void id_isWildfly() {
        assertEquals("wildfly", integration.id());
    }

    @Test
    void guidance_pointsAtStandaloneConf() {
        InstallGuidance guidance = integration.guidance();
        assertTrue(guidance.summary().contains("standalone.conf"));
        assertTrue(guidance.steps().stream().anyMatch(s -> s.contains("-javaagent:")));
        assertTrue(guidance.steps().stream().anyMatch(s -> s.contains("standalone.xml")),
                "the guidance states the agent never touches standalone.xml");
    }
}
