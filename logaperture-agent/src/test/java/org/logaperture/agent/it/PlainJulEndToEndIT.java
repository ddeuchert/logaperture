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
import org.logaperture.control.jmx.DoctorFindingData;
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * doc/specs/plain-jvm-jul.md "Acceptance": the agent on a real {@code java} process that logs
 * through {@code java.util.logging} only, with no Logback on its class path, driven over JMX.
 */
class PlainJulEndToEndIT {

    private static final String WORKER = JulFixtureApp.WORKER_LOGGER;

    @TempDir
    private Path work;

    private final List<Process> processes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Process process : processes) {
            process.destroyForcibly();
        }
    }

    @Test
    void plainJulApp_getsTheWholeFeatureSet() throws Exception {
        Path output = work.resolve("run1.log");
        Process app = launch(output);
        String startup = waitFor(output, "FIXTURE-READY");
        assertTrue(startup.contains("MANAGER " + JulFixtureApp.FixtureLogManager.class.getName()),
                "the app's own java.util.logging.manager must win -- the agent never initialises JUL first:\n" + startup);
        assertTrue(startup.contains("INFO " + WORKER + " fixture app started"),
                "the app's own logging.properties (its format) must apply:\n" + startup);

        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        assertTrue(waitFor(output, "active (JVM)").contains("[logaperture] LogAperture"));
        assertEquals("java.util.logging (JUL)", proxy.environmentReport().getBackendName());
        assertFalse(proxy.listLoggers("global").isEmpty(), "the JDK's own JUL logger is listed here");

        List<HandlerInfoData> handlerRows = proxy.listHandlers();
        List<String> handlers = handlerRows.stream().map(HandlerInfoData::getRef).toList();
        assertTrue(handlers.contains("console") && handlers.contains("file"), "handlers named by type (#4): " + handlers);
        assertTrue(handlerRows.stream().anyMatch(h -> "file".equals(h.getRef()) && h.isPersistent()),
                "the JDK FileHandler writes a file, though its path is private (#5)");

        // set logger: FINE lines appear, the console following (default handlers follow).
        proxy.setLogger(WORKER, "DEBUG", "e2e", "SESSION", 60, false);
        String afterSet = logAndWait(app, output, "a");
        assertTrue(afterSet.contains("FINE " + WORKER + " fine a"), afterSet);

        // set handler: the console at WARN hides INFO; reset brings it back.
        proxy.setHandlerLevel("console", "WARN", "e2e", "SESSION", 0);
        String hidden = logAndWait(app, output, "b");
        assertFalse(hidden.contains("info b"), hidden);
        proxy.resetHandler("console", false);
        String shown = logAndWait(app, output, "c");
        assertTrue(shown.contains("INFO " + WORKER + " info c"), shown);

        // A drop rule suppresses matching lines only.
        proxy.addRuleDrop(WORKER, "drop-me", false, null, null, false, null, false, 60_000, "e2e", "SESSION", 0);
        String dropped = logAndWait(app, output, "d");
        assertTrue(dropped.contains("info d"), dropped);
        assertFalse(dropped.contains("drop-me d"), dropped);

        // top counts bytes; doctor runs its logger checks.
        List<DoctorFindingData> findings = proxy.diagnose();
        List<String> checks = findings.stream().map(DoctorFindingData::getCheck).toList();
        assertTrue(checks.contains("logger.verbosity-left-on"), "doctor checks: " + checks);
        assertFalse(checks.contains("handler.unbounded-growth"),
                "no false 'no size cap' on a FileHandler whose limits are private (#5): " + checks);

        // Re-reading logging.properties (#3): the JDK replaces the console handler with a new instance
        // at INFO and resets the logger's level. Both overrides are back at once, the handler's on the
        // new instance, which keeps the name "console".
        proxy.setHandlerLevel("console", "DEBUG", "e2e", "SESSION", 0);
        sendLine(app, "RELOAD");
        waitFor(output, "RELOADED");
        LoggerInfoData worker = proxy.listLoggers(WORKER).get(0);
        assertEquals("DEBUG", worker.getEffectiveLevel());
        assertTrue(worker.isOverrideActive());
        String afterReload = logAndWait(app, output, "e");
        assertTrue(afterReload.contains("FINE " + WORKER + " fine e"), afterReload);
        assertFalse(afterReload.contains("drop-me e"), "the drop rule is on the new handler too:\n" + afterReload);

        assertTrue(proxy.topLoggers(10).getLoggers().stream()
                        .anyMatch(l -> WORKER.equals(l.getLoggerName()) && l.getTotalBytes() > 0),
                "top should have counted the worker's output to the FileHandler");
    }

    @Test
    void stickyOverride_survivesARestart() throws Exception {
        Process first = launch(work.resolve("first.log"));
        waitFor(work.resolve("first.log"), "FIXTURE-READY");
        LevelControlMXBean firstProxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(first.pid()));
        firstProxy.setLogger(WORKER, "DEBUG", "sticky-e2e", "STICKY", 0, false);
        LevelControlEndToEndIT.stopFixtureProcess(first);

        Path second = work.resolve("second.log");
        Process app = launch(second);
        waitFor(second, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        LoggerInfoData worker = proxy.listLoggers(WORKER).get(0);
        assertEquals("DEBUG", worker.getEffectiveLevel());
        assertEquals("STICKY", worker.getTier());
        String logged = logAndWait(app, second, "s");
        assertTrue(logged.contains("FINE " + WORKER + " fine s"), logged);
    }

    private Process launch(Path output) throws IOException {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "system property logaperture.agent.jar must point at the shaded jar");
        Path properties = work.resolve("logging.properties");
        if (!Files.exists(properties)) {
            Files.writeString(properties, String.join("\n",
                    "handlers=java.util.logging.ConsoleHandler, java.util.logging.FileHandler",
                    ".level=INFO",
                    "java.util.logging.ConsoleHandler.level=INFO",
                    "java.util.logging.FileHandler.pattern=" + work.resolve("app%u.log").toString().replace('\\', '/'),
                    "java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter",
                    "java.util.logging.SimpleFormatter.format=%4$s %3$s %5$s%n",
                    ""));
        }
        List<String> command = List.of(
                System.getProperty("java.home") + "/bin/java",
                "-javaagent:" + agentJar,
                "-Dlogaperture.home=" + work.resolve("home"),
                "-cp", classPathWithoutLogback(),
                JulFixtureApp.class.getName(),
                properties.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    /** #1: with Logback on the class path the agent would bind Logback, so the fixture runs without it. */
    private static String classPathWithoutLogback() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("logback"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    /** Sends {@code LOG <tag>} and returns only the output written since, up to {@code LOGGED <tag>}. */
    private static String logAndWait(Process app, Path output, String tag) throws Exception {
        int before = Files.exists(output) ? Files.readString(output).length() : 0;
        sendLine(app, "LOG " + tag);
        return waitFor(output, "LOGGED " + tag).substring(before);
    }

    private static void sendLine(Process process, String line) throws IOException {
        OutputStream stdin = process.getOutputStream();
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static String waitFor(Path output, String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
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
