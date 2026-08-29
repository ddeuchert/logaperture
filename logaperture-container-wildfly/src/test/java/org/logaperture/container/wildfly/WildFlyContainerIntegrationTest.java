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
 * {@code detect()} probes only cmdline {@code -D} properties and class
 * presence — never {@code java.util.logging} (the premain gotcha), and not
 * {@code java.util.logging.manager} either, since jboss-modules sets that
 * one only later, at runtime. jboss-modules is on this module's test
 * classpath (a `provided` transitive of jboss-logmanager), so the
 * class-presence half is genuinely satisfied here; the tests drive the
 * `jboss.home.dir` half.
 */
class WildFlyContainerIntegrationTest {

    private final WildFlyContainerIntegration integration = new WildFlyContainerIntegration();

    @AfterEach
    void clearProperties() {
        System.clearProperty("jboss.home.dir");
        System.clearProperty("jboss.domain.base.dir");
    }

    @Test
    void detect_trueForAJBossModulesServerWithAJBossHome() {
        System.setProperty("jboss.home.dir", "/opt/wildfly");
        assertTrue(integration.detect());
    }

    @Test
    void detect_falseWithoutAJBossHomeOrAnOrgJBossAsMainClass() {
        // no jboss.home.dir, and the surefire launch command is not org.jboss.as.*
        assertFalse(integration.detect());
    }

    @Test
    void detect_falseInDomainMode() {
        System.setProperty("jboss.home.dir", "/opt/wildfly");
        System.setProperty("jboss.domain.base.dir", "/opt/wildfly/domain");
        assertFalse(integration.detect(), "domain mode is out of scope for v1");
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
