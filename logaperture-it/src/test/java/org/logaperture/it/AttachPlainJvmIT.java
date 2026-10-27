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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code logctl attach} against real JVMs that were started without {@code -javaagent}
 * (doc/specs/logctl-attach.md "Test plan"): a Logback application, a {@code java.util.logging}
 * application, and one that logs through neither. Each fixture runs in its own JVM; {@code logctl} is
 * the real shaded jar, run as a separate process. No Docker needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachPlainJvmIT {

    private static final String LOGBACK_FIXTURE = """
            public class LogbackFixture {
                public static void main(String[] args) throws Exception {
                    ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                            .setLevel(ch.qos.logback.classic.Level.INFO);
                    org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("com.acme.Fixture");
                    while (true) {
                        log.info("fixture-info");
                        log.debug("fixture-debug");
                        Thread.sleep(200);
                    }
                }
            }
            """;

    private static final String JUL_FIXTURE = """
            public class JulFixture {
                public static void main(String[] args) throws Exception {
                    java.util.logging.Logger log = java.util.logging.Logger.getLogger("com.acme.Fixture");
                    while (true) {
                        log.info("fixture-info");
                        Thread.sleep(200);
                    }
                }
            }
            """;

    private static final String QUIET_FIXTURE = """
            public class QuietFixture {
                public static void main(String[] args) throws Exception {
                    System.out.println("fixture-ready");
                    while (true) {
                        Thread.sleep(1000);
                    }
                }
            }
            """;

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private Path scratch;

    private Path classes;
    private String fixtureClasspath;
    private String cliJar;
    private final List<Process> fixtures = new ArrayList<>();
    private int fixtureCount;

    @BeforeAll
    void compileFixtures() throws Exception {
        scratch = Files.createTempDirectory("logaperture-attach-it");
        cliJar = System.getProperty("logaperture.cli.jar");
        assertNotNull(cliJar, "logaperture.cli.jar must be set (reactor build order provides it)");
        assertTrue(Files.isRegularFile(Path.of(cliJar)), "not built: " + cliJar + " -- run `mvn package`");

        fixtureClasspath = String.join(java.io.File.pathSeparator, jarOf(org.slf4j.Logger.class),
                jarOf(ch.qos.logback.classic.Logger.class), jarOf(ch.qos.logback.core.Appender.class));
        classes = Files.createDirectories(scratch.resolve("fixtures"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        for (String[] source : new String[][] {{"LogbackFixture", LOGBACK_FIXTURE}, {"JulFixture", JUL_FIXTURE},
                {"QuietFixture", QUIET_FIXTURE}}) {
            Path file = Files.writeString(scratch.resolve(source[0] + ".java"), source[1]);
            int status = compiler.run(null, null, null, "-cp", fixtureClasspath, "-d", classes.toString(),
                    file.toString());
            assertEquals(0, status, "compiling " + source[0]);
        }
    }

    @AfterAll
    void removeScratch() throws IOException {
        try (var paths = Files.walk(scratch)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @AfterEach
    void stopFixtures() {
        for (Process fixture : fixtures) {
            fixture.destroyForcibly();
        }
        fixtures.clear();
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void aRunningLogbackApplicationCanBeAttachedAndControlled() throws Exception {
        Fixture app = start("LogbackFixture", List.of());
        awaitOutput(app, "fixture-info");
        assertFalse(app.output().contains("fixture-debug"), "root is INFO, so no debug lines yet");

        Result attached = logctl("attach", Long.toString(app.pid()));
        assertEquals(0, attached.exitCode(), attached.stderr());
        assertTrue(attached.stdout().contains("is active in PID " + app.pid()), attached.stdout());

        Result raised = logctl("set", "logger", "com.acme", "DEBUG", "for", "5m", "--pid", Long.toString(app.pid()));
        assertEquals(0, raised.exitCode(), raised.stderr());
        assertTrue(awaitOutput(app, "fixture-debug"), "the raised level reaches the application's own output");

        Result reset = logctl("reset", "loggers", "--pid", Long.toString(app.pid()));
        assertEquals(0, reset.exitCode(), reset.stderr());
    }

    @Test
    void aRunningJulApplicationCanBeAttachedAndControlled() throws Exception {
        Fixture app = start("JulFixture", List.of());
        awaitOutput(app, "fixture-info");

        Result attached = logctl("attach", Long.toString(app.pid()));
        assertEquals(0, attached.exitCode(), attached.stderr());
        assertTrue(attached.stdout().contains("is active in PID") && attached.stdout().contains("java.util.logging"),
                attached.stdout() + "\n--- application output:\n" + app.output());

        Result raised = logctl("set", "logger", "com.acme.Fixture", "DEBUG", "for", "5m", "--pid",
                Long.toString(app.pid()));
        assertEquals(0, raised.exitCode(), raised.stderr() + "\n--- application output:\n" + app.output());
        Result status = logctl("status", "--pid", Long.toString(app.pid()));
        assertTrue(status.stdout().contains("com.acme.Fixture"), status.stdout());
    }

    @Test
    void aSecondAttachChangesNothingAndSucceeds() throws Exception {
        Fixture app = start("LogbackFixture", List.of());
        awaitOutput(app, "fixture-info");
        assertEquals(0, logctl("attach", Long.toString(app.pid())).exitCode());

        Result again = logctl("attach", Long.toString(app.pid()));

        assertEquals(0, again.exitCode(), again.stderr());
        assertTrue(again.stdout().contains("already runs LogAperture"), again.stdout());
        assertTrue(count(app.output(), "A Java agent has been loaded dynamically") <= 1,
                "no second load, so the JVM's dynamic-loading warning appears at most once");
    }

    @Test
    void doctorSaysWhyTheAgentOrderCheckIsMissing() throws Exception {
        Fixture app = start("LogbackFixture", List.of());
        awaitOutput(app, "fixture-info");
        assertEquals(0, logctl("attach", Long.toString(app.pid())).exitCode());

        Result doctor = logctl("doctor", "--pid", Long.toString(app.pid()));

        assertTrue(doctor.stdout().contains("LogAperture was loaded by attach"), doctor.stdout());
    }

    @Test
    void anApplicationThatLogsThroughNeitherIsLoadedButNotActiveAndSaysSo() throws Exception {
        Fixture app = start("QuietFixture", List.of());
        awaitOutput(app, "fixture-ready");

        Result attached = logctl("attach", Long.toString(app.pid()));

        assertEquals(0, attached.exitCode(), attached.stderr());
        assertTrue(attached.stdout().contains("not active yet"), attached.stdout());
        assertTrue(awaitOutput(app, "LogAperture attached; waiting for the application to start logging"),
                "the agent says why on the application's own standard error:\n" + app.output());
    }

    @Test
    void aJvmStartedWithDynamicLoadingOffRefusesWithTheWayOut() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "-XX:-EnableDynamicAgentLoading exists from Java 21");
        Fixture app = start("LogbackFixture", List.of("-XX:-EnableDynamicAgentLoading"));
        awaitOutput(app, "fixture-info");

        Result attached = logctl("attach", Long.toString(app.pid()));

        assertEquals(5, attached.exitCode(), attached.stdout() + attached.stderr());
        assertTrue(attached.stderr().contains("-XX:-EnableDynamicAgentLoading"), attached.stderr());
        assertTrue(attached.stderr().contains("-javaagent:logaperture-agent.jar"), attached.stderr());
    }

    @Test
    void anUnknownPidIsExitThree() throws Exception {
        Result attached = logctl("attach", "999999");
        assertEquals(3, attached.exitCode(), attached.stdout() + attached.stderr());
    }

    @Test
    void agentOptionsGivenToAttachReachTheAgent() throws Exception {
        Fixture app = start("LogbackFixture", List.of());
        awaitOutput(app, "fixture-info");
        assertEquals(0, logctl("attach", Long.toString(app.pid()), "--storm-detection", "on").exitCode());

        Result storms = logctl("storms", "--pid", Long.toString(app.pid()));

        assertEquals(0, storms.exitCode(), storms.stderr());
        assertFalse(storms.stdout().toLowerCase().contains("disabled"), storms.stdout());
    }

    // --- harness -----------------------------------------------------------------------------------

    private record Fixture(Process process, Path outputFile) {
        long pid() {
            return process.pid();
        }

        String output() {
            try {
                return Files.readString(outputFile);
            } catch (IOException e) {
                return "";
            }
        }
    }

    private Fixture start(String mainClass, List<String> jvmOptions) throws IOException {
        int n = ++fixtureCount;
        Path output = scratch.resolve("fixture-" + n + ".out");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dlogaperture.home=" + scratch.resolve("target-home-" + n));
        command.add("-Dlogaperture.instanceId=fixture-" + n);
        command.addAll(jvmOptions);
        command.add("-cp");
        // Only the Logback fixture gets Logback: with it on the class path the agent treats the application as a
        // Logback one (doc/specs/plain-jvm-jul.md #1), whatever the code then logs through.
        command.add("LogbackFixture".equals(mainClass) ? classes + java.io.File.pathSeparator + fixtureClasspath
                : classes.toString());
        command.add(mainClass);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
                .start();
        fixtures.add(process);
        return new Fixture(process, output);
    }

    private Result logctl(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dlogaperture.home=" + scratch.resolve("cli-home"));
        command.add("-jar");
        command.add(cliJar);
        command.addAll(List.of(args));
        Path out = Files.createTempFile(scratch, "logctl", ".out");
        Path err = Files.createTempFile(scratch, "logctl", ".err");
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile())
                .start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("logctl " + String.join(" ", args) + " did not finish in 90 s");
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static boolean awaitOutput(Fixture app, String text) throws InterruptedException {
        BooleanSupplier seen = () -> app.output().contains(text);
        for (int attempt = 0; attempt < 150; attempt++) {
            if (seen.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return seen.getAsBoolean();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
