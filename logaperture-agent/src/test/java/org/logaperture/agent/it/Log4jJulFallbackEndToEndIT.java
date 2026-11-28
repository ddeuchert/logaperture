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
package org.logaperture.agent.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.control.jmx.LevelControlMXBean;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * doc/specs/jul-fallback-on-log4j-bind-failure.md "Acceptance": an application on {@code log4j-jul} with a Log4j 2
 * below the floor (2.19.0, on the system class path, as {@code log4j-jul} needs it) still gets level control, on its
 * {@code java.util.logging} loggers, and {@code doctor} says why Log4j 2 is not the one controlled.
 */
class Log4jJulFallbackEndToEndIT {

    private static final String WORKER = Log4jJulFixtureApp.WORKER_LOGGER;
    private static final String LOG4J_JUL = "org.apache.logging.log4j.jul.LogManager";

    @TempDir
    private Path work;

    private final List<Process> processes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        processes.forEach(Process::destroyForcibly);
    }

    @Test
    void aLog4jBelowTheFloorBehindLog4jJul_fallsBackToJul() throws Exception {
        Path output = work.resolve("app.log");
        Process app = launch(output);
        String started = waitFor(output, "FIXTURE-READY");
        assertTrue(started.contains("MANAGER org.apache.logging.log4j.jul.LogManager"), started);

        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        waitFor(output, "controlling java.util.logging instead");
        // The context is installed on the detector thread, after the line: wait for it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (proxy.listLoggers(WORKER).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("java.util.logging (JUL)", proxy.environmentReport().getBackendName());
        assertEquals(1, proxy.listLoggers(WORKER).size(), "the worker is listed once: " + proxy.listLoggers(WORKER));

        proxy.setLogger(WORKER, "DEBUG", "e2e", "SESSION", 60, false);
        int before = Files.readString(output).length();
        send(app, "LOG a");
        String after = waitFor(output, "LOGGED a").substring(before);
        assertTrue(after.contains("fine a"), "the override reaches the application's output:\n" + after);

        var rows = proxy.diagnose().stream().filter(f -> "log4j2.version".equals(f.getCheck())).toList();
        assertEquals(1, rows.size(), "doctor says why: " + proxy.diagnose());
        assertTrue(rows.get(0).getDetail().contains("java.util.logging is controlled instead"), rows.get(0).getDetail());
    }

    /**
     * doc/specs/wait-for-log-manager.md "Which setups": the property is not set when the agent starts, so only the
     * manager in place once JUL is in use tells that the JUL tree is a front for Log4j.
     */
    @Test
    void log4jJulSetInCode_aSupportedLog4jIsTheOnlyContext() throws Exception {
        Path output = work.resolve("supported.log");
        Path jul = Path.of(System.getProperty("log4j2.jul.dir"), "log4j-jul.jar");
        assertTrue(Files.isRegularFile(jul), "the build copies log4j-jul to " + jul);
        List<String> testClassPath = Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
        String log4j = testClassPath.stream()
                .filter(entry -> entry.contains("log4j-api") || entry.contains("log4j-core"))
                .collect(Collectors.joining(File.pathSeparator));
        Process app = launch(output, log4j + File.pathSeparator + jul,
                List.of("=--wait-for-log-manager=on"), List.of(), List.of(LOG4J_JUL));
        String started = waitFor(output, "FIXTURE-READY");
        assertTrue(started.contains("MANAGER " + LOG4J_JUL), started);
        waitFor(output, "active (JVM)");
        Thread.sleep(1000); // a JUL context would be installed by now: the same thread publishes the surface

        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        assertEquals(1, proxy.listLoggers(WORKER).size(), "the worker is listed once: " + proxy.listLoggers(WORKER));
        assertTrue(proxy.environmentReport().getBackendName().startsWith("Log4j 2"),
                proxy.environmentReport().getBackendName());
    }

    /** The #311 fallback, with the manager set in code. */
    @Test
    void log4jJulSetInCode_aLog4jBelowTheFloorStillFallsBackToJul() throws Exception {
        Path output = work.resolve("old-in-code.log");
        Process app = launch(output, oldLog4j(), List.of("=--wait-for-log-manager=on"), List.of(),
                List.of(LOG4J_JUL));
        waitFor(output, "FIXTURE-READY");
        waitFor(output, "controlling java.util.logging instead");

        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (proxy.listLoggers(WORKER).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("java.util.logging (JUL)", proxy.environmentReport().getBackendName());
        assertEquals(1, proxy.listLoggers(WORKER).size(), "the worker is listed once: " + proxy.listLoggers(WORKER));
    }

    private Process launch(Path output) throws IOException {
        return launch(output, oldLog4j(), List.of(), List.of("-Djava.util.logging.manager=" + LOG4J_JUL), List.of());
    }

    private String oldLog4j() {
        Path old = Path.of(System.getProperty("log4j2.old.dir"));
        assertTrue(Files.isDirectory(old), "the build copies Log4j 2.19.0 to " + old);
        return old.resolve("log4j-api-2.19.0.jar") + File.pathSeparator + old.resolve("log4j-core-2.19.0.jar")
                + File.pathSeparator + old.resolve("log4j-jul-2.19.0.jar");
    }

    /**
     * @param log4j          the Log4j jars: {@code log4j-jul} is the JUL manager, so they are on the system class path
     * @param agentArguments empty, or the one {@code =<arguments>} to append to the {@code -javaagent} entry
     */
    private Process launch(Path output, String log4j, List<String> agentArguments, List<String> jvmOptions,
            List<String> appArguments) throws IOException {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "system property logaperture.agent.jar must point at the shaded jar");
        Path config = work.resolve("log4j2-app.xml");
        Files.writeString(config, String.join("\n",
                "<Configuration status=\"warn\">",
                "  <Appenders><Console name=\"Out\" target=\"SYSTEM_OUT\">",
                "    <PatternLayout pattern=\"%level %logger %msg%n\"/></Console></Appenders>",
                "  <Loggers><Root level=\"info\"><AppenderRef ref=\"Out\"/></Root></Loggers>",
                "</Configuration>", ""));
        List<String> command = new ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/java",
                "-javaagent:" + agentJar + String.join("", agentArguments),
                "-Dlogaperture.home=" + work.resolve("home"),
                "-Dlog4j2.configurationFile=" + config));
        command.addAll(jvmOptions);
        command.addAll(List.of("-cp", classPathWithoutLoggingFrameworks() + File.pathSeparator + log4j,
                Log4jJulFixtureApp.class.getName()));
        command.addAll(appArguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    /** No Logback (the agent would bind it) and none of the reactor's Log4j, which is not the old one under test. */
    private static String classPathWithoutLoggingFrameworks() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("logback") && !entry.contains("log4j")
                        && !entry.contains("logaperture-adapter-log4j2"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static void send(Process process, String line) throws IOException {
        OutputStream stdin = process.getOutputStream();
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static String waitFor(Path output, String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String content = "";
        while (System.nanoTime() < deadline) {
            content = Files.exists(output) ? Files.readString(output) : "";
            if (content.contains(text)) {
                return content;
            }
            Thread.sleep(50);
        }
        fail("'" + text + "' never appeared in the fixture's output:\n" + content);
        throw new AssertionError("unreachable");
    }
}
