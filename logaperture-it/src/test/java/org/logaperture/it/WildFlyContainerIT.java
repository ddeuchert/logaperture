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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Slice 3 exit criterion, against a real standalone WildFly 26.1.3.Final:
 * agent attached by a bare {@code -javaagent}, driven entirely through
 * {@code logctl} — see doc/specs/wildfly-support.md.
 *
 * <p>{@code logctl} runs <em>inside</em> the container (it attaches to the
 * WildFly JVM locally, same as a real operator on the box), so there is no
 * JMX-over-Docker plumbing. Self-skips when Docker is absent; CI runs it on
 * an ubuntu runner.
 *
 * <p>Harness note from the shakeout: this image ignores {@code
 * JAVA_OPTS_APPEND}, so the container command appends a {@code JAVA_OPTS}
 * line to {@code standalone.conf} before boot.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WildFlyContainerIT {

    private static final String SERVER_LOG = "/opt/jboss/wildfly/standalone/log/server.log";
    private static final String STANDALONE_XML = "/opt/jboss/wildfly/standalone/configuration/standalone.xml";
    private static final String BOOT_LOGGER = "org.jboss.as.server";

    private GenericContainer<?> wildfly;

    @BeforeAll
    void startWildFly() throws Exception {
        String agentJar = requireFile("logaperture.agent.jar",
                System.getProperty("logaperture.agent.jar"));
        String cliJar = requireFile("logaperture.cli.jar",
                System.getProperty("logaperture.cli.jar"));

        String image = System.getProperty("logaperture.wildfly.image", "quay.io/wildfly/wildfly:26.1.3.Final-jdk17");

        // This image ignores JAVA_OPTS_APPEND, and setting JAVA_OPTS would wipe
        // its --add-opens/--add-exports -- so append one line to standalone.conf.
        String bootScript = "echo 'JAVA_OPTS=\"$JAVA_OPTS -javaagent:/opt/logaperture-agent.jar\"'"
                + " >> \"$JBOSS_HOME/bin/standalone.conf\" && exec \"$JBOSS_HOME/bin/standalone.sh\" -b 0.0.0.0";

        wildfly = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(agentJar), "/opt/logaperture-agent.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(cliJar), "/opt/logctl.jar")
                .withCommand("sh", "-c", bootScript)
                .withLogConsumer(frame -> System.out.print("[wildfly] " + frame.getUtf8String()))
                .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        wildfly.start();

        awaitControlPlane();
    }

    @AfterAll
    void stop() {
        if (wildfly != null) {
            wildfly.stop();
        }
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void wildFlyBootedCleanlyWithTheAgentAttached() throws Exception {
        String bootLog = exec("cat", SERVER_LOG).getStdout();
        assertTrue(bootLog.contains("WFLYSRV0025"), "WildFly reported a clean start");
        assertFalse(bootLog.contains("The LogManager was not properly installed"));
        assertFalse(bootLog.contains("The LogManager accessed before"));

        // The agent installs only if java.util.logging.manager is genuinely JBoss LogManager
        // by the time it runs (its readiness gate) -- so a working `logctl` implies a clean boot.
        assertTrue(logctl("levels", "org.jboss").stdout().contains(BOOT_LOGGER),
                "logctl lists the server's own loggers");
    }

    @Test
    void forOverride_raisesABootLoggerThenResetRestoresIt() {
        Logctl before = logctl("levels", BOOT_LOGGER);
        assertTrue(before.stdout().contains("INFO"), "org.jboss.as.server starts at INFO:\n" + before.stdout());

        Logctl raised = logctl("debug", BOOT_LOGGER, "for", "30m");
        assertEquals(0, raised.exitCode(), raised.stderr());
        assertTrue(logctl("levels", BOOT_LOGGER).stdout().contains("DEBUG"),
                "logctl levels shows the raised level");
        assertTrue(logctl("status").stdout().contains(BOOT_LOGGER),
                "logctl status shows the active override");

        assertEquals(0, logctl("reset", BOOT_LOGGER).exitCode());
        assertFalse(logctl("status").stdout().contains(BOOT_LOGGER),
                "the override is gone after reset");
    }

    @Test
    void standaloneXml_isByteIdenticalAfterASessionOfOverrides() throws Exception {
        String before = exec("md5sum", STANDALONE_XML).getStdout();

        logctl("debug", "com.example.Probe", "sticky");
        logctl("trace", "org.hibernate.SQL");
        logctl("reset", "--all");

        assertEquals(before, exec("md5sum", STANDALONE_XML).getStdout(),
                "the agent never writes standalone.xml");
    }

    @Test
    @Disabled("Slice 3 follow-up: needs an in-test WAR build. Deploy a minimal WAR whose class "
            + "logs to com.myapp.probe.*, assert `logctl levels` shows it under 'system', set it, "
            + "redeploy, assert the override survived.")
    void deployedWarLogger_isVisibleAndSurvivesRedeploy() {
    }

    @Test
    @Disabled("Slice 3 follow-up: make a /subsystem=logging change via jboss-cli that collides with "
            + "an active override; assert the verification sweep re-applies it within its interval, "
            + "with a 'verification-sweep' audit entry.")
    void managementCliLoggingChange_isCorrectedByTheVerificationSweep() {
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Logctl(int exitCode, String stdout, String stderr) {
    }

    private Logctl logctl(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "java";
        command[1] = "-jar";
        command[2] = "/opt/logctl.jar";
        System.arraycopy(args, 0, command, 3, args.length);
        ExecResult result = exec(command);
        return new Logctl(result.getExitCode(), result.getStdout(), result.getStderr());
    }

    private ExecResult exec(String... command) {
        try {
            return wildfly.execInContainer(command);
        } catch (Exception e) {
            throw new IllegalStateException("execInContainer " + String.join(" ", command) + " failed", e);
        }
    }

    private void awaitControlPlane() throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            Logctl probe = logctl("levels", "org.jboss.as");
            if (probe.exitCode() == 0 && probe.stdout().contains("org.jboss.as")) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("logctl never reached the agent's control plane inside the container");
    }

    private static String requireFile(String property, String value) {
        assertNotNull(value, "system property " + property + " must be set (reactor build order provides it)");
        assertTrue(Files.isRegularFile(Path.of(value)), property + " not found: " + value + " -- run `mvn package`");
        return value;
    }
}
