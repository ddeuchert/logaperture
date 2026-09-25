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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * doc/specs/vendor-defaults.md "Testing" against a real standalone WildFly: the agent is
 * started with {@code --vendor-defaults=} naming a file that sets a logger, a named WildFly
 * handler ({@code FILE}) and a {@code drop} rule. A separate class, with its own container,
 * from {@link WildFlyContainerIT}: a vendor file changes the baseline every test in a shared
 * container would otherwise assume.
 *
 * <p>Drift is exercised with a {@code /subsystem=logging} management change rather than a full
 * {@code :reload} -- the same verification-sweep path, without restarting the server under the
 * rest of this suite.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WildFlyVendorDefaultsIT {

    private static final String JBOSS_CLI = "/opt/jboss/wildfly/bin/jboss-cli.sh";
    private static final String VENDOR_LOGGER = "com.vendor.probe";
    private static final String VENDOR_FILE = "/opt/vendor-defaults.yaml";

    private GenericContainer<?> wildfly;

    @BeforeAll
    void startWildFly() throws Exception {
        String agentJar = requireFile("logaperture.agent.jar", System.getProperty("logaperture.agent.jar"));
        String cliJar = requireFile("logaperture.cli.jar", System.getProperty("logaperture.cli.jar"));
        String image = System.getProperty("logaperture.wildfly.image", "quay.io/wildfly/wildfly:26.1.3.Final-jdk17");

        Path vendorFile = Files.createTempFile("vendor-defaults", ".yaml");
        vendorFile.toFile().deleteOnExit();
        Files.writeString(vendorFile, """
                schemaVersion: 1
                loggers:
                  - name: %s
                    level: WARN
                handlers:
                  - name: FILE
                    level: WARN
                rules:
                  - id: probe-noise
                    action: drop
                    logger: %s
                    messageContains: "noise"
                """.formatted(VENDOR_LOGGER, VENDOR_LOGGER));

        // Same standalone.conf approach as WildFlyContainerIT (this image ignores JAVA_OPTS_APPEND).
        String bootScript = "echo 'JAVA_OPTS=\"$JAVA_OPTS -javaagent:/opt/logaperture-agent.jar=--vendor-defaults="
                + VENDOR_FILE
                + " -Dlogaperture.sweep.seconds=3"
                + " -Dlogaperture.home=/opt/jboss/wildfly/standalone/tmp/logaperture\"'"
                + " >> \"$JBOSS_HOME/bin/standalone.conf\" && exec \"$JBOSS_HOME/bin/standalone.sh\" -b 0.0.0.0";

        wildfly = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(agentJar), "/opt/logaperture-agent.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(cliJar), "/opt/logctl.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(vendorFile, 0444), VENDOR_FILE)
                .withCommand("sh", "-c", bootScript)
                .withLogConsumer(frame -> System.out.print("[wildfly-vendor] " + frame.getUtf8String()))
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

    @Test
    void vendorLevelsAndRule_areInEffectFromBoot_andReported() {
        String loggers = logctl("list", "loggers", VENDOR_LOGGER).stdout();
        assertTrue(loggers.contains("VENDOR") && loggers.contains(VENDOR_LOGGER), loggers);
        assertTrue(lineFor(loggers, VENDOR_LOGGER).contains("WARN"), loggers);

        assertTrue(pollLogctl(out -> lineFor(out, "FILE").contains("WARN"), "list", "handlers"),
                "the named WildFly handler FILE picks up its vendor level once its name resolves");

        String rules = logctl("list", "rules").stdout();
        assertTrue(lineFor(rules, "vendor:probe-noise").contains("vendor-defaults"), rules);

        assertTrue(logctl("status").stdout().startsWith("Vendor defaults: " + VENDOR_FILE), logctl("status").stdout());
        assertTrue(logctl("env").stdout().contains(VENDOR_FILE + "  loaded"), logctl("env").stdout());
        assertTrue(wildfly.getLogs().contains("vendor defaults loaded from " + VENDOR_FILE),
                "the agent reported the file at startup");
    }

    @Test
    void setThenReset_landsOnTheVendorLevel_forALoggerAndAHandler() {
        assertEquals(0, logctl("set", "logger", VENDOR_LOGGER, "DEBUG", "session").exitCode());
        assertTrue(lineFor(logctl("list", "loggers", VENDOR_LOGGER).stdout(), VENDOR_LOGGER).contains("DEBUG"));
        Logctl reset = logctl("reset", "logger", VENDOR_LOGGER);
        assertTrue(reset.stdout().contains("WARN (vendor default)"), reset.stdout());

        assertTrue(pollLogctl(out -> lineFor(out, "FILE").contains("WARN"), "list", "handlers"));
        assertEquals(0, logctl("set", "handler", "FILE", "DEBUG", "session").exitCode());
        assertEquals(0, logctl("reset", "handler", "FILE").exitCode());
        assertTrue(lineFor(logctl("list", "handlers").stdout(), "FILE").matches("FILE\\s+WARN\\s+WARN.*"),
                logctl("list", "handlers").stdout());
    }

    @Test
    void managementChange_onAVendorLogger_isCorrectedByTheVerificationSweep() {
        ExecResult added = exec(JBOSS_CLI, "--connect",
                "--command=/subsystem=logging/logger=" + VENDOR_LOGGER + ":add(level=DEBUG)");
        assertEquals(0, added.getExitCode(), added.getStdout() + added.getStderr());
        try {
            assertTrue(pollUntil(() -> wildfly.getLogs().lines().anyMatch(line ->
                            line.contains("source=verification-sweep") && line.contains("logger=" + VENDOR_LOGGER))),
                    "the verification sweep re-applied the vendor level");
            assertTrue(lineFor(logctl("list", "loggers", VENDOR_LOGGER).stdout(), VENDOR_LOGGER).contains("WARN"));
        } finally {
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/logger=" + VENDOR_LOGGER + ":remove");
        }
    }

    /**
     * doc/specs/reset-to-native.md "Testing": {@code --to-native} lands on WildFly's own
     * configuration, a management change then sticks (the sweep leaves it alone), and a plain
     * reset returns to the vendor level. Restores the vendor level on the way out, since the
     * container is shared with the other tests here.
     */
    @Test
    void resetToNative_landsOnWildFlysOwnConfiguration_followsIt_andAPlainResetRestoresTheVendorLevel()
            throws InterruptedException {
        try {
            Logctl toNative = logctl("reset", "logger", VENDOR_LOGGER, "--to-native");
            assertEquals(0, toNative.exitCode(), toNative.stderr());
            assertTrue(toNative.stdout().contains("INFO (native default, until restart)"), toNative.stdout());
            assertTrue(lineFor(logctl("list", "loggers", VENDOR_LOGGER).stdout(), VENDOR_LOGGER)
                    .contains("WARN (reset to native)"));
            assertTrue(logctl("status").stdout().contains("1 reset to native"), logctl("status").stdout());

            ExecResult added = exec(JBOSS_CLI, "--connect",
                    "--command=/subsystem=logging/logger=" + VENDOR_LOGGER + ":add(level=DEBUG)");
            assertEquals(0, added.getExitCode(), added.getStdout() + added.getStderr());
            Thread.sleep(7_000); // two sweep ticks (logaperture.sweep.seconds=3)
            String afterChange = lineFor(logctl("list", "loggers", VENDOR_LOGGER).stdout(), VENDOR_LOGGER);
            assertTrue(afterChange.contains("DEBUG"), "the management change stuck (T3): " + afterChange);

            assertTrue(pollLogctl(out -> lineFor(out, "FILE").contains("WARN"), "list", "handlers"));
            assertEquals(0, logctl("reset", "handler", "FILE", "--to-native").exitCode());
            String fileRow = lineFor(logctl("list", "handlers").stdout(), "FILE");
            assertTrue(fileRow.contains("WARN (reset to native)") && !fileRow.matches("FILE\\s+WARN\\s+.*"), fileRow);
        } finally {
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/logger=" + VENDOR_LOGGER + ":remove");
            Logctl plain = logctl("reset", "logger", VENDOR_LOGGER);
            assertTrue(plain.stdout().contains("WARN (vendor default)"), plain.stdout());
            assertEquals(0, logctl("reset", "handler", "FILE").exitCode());
            assertTrue(lineFor(logctl("list", "handlers").stdout(), "FILE").matches("FILE\\s+WARN\\s+WARN.*"),
                    logctl("list", "handlers").stdout());
        }
    }

    @Test
    void vendorRule_survivesResetRules_refusesSingleReset_andSuspendsWithTheFlag() {
        Logctl bulk = logctl("reset", "rules");
        assertTrue(bulk.stdout().contains("Left 1 vendor default rule(s) in place"), bulk.stdout());

        Logctl refused = logctl("reset", "rule", "vendor:probe-noise");
        assertNotEquals(0, refused.exitCode());
        assertTrue(refused.stderr().contains("--include-vendor-defaults"), refused.stderr());

        Logctl suspended = logctl("reset", "rule", "vendor:probe-noise", "--include-vendor-defaults");
        assertEquals(0, suspended.exitCode(), suspended.stderr());
        assertTrue(lineFor(logctl("list", "rules").stdout(), "vendor:probe-noise").contains("suspended"));
        assertFalse(logctl("reset", "rule", "vendor:probe-noise", "--include-vendor-defaults").stdout()
                .contains("→ suspended"), "already suspended: nothing left to do");
    }

    // --- helpers (same shape as WildFlyContainerIT's) ---------------------------------------------

    private record Logctl(int exitCode, String stdout, String stderr) {
    }

    private static String lineFor(String output, String startsWith) {
        return output.lines().map(String::strip).filter(line -> line.startsWith(startsWith)).findFirst().orElse("");
    }

    private boolean pollLogctl(Predicate<String> until, String... args) {
        return pollUntil(() -> until.test(logctl(args).stdout()));
    }

    private boolean pollUntil(BooleanSupplier condition) {
        for (int i = 0; i < 30; i++) {
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

    private void awaitControlPlane() throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            Logctl probe = logctl("list", "loggers", "org.jboss.as", "--show-all");
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
