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
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.core.spi.InstallGuidance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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

    // --- version() (doc/specs/environment-report.md Decision #3, revised) ---------------------
    //
    // Neither real image tried ships $JBOSS_HOME/version.txt (the original, wrong assumption --
    // a user got no version at all against real WildFly). Two real sources instead, confirmed
    // by hand against real quay.io images: Galleon's provisioning.xml (26.1.3.Final has no
    // .galleon directory at all) and the classic product manifest (34.0.1.Final has no such
    // manifest -- Galleon-provisioned). Every fixture below is copied verbatim from one of
    // those two real images, not invented.

    @Test
    void version_noJBossHome_isEmpty() {
        assertEquals(Optional.empty(), integration.version());
    }

    @Test
    void version_neitherSourcePresent_isEmpty(@TempDir Path jbossHome) {
        System.setProperty("jboss.home.dir", jbossHome.toString());
        assertEquals(Optional.empty(), integration.version());
    }

    @Test
    void version_galleonProvisioningPresent_readsTheFeaturePackVersion(@TempDir Path jbossHome) throws Exception {
        Path galleon = Files.createDirectories(jbossHome.resolve(".galleon"));
        // Verbatim from real WildFly 34.0.1.Final (quay.io/wildfly/wildfly:34.0.1.Final-jdk21).
        Files.writeString(galleon.resolve("provisioning.xml"), """
                <?xml version="1.0" ?>
                <installation xmlns="urn:jboss:galleon:provisioning:3.0">
                    <transitive>
                        <feature-pack location="wildfly-ee@maven(org.jboss.universe:community-universe):current#34.0.1.Final">
                            <packages><include name="docs.examples.configs"/></packages>
                        </feature-pack>
                    </transitive>
                    <feature-pack location="wildfly@maven(org.jboss.universe:community-universe):current#34.0.1.Final">
                        <packages><include name="docs.examples.configs"/></packages>
                    </feature-pack>
                </installation>
                """);
        System.setProperty("jboss.home.dir", jbossHome.toString());

        assertEquals(Optional.of("34.0.1.Final"), integration.version());
    }

    @Test
    void version_classicProductManifestPresent_readsTheReleaseVersion(@TempDir Path jbossHome) throws Exception {
        Path metaInf = Files.createDirectories(jbossHome.resolve("modules").resolve("system").resolve("layers")
                .resolve("base").resolve("org").resolve("jboss").resolve("as").resolve("product").resolve("main")
                .resolve("dir").resolve("META-INF"));
        // Verbatim from real WildFly 26.1.3.Final (quay.io/wildfly/wildfly:26.1.3.Final-jdk17) --
        // the same file org.jboss.as.version.ProductConfig reads for its own boot banner.
        Files.writeString(metaInf.resolve("MANIFEST.MF"), """
                Manifest-Version: 1.0
                JBoss-Product-Release-Name: WildFly Full
                JBoss-Product-Release-Version: 26.1.3.Final

                """);
        System.setProperty("jboss.home.dir", jbossHome.toString());

        assertEquals(Optional.of("26.1.3.Final"), integration.version());
    }

    @Test
    void version_bothSourcesPresent_prefersGalleon(@TempDir Path jbossHome) throws Exception {
        Path galleon = Files.createDirectories(jbossHome.resolve(".galleon"));
        Files.writeString(galleon.resolve("provisioning.xml"),
                "<installation><feature-pack location=\"wildfly@maven(x):current#99.0.0.Final\"/></installation>");
        Path metaInf = Files.createDirectories(jbossHome.resolve("modules").resolve("system").resolve("layers")
                .resolve("base").resolve("org").resolve("jboss").resolve("as").resolve("product").resolve("main")
                .resolve("dir").resolve("META-INF"));
        Files.writeString(metaInf.resolve("MANIFEST.MF"), "JBoss-Product-Release-Version: 1.0.0.Final\n\n");
        System.setProperty("jboss.home.dir", jbossHome.toString());

        assertEquals(Optional.of("99.0.0.Final"), integration.version());
    }
}
