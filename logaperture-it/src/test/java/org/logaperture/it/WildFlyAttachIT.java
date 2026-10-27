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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code logctl attach} into a standalone WildFly that was started <em>without</em> {@code -javaagent}
 * (doc/specs/logctl-attach.md "Test plan"), the same image as {@link WildFlyContainerIT}. {@code logctl}
 * runs inside the container, as the unprivileged {@code jboss} user, whose home is not writable: so this
 * also covers the state-home fallback (#8). Self-skips when Docker is absent.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WildFlyAttachIT {

    private static final String BOOT_LOGGER = "org.jboss.as.server";
    private static final String SERVER_LOG = "/opt/jboss/wildfly/standalone/log/server.log";

    private GenericContainer<?> wildfly;
    private String wildflyPid;

    @BeforeAll
    void startWildFlyWithoutTheAgent() throws Exception {
        String cliJar = System.getProperty("logaperture.cli.jar");
        assertNotNull(cliJar, "logaperture.cli.jar must be set (reactor build order provides it)");
        assertTrue(Files.isRegularFile(Path.of(cliJar)), "not built: " + cliJar + " -- run `mvn package`");
        String image = System.getProperty("logaperture.wildfly.image", "quay.io/wildfly/wildfly:26.1.3.Final-jdk17");

        wildfly = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(cliJar), "/opt/logctl.jar")
                .withCommand("/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0")
                .withLogConsumer(frame -> System.out.print("[wildfly] " + frame.getUtf8String()))
                .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        wildfly.start();

        // The one java process whose command line names jboss-modules; the `sh -c` running this search
        // starts with "sh", so it cannot match.
        ExecResult found = exec("sh", "-c", "for p in /proc/[0-9]*; do tr '\\0' ' ' < $p/cmdline 2>/dev/null"
                + " | grep -q '^[^ ]*java .*jboss-modules' && basename $p; done");
        wildflyPid = found.getStdout().trim();
        assertTrue(wildflyPid.matches("\\d+"), "found the WildFly process: '" + found.getStdout() + "'");
        assertEquals(3, logctl("status").exitCode(), "started without the agent: nothing for status to find");

        // The one attach every test below relies on; its own result is checked here, where it is certain to be first.
        Logctl attached = logctl("attach", wildflyPid);
        assertEquals(0, attached.exitCode(), attached.stdout() + attached.stderr());
        assertTrue(attached.stdout().contains("is active in PID " + wildflyPid), attached.stdout());
    }

    @AfterAll
    void stop() {
        if (wildfly != null) {
            wildfly.stop();
        }
    }

    @Test
    void aServerStartedWithoutTheAgentIsAttachedAndControlled() throws Exception {
        assertTrue(logctl("list", "loggers", "org.jboss", "--show-all").stdout().contains(BOOT_LOGGER),
                "the server's own loggers are visible");
        Logctl raised = logctl("set", "logger", BOOT_LOGGER, "DEBUG", "for", "5m");
        assertEquals(0, raised.exitCode(), raised.stderr());
        assertTrue(logctl("status").stdout().contains(BOOT_LOGGER));
        assertEquals(0, logctl("reset", "logger", BOOT_LOGGER).exitCode());
    }

    @Test
    void theStateHomeFallsBackWhenTheServersHomeIsNotWritable() throws Exception {
        Logctl env = logctl("env");

        assertEquals(0, env.exitCode(), env.stderr());
        assertTrue(env.stdout().contains("/tmp/logaperture-"),
                "state is kept under the temp directory, since the jboss user has no writable home:\n" + env.stdout());
        assertTrue(wildfly.getLogs().contains("keeping state in /tmp/logaperture-"),
                "the agent said where it put the state");
    }

    @Test
    void aSecondAttachIsANoOp() {
        Logctl again = logctl("attach", wildflyPid);

        assertEquals(0, again.exitCode(), again.stderr());
        assertTrue(again.stdout().contains("already runs LogAperture"), again.stdout());
    }

    @Test
    void doctorSaysTheAgentWasAttached() throws Exception {
        assertTrue(logctl("doctor").stdout().contains("LogAperture was loaded by attach"));
    }

    @Test
    void anAttachDoesNotTouchStandaloneXml() throws Exception {
        String xml = "/opt/jboss/wildfly/standalone/configuration/standalone.xml";
        String before = exec("md5sum", xml).getStdout();

        logctl("set", "logger", "com.example.Probe", "DEBUG", "sticky");

        assertEquals(before, exec("md5sum", xml).getStdout());
    }

    // --- harness -----------------------------------------------------------------------------------

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
}
