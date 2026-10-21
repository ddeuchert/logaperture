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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/wildfly-early-handler-names.md "Tests" against a real standalone WildFly whose only
 * deployment is slow to start: handler names resolve, and a vendor-defaults {@code CONSOLE: AUTO}
 * follows a sticky logger, while the server is still {@code starting} (no {@code WFLYSRV0025}), not
 * minutes later. Its own container: it holds each boot open for {@link #HOLD_SECONDS} seconds.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WildFlySlowStartIT {

    private static final String JBOSS_CLI = "/opt/jboss/wildfly/bin/jboss-cli.sh";
    private static final String DEPLOYMENTS = "/opt/jboss/wildfly/standalone/deployments";
    private static final String VENDOR_FILE = "/opt/vendor-defaults.yaml";
    private static final String SLOW_LOGGER = "com.myapp.slow.Boot";
    private static final int HOLD_SECONDS = 40;
    private static final int FIRST_JAKARTA_NAMESPACE_MAJOR = 27;
    private static final Pattern IMAGE_MAJOR = Pattern.compile(":(?<major>\\d+)\\.");

    private GenericContainer<?> wildfly;
    private Path scratch;
    private boolean jakartaServletNamespace;

    @BeforeAll
    void startWildFly() throws Exception {
        String agentJar = requireFile("logaperture.agent.jar", System.getProperty("logaperture.agent.jar"));
        String cliJar = requireFile("logaperture.cli.jar", System.getProperty("logaperture.cli.jar"));
        String image = System.getProperty("logaperture.wildfly.image", "quay.io/wildfly/wildfly:26.1.3.Final-jdk17");
        Matcher major = IMAGE_MAJOR.matcher(image);
        assertTrue(major.find(), "expected a recognizable WildFly version tag in " + image);
        jakartaServletNamespace = Integer.parseInt(major.group("major")) >= FIRST_JAKARTA_NAMESPACE_MAJOR;

        scratch = Files.createTempDirectory("slow-start-it");
        Path vendorFile = scratch.resolve("vendor-defaults.yaml");
        Files.writeString(vendorFile, """
                schemaVersion: 1
                handlers:
                  - name: CONSOLE
                    level: AUTO
                """);
        Path war = buildSlowWar();

        String bootScript = "echo 'JAVA_OPTS=\"$JAVA_OPTS -javaagent:/opt/logaperture-agent.jar=--vendor-defaults="
                + VENDOR_FILE
                + " -Dlogaperture.home=/opt/jboss/wildfly/standalone/tmp/logaperture\"'"
                + " >> \"$JBOSS_HOME/bin/standalone.conf\" && exec \"$JBOSS_HOME/bin/standalone.sh\" -b 0.0.0.0";

        wildfly = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(agentJar), "/opt/logaperture-agent.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(cliJar), "/opt/logctl.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(vendorFile, 0444), VENDOR_FILE)
                .withCopyFileToContainer(MountableFile.forHostPath(war), DEPLOYMENTS + "/slow.war")
                .withCommand("sh", "-c", bootScript)
                .withLogConsumer(frame -> System.out.print("[wildfly-slow] " + frame.getUtf8String()))
                // The deployment is the slow part: ready to look as soon as it reports it is holding the boot.
                .waitingFor(Wait.forLogMessage(".*slow probe holding.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        wildfly.start();
    }

    @AfterAll
    void stop() {
        if (wildfly != null) {
            wildfly.stop();
        }
    }

    @Test
    void handlerNamesAndAVendorAutoConsole_workWhileTheServerIsStillStarting() throws Exception {
        // Boot 1: the deployment is holding the boot open, so the server has not reported WFLYSRV0025.
        assertFalse(booted(1), "the server is still starting (the deployment holds it)");
        assertTrue(pollUntil(() -> logctl("list", "handlers", "--show-all").stdout().contains("CONSOLE")
                        && logctl("list", "handlers", "--show-all").stdout().contains("FILE")),
                "CONSOLE and FILE are named while the server is still starting:\n"
                        + logctl("list", "handlers", "--show-all").stdout());
        assertFalse(booted(1), "...and that was before the boot finished");
        assertFalse(wildfly.getLogs().contains("WFLYCTL0013"),
                "no failed management-operation logging while resolving names early");

        // Let boot 1 finish, then keep a logger lowered across a restart.
        assertTrue(pollUntil(() -> booted(1), HOLD_SECONDS + 60), "boot 1 completes");
        assertEquals(0, logctl("set", "logger", SLOW_LOGGER, "DEBUG", "sticky").exitCode());

        // Boot 2: the sticky logger comes back with the restart; the vendor AUTO console is pending
        // at install and must follow it within seconds, long before the deployment lets go.
        exec(JBOSS_CLI, "--connect", "--command=:shutdown(restart=true)");
        assertTrue(pollUntil(() -> holdingBoot(2), 120), "boot 2 reaches the slow deployment");
        assertFalse(booted(2), "boot 2 is still starting");
        assertTrue(pollUntil(() -> consoleLevelIs("DEBUG"), 20),
                "the vendor AUTO console follows the sticky logger while still starting:\n"
                        + logctl("list", "handlers", "--show-all").stdout());
        assertFalse(booted(2), "...and that was before boot 2 finished");
        assertTrue(pollUntil(() -> afterSecondHoldStart(wildfly.getLogs()).contains("slow probe debug tick"), 30),
                "a DEBUG record from the sticky logger reached the console during the deployment's start");
        assertFalse(booted(2), "...still before boot 2 finished");
    }

    // --- the slow app -----------------------------------------------------------------------------

    private Path buildSlowWar() throws IOException {
        String servletPackage = jakartaServletNamespace ? "jakarta.servlet" : "javax.servlet";
        String source = """
                package com.myapp.slow;
                import %s.ServletContextEvent;
                import %s.ServletContextListener;
                import %s.annotation.WebListener;
                import java.util.logging.Logger;
                @WebListener
                public class Boot implements ServletContextListener {
                    @Override public void contextInitialized(ServletContextEvent e) {
                        Logger log = Logger.getLogger("%s");
                        log.info("slow probe holding the boot for %d s");
                        for (int i = 0; i < %d; i += 2) {
                            log.fine("slow probe debug tick " + i);
                            try { Thread.sleep(2000); } catch (InterruptedException x) { return; }
                        }
                    }
                }
                """.formatted(servletPackage, servletPackage, servletPackage, SLOW_LOGGER, HOLD_SECONDS, HOLD_SECONDS);
        Path src = scratch.resolve("com/myapp/slow/Boot.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(scratch.resolve("classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not JRE) is required to build the slow WAR");
        int rc = compiler.run(null, null, null, "--release", "17", "-classpath", probeCompileClasspath(),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "slow probe compile failed");

        Path war = scratch.resolve("slow.war");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/myapp/slow/Boot.class"));
            zip.write(Files.readAllBytes(classes.resolve("com/myapp/slow/Boot.class")));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/beans.xml"));
            zip.write("<beans/>".getBytes());
            zip.closeEntry();
        }
        return war;
    }

    private String probeCompileClasspath() {
        String property = jakartaServletNamespace ? "probe.compile.classpath.jakarta" : "probe.compile.classpath.javax";
        String explicit = System.getProperty(property, "");
        return !explicit.isBlank() && Files.isReadable(Path.of(explicit)) ? explicit : System.getProperty("java.class.path");
    }

    // --- boot state, read from the server's own log -----------------------------------------------

    /** Whether the server has logged WFLYSRV0025 at least {@code boots} times. */
    private boolean booted(int boots) {
        return wildfly.getLogs().lines().filter(line -> line.contains("WFLYSRV0025")).count() >= boots;
    }

    /** Whether the {@code boots}-th boot has reached the slow deployment's hold. */
    private boolean holdingBoot(int boots) {
        return wildfly.getLogs().lines().filter(line -> line.contains("slow probe holding")).count() >= boots;
    }

    /** The console output from the second "holding" line on. */
    private static String afterSecondHoldStart(String logs) {
        int first = logs.indexOf("slow probe holding");
        int second = first < 0 ? -1 : logs.indexOf("slow probe holding", first + 1);
        return second < 0 ? "" : logs.substring(second);
    }

    private boolean consoleLevelIs(String level) {
        return logctl("list", "handlers", "--show-all").stdout().lines()
                .anyMatch(line -> line.strip().startsWith("CONSOLE") && line.contains(level));
    }

    // --- helpers (same shape as WildFlyVendorDefaultsIT's) ---------------------------------------

    private record Logctl(int exitCode, String stdout, String stderr) {
    }

    private boolean pollUntil(BooleanSupplier condition) {
        return pollUntil(condition, 30);
    }

    private boolean pollUntil(BooleanSupplier condition, int seconds) {
        for (int i = 0; i < seconds; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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

    private static String requireFile(String property, String value) {
        assertNotNull(value, "system property " + property + " must be set (reactor build order provides it)");
        assertTrue(Files.isRegularFile(Path.of(value)), property + " not found: " + value + " -- run `mvn package`");
        return value;
    }
}
