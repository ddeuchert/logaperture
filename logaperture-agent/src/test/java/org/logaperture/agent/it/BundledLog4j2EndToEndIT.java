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
 * doc/specs/class-loader-detector.md "Acceptance" (fat jar) for the first consumer, Log4j 2
 * (doc/specs/log4j2-adapter.md): the agent on a real {@code java} process whose Log4j lives in a
 * class loader of the application's own finds it, binds it once it has started, and leaves the
 * application's own configuration in force.
 */
class BundledLog4j2EndToEndIT {

    private static final String WORKER = BundledLog4j2FixtureApp.WORKER_LOGGER;

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
    void agentFindsALog4jOutsideTheSystemClassPath_andControlsItsLevels() throws Exception {
        Path output = work.resolve("run.log");
        Process app = launch(output);
        String startup = waitFor(output, "FIXTURE-READY");
        assertTrue(startup.contains("INFO fixture.Worker fixture app started"),
                "the app's own log4j2 configuration must apply -- the agent touches nothing first:\n" + startup);

        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        List<LoggerInfoData> found = pollForLogger(proxy, WORKER);
        assertEquals("INFO", found.get(0).getEffectiveLevel());
        String backend = proxy.environmentReport().getBackendName();
        assertTrue(backend != null && backend.contains("Log4j 2"), "backend: " + backend);

        proxy.setLogger(WORKER, "DEBUG", "e2e", "SESSION", 60, false);
        String debug = logAndWait(app, output, "a");
        assertTrue(debug.contains("DEBUG fixture.Worker debug a"), debug);

        proxy.resetLogger(WORKER, false);
        String reset = logAndWait(app, output, "b");
        assertTrue(reset.contains("INFO fixture.Worker info b"), reset);
        assertFalse(reset.contains("debug b"), reset);
    }

    /** A1, R1: a configuration reload wipes the level; the override is back before the call returns. */
    @Test
    void anOverrideSurvivesAReconfiguration_andANativeChangeAgainstIt() throws Exception {
        Path output = work.resolve("reconf.log");
        Process app = launch(output);
        waitFor(output, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        pollForLogger(proxy, WORKER);
        proxy.setLogger(WORKER, "DEBUG", "e2e", "SESSION", 120, false);

        sendAndWait(app, output, "RECONFIGURE r1", "DONE r1");
        String afterReload = logAndWait(app, output, "a");
        assertTrue(afterReload.contains("DEBUG fixture.Worker debug a"),
                "the reload must not drop the override:\n" + afterReload);

        // Spring's logging.level / an Actuator POST: a native change against the override.
        sendAndWait(app, output, "NATIVE WARN n1", "DONE n1");
        String afterNative = logAndWait(app, output, "b");
        assertTrue(afterNative.contains("DEBUG fixture.Worker debug b"),
                "a native change must not undo the override:\n" + afterNative);
        LoggerInfoData row = proxy.listLoggers(WORKER).get(0);
        assertEquals("DEBUG", row.getEffectiveLevel());
        assertTrue(row.isOverrideActive());

        proxy.resetLogger(WORKER, false);
        String reset = logAndWait(app, output, "c");
        assertFalse(reset.contains("debug c"), reset);
    }

    private static void sendAndWait(Process app, Path output, String command, String done) throws Exception {
        OutputStream stdin = app.getOutputStream();
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        waitFor(output, done);
    }

    /** C2: the adapter's classes are in the agent jar, but only where a BridgeClassLoader reads them. */
    @Test
    void adapterClassesAreStoredUnderThePrefix_andNowhereElse() throws Exception {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar);
        List<String> entries;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(agentJar)) {
            entries = zip.stream().map(java.util.zip.ZipEntry::getName).toList();
        }
        String inPrefix = "META-INF/logaperture/log4j2/org/logaperture/adapter/log4j2/Log4j2Framework.class";
        assertTrue(entries.contains(inPrefix), "missing " + inPrefix);
        assertEquals(List.of(), entries.stream()
                .filter(name -> name.startsWith("org/logaperture/adapter/log4j2/")).toList(),
                "a class at the jar's root could be loaded by the system class loader");
        assertEquals(List.of(), entries.stream().filter(name -> name.startsWith("org/apache/logging/log4j/")).toList(),
                "the agent must never bundle a logging framework");
    }

    private static List<LoggerInfoData> pollForLogger(LevelControlMXBean proxy, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<LoggerInfoData> rows = proxy.listLoggers(name);
            if (!rows.isEmpty()) {
                return rows;
            }
            Thread.sleep(100);
        }
        fail("the agent never listed " + name + ": the Log4j in the app's own class loader was not bound");
        throw new AssertionError("unreachable");
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
        List<String> command = List.of(
                System.getProperty("java.home") + "/bin/java",
                "-javaagent:" + agentJar,
                "-Dlogaperture.home=" + work.resolve("home"),
                "-Dfixture.log4j.jars=" + log4jJars(),
                "-cp", classPathWithoutLog4jAndLogback(),
                BundledLog4j2FixtureApp.class.getName(),
                config.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    private static String log4jJars() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.contains("log4j-api") || entry.contains("log4j-core"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String classPathWithoutLog4jAndLogback() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("logback") && !entry.contains("log4j-api")
                        && !entry.contains("log4j-core"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String logAndWait(Process app, Path output, String tag) throws Exception {
        int before = Files.exists(output) ? Files.readString(output).length() : 0;
        OutputStream stdin = app.getOutputStream();
        stdin.write(("LOG " + tag + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        return waitFor(output, "LOGGED " + tag).substring(before);
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
