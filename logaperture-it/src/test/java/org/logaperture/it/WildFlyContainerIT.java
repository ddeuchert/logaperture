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
package org.logaperture.it;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.management.JMX;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slice 3 exit criterion, against a real standalone WildFly 26.1.3.Final
 * with the agent attached via {@code JAVA_OPTS} — see
 * doc/specs/wildfly-support.md.
 *
 * <p><b>Status: written, not yet shaken out against a real Docker daemon.</b>
 * Authored without Docker available; the JMX-over-fixed-port wiring and the
 * WildFly log-path assertions are the parts most likely to need a tweak on
 * first real run. The whole class self-skips when Docker is absent
 * ({@code @Testcontainers(disabledWithoutDocker = true)}), so {@code mvn
 * verify} stays green without Docker; CI runs it on an ubuntu runner.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WildFlyContainerIT {

    private static final int JMX_PORT = 9995;
    private static final int MGMT_PORT = 9990;
    private static final String SERVER_LOG = "/opt/jboss/wildfly/standalone/log/server.log";
    private static final String BOOT_LOGGER = "org.jboss.as.server";

    private GenericContainer<?> wildfly;
    private LevelControlMXBean mbean;
    private JMXConnector connector;

    @BeforeAll
    void startWildFly() throws Exception {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "logaperture.agent.jar system property must point at the shaded agent jar");
        assertTrue(Files.isRegularFile(Path.of(agentJar)), "agent jar not found: " + agentJar
                + " (run `mvn package` on logaperture-agent first)");

        String image = System.getProperty("logaperture.wildfly.image", "quay.io/wildfly/wildfly:26.1.3.Final-jdk17");

        String javaOptsAppend = String.join(" ",
                "-javaagent:/opt/logaperture-agent.jar",
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=" + JMX_PORT,
                "-Dcom.sun.management.jmxremote.rmi.port=" + JMX_PORT,
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Dcom.sun.management.jmxremote.local.only=false",
                "-Djava.rmi.server.hostname=127.0.0.1");

        wildfly = new GenericContainer<>(image)
                .withExposedPorts(MGMT_PORT, JMX_PORT)
                .withEnv("JAVA_OPTS_APPEND", javaOptsAppend)
                .withCopyFileToContainer(MountableFile.forHostPath(agentJar), "/opt/logaperture-agent.jar")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(JMX_PORT), new ExposedPort(JMX_PORT))))
                .withLogConsumer(frame -> System.out.print("[wildfly] " + frame.getUtf8String()))
                .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*started in.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        wildfly.start();

        JMXServiceURL url = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + wildfly.getMappedPort(JMX_PORT) + "/jmxrmi");
        connector = JMXConnectorFactory.connect(url, null);
        mbean = JMX.newMXBeanProxy(connector.getMBeanServerConnection(), JmxRegistrar.OBJECT_NAME, LevelControlMXBean.class);
    }

    @AfterAll
    void stop() throws Exception {
        if (connector != null) {
            connector.close();
        }
        if (wildfly != null) {
            wildfly.stop();
        }
    }

    @Test
    void wildFlyBootedWithTheCorrectLogManager_andTheWildFlyIntegrationInstalled() throws Exception {
        // The agent's WildFly integration only proceeds once
        // WildFlyLogManagerReadiness has confirmed org.jboss.logmanager.LogManager
        // is the installed java.util.logging.manager (the premain-gotcha guard).
        // So: agent MBean reachable + it can see org.jboss.* loggers == boot was clean.
        List<LoggerInfoData> jbossLoggers = mbean.listLoggers("org.jboss");
        assertFalse(jbossLoggers.isEmpty(), "the system LogContext should expose org.jboss.* loggers");

        String bootLog = execInContainer("cat", SERVER_LOG);
        assertFalse(bootLog.contains("The LogManager accessed before"),
                "no premature java.util.logging access");
        assertTrue(bootLog.contains("WFLYSRV0025"), "WildFly reported a clean start");
    }

    @Test
    void listLoggers_liststheSystemContext() {
        List<LoggerInfoData> rows = mbean.listLoggers(null);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> "system".equals(r.getContext())),
                "stock WildFly has a single shared system context");
    }

    @Test
    void forOverride_onABootLogger_raisesDetailThenRevertsOnSchedule() throws Exception {
        LevelOverrideData override = mbean.setLevel(BOOT_LOGGER, "DEBUG", false, "it-boot-detail", "FOR", 60);
        assertEquals("DEBUG", override.getLevel());
        assertEquals("DEBUG", mbean.listLoggers(BOOT_LOGGER).get(0).getEffectiveLevel());

        // Force a log line at the newly-permitted level, then confirm it reached server.log.
        // (org.jboss.as.server logs at DEBUG during a :reload; a management no-op is enough to stir it.)
        execInContainer("/opt/jboss/wildfly/bin/jboss-cli.sh", "--connect", "--command=:reload");
        Thread.sleep(5000);
        // reverting is driven by the expiry sweep; shorten by asking for a fresh FOR of 1s and waiting it out
        mbean.setLevel(BOOT_LOGGER, "DEBUG", false, "it-boot-detail", "FOR", 1);
        Thread.sleep(35_000); // default sweep interval is 30s
        assertFalse(mbean.listLoggers(BOOT_LOGGER).get(0).isOverrideActive(),
                "the FOR override reverted on schedule");
    }

    @Test
    void standaloneXml_isByteIdenticalAfterASessionOfOverrides() throws Exception {
        String before = execInContainer("md5sum", "/opt/jboss/wildfly/standalone/configuration/standalone.xml");

        mbean.setLevel("com.example.Probe", "TRACE", false, "it", "STICKY", 0);
        mbean.setLevel("org.hibernate.SQL", "DEBUG", false, "it", "SESSION", 0);
        mbean.resetAll();

        String after = execInContainer("md5sum", "/opt/jboss/wildfly/standalone/configuration/standalone.xml");
        assertEquals(before, after, "the agent never writes standalone.xml");
    }

    @Test
    @Disabled("Needs a shakeout run against real Docker, plus an in-test WAR build. "
            + "Deploy a minimal WAR whose class logs to com.myapp.probe.*, assert listLoggers shows it under "
            + "'system', setLevel it, redeploy, assert the override survived (a 'resume' audit entry).")
    void deployedWarLogger_isVisibleAndSurvivesRedeploy() {
        // TODO Slice 3 shakeout
    }

    @Test
    @Disabled("Needs a shakeout run against real Docker. Make a /subsystem=logging change via jboss-cli that "
            + "collides with an active override; assert the verification sweep re-applies within its interval "
            + "with a 'verification-sweep' audit entry (closes the M0 '/subsystem=logging never exercised' gap).")
    void managementCliLoggingChange_isCorrectedByTheVerificationSweep() {
        // TODO Slice 3 shakeout
    }

    private String execInContainer(String... command) throws Exception {
        var result = wildfly.execInContainer(command);
        return result.getStdout() + result.getStderr();
    }
}
