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

    private Process launch(Path output) throws IOException {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "system property logaperture.agent.jar must point at the shaded jar");
        Path config = work.resolve("log4j2-app.xml");
        Files.writeString(config, String.join("\n",
                "<Configuration status=\"warn\">",
                "  <Appenders><Console name=\"Out\" target=\"SYSTEM_OUT\">",
                "    <PatternLayout pattern=\"%level %logger %msg%n\"/></Console></Appenders>",
                "  <Loggers><Root level=\"info\"><AppenderRef ref=\"Out\"/></Root></Loggers>",
                "</Configuration>", ""));
        Path old = Path.of(System.getProperty("log4j2.old.dir"));
        assertTrue(Files.isDirectory(old), "the build copies Log4j 2.19.0 to " + old);
        // log4j-jul is the JUL manager, so it and its Log4j must be on the system class path.
        String log4j = old.resolve("log4j-api-2.19.0.jar") + File.pathSeparator + old.resolve("log4j-core-2.19.0.jar")
                + File.pathSeparator + old.resolve("log4j-jul-2.19.0.jar");
        List<String> command = List.of(
                System.getProperty("java.home") + "/bin/java",
                "-javaagent:" + agentJar,
                "-Dlogaperture.home=" + work.resolve("home"),
                "-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager",
                "-Dlog4j2.configurationFile=" + config,
                "-cp", classPathWithoutLoggingFrameworks() + File.pathSeparator + log4j,
                Log4jJulFixtureApp.class.getName());
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
