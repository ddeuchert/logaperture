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

    /** The matrix cell (doc/specs/log4j2-adapter.md Slice 8): which Log4j, and how the fixture logs. */
    private static final String MODE = System.getProperty("log4j2.mode", "sync");
    private static final String VERSION = System.getProperty("log4j2.version", "");

    /** Behind an {@code <Async>} appender the handler is the {@code <Async>} one (Slice 6, S6-1). */
    private static String consoleHandler() {
        return "async-appender".equals(MODE) ? "AsyncOut" : "Out";
    }

    private static String diskHandler() {
        return "async-appender".equals(MODE) ? "AsyncDisk" : "Disk";
    }

    /** Trim cannot work on 2.25.0 to 2.25.3 (A4): the cell asserts that, rather than skipping (M8-6). */
    private static boolean trimExpectedOff() {
        return VERSION.matches("2\\.25\\.[0-3]");
    }

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

        // Straight after one another, so the native change is milliseconds after the reload, well inside the
        // 5 s window in which it is put back and audited but not warned about (A1).
        sendAndWait(app, output, "RECONFIGURE r1", "DONE r1");
        sendAndWait(app, output, "NATIVE WARN n1", "DONE n1");
        String afterReload = logAndWait(app, output, "a");
        assertTrue(afterReload.contains("DEBUG fixture.Worker debug a"),
                "neither the reload nor the native change may drop the override:\n" + afterReload);
        LoggerInfoData row = proxy.listLoggers(WORKER).get(0);
        assertEquals("DEBUG", row.getEffectiveLevel());
        assertTrue(row.isOverrideActive());
        assertFalse(Files.readString(output).contains("outside LogAperture"),
                "no warning within 5 s of a reconfiguration:\n" + Files.readString(output));

        // Later, an Actuator-style change: put back, and now the warning names the logger and the override.
        Thread.sleep(5_500);
        sendAndWait(app, output, "NATIVE ERROR n2", "DONE n2");
        String warned = waitFor(output, "outside LogAperture");
        assertTrue(warned.contains("logger '" + WORKER + "' was changed to ERROR outside LogAperture"), warned);
        assertTrue(logAndWait(app, output, "c").contains("DEBUG fixture.Worker debug c"));

        // A2: reset gives back the level the native source set last (ERROR), not the INFO the file declared.
        proxy.resetLogger(WORKER, false);
        String reset = logAndWait(app, output, "d");
        assertTrue(reset.contains("ERROR fixture.Worker error d"), reset);
        assertFalse(reset.contains("info d"), "the native source's ERROR is the baseline now:\n" + reset);
    }

    /** The gate (A3): a drop rule removes its lines, and is back on the new configuration after a reload. */
    @Test
    void aDropRule_removesItsLines_andSurvivesAReconfiguration() throws Exception {
        Path output = work.resolve("drop.log");
        Process app = launch(output);
        waitFor(output, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        pollForLogger(proxy, WORKER);

        String before = logAndWait(app, output, "a");
        assertTrue(before.contains("INFO fixture.Worker drop-me a"), "no rule yet:\n" + before);

        proxy.addRuleDrop(WORKER, "drop-me", false, null, null, false, null, false, 60_000, "e2e", "SESSION", 0);
        String dropped = logAndWait(app, output, "b");
        assertTrue(dropped.contains("INFO fixture.Worker info b"), "other lines are kept:\n" + dropped);
        assertFalse(dropped.contains("drop-me b"), "the rule removes its lines:\n" + dropped);

        sendAndWait(app, output, "RECONFIGURE r1", "DONE r1");
        String afterReload = logAndWait(app, output, "c");
        assertTrue(afterReload.contains("INFO fixture.Worker info c"), afterReload);
        assertFalse(afterReload.contains("drop-me c"),
                "a reload drops the filter with the old configuration; core puts it back:\n" + afterReload);
    }

    /** The slot wrapper (A4): a trim rule cuts the trace on every converter, and is back after a reload. */
    @Test
    void aTrimRule_cutsTheTrace_withNoSyntheticClassName_andSurvivesAReconfiguration() throws Exception {
        Path output = work.resolve("trim.log");
        Process app = launch(output);
        waitFor(output, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        pollForLogger(proxy, WORKER);

        String before = exceptionAndWait(app, output, "a");
        assertTrue(before.contains("at org."), "no rule yet, the whole trace:\n" + before);
        assertFalse(before.contains("stack trace trimmed"), before);

        proxy.addRuleTrim(WORKER, null, false, null, null, false, null, 1, false, "e2e", "SESSION", 60);
        String trimmed = exceptionAndWait(app, output, "b");
        var trimRows = proxy.diagnose().stream().filter(f -> "log4j2.trim".equals(f.getCheck())).toList();
        if (trimExpectedOff()) {
            // 2.25.0 to 2.25.3 (A4): nothing is wrapped, the trace is whole, and doctor says why (M8-6).
            assertTrue(trimmed.contains("at org."), "trim is off on Log4j " + VERSION + ":\n" + trimmed);
            assertFalse(trimmed.contains("stack trace trimmed"), trimmed);
            assertEquals(1, trimRows.size(), "doctor says trim is off on " + VERSION);
        } else {
            assertTrue(trimRows.isEmpty(), "trim works on " + VERSION + ", so doctor says nothing about it; it said: "
                    + trimRows.stream().map(f -> f.getSummary() + " / " + f.getDetail()).toList());
            assertTrue(trimmed.contains("java.lang.RuntimeException: outer failure b [stack trace trimmed: "), trimmed);
            assertTrue(trimmed.contains("Caused by: java.lang.IllegalStateException: root cause b [stack trace trimmed: "),
                    "the cause is cut too:\n" + trimmed);
            assertFalse(trimmed.contains("Synthetic") || trimmed.contains("TrimmedThrowables"),
                    "no synthetic class name:\n" + trimmed);
        }

        sendAndWait(app, output, "RECONFIGURE r1", "DONE r1");
        String afterReload = exceptionAndWait(app, output, "c");
        if (trimExpectedOff()) {
            assertFalse(afterReload.contains("stack trace trimmed"), "still off after a reload:\n" + afterReload);
            assertTrue(afterReload.contains("at org."), afterReload);
            assertEquals(1, proxy.diagnose().stream().filter(f -> "log4j2.trim".equals(f.getCheck())).count(),
                    "doctor still says so after a reload");
        } else {
            assertTrue(afterReload.contains("[stack trace trimmed: "),
                    "a reload drops the wrappers with the old configuration; core puts them back:\n" + afterReload);
        }
        assertEquals(List.of(2L), proxy.listRules().stream().map(r -> r.getHitCount()).toList(),
                "counted once per event by the gate (also where trim cannot be applied), and not again by the wrapper");
    }

    /** Handler levels (A5): the appender is listed by its file name, set hides lines, reset and a reload keep faith. */
    @Test
    void aHandlerLevel_hidesLines_resetBringsThemBack_andSurvivesAReconfiguration() throws Exception {
        Path output = work.resolve("handler.log");
        Process app = launch(output);
        waitFor(output, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        pollForLogger(proxy, WORKER);
        assertTrue(proxy.listHandlers().stream().anyMatch(h -> consoleHandler().equals(h.getRef())),
                "the appender is listed by its log4j2.xml name: " + proxy.listHandlers());

        assertTrue(logAndWait(app, output, "a").contains("INFO fixture.Worker info a"));

        proxy.setHandlerLevel(consoleHandler(), "ERROR", "e2e", "SESSION", 0);
        String hidden = logAndWait(app, output, "b");
        assertFalse(hidden.contains("info b"), "INFO is hidden by the handler:\n" + hidden);
        assertTrue(hidden.contains("ERROR fixture.Worker error b"), hidden);

        sendAndWait(app, output, "RECONFIGURE r1", "DONE r1");
        String afterReload = logAndWait(app, output, "c");
        assertFalse(afterReload.contains("info c"),
                "a reload gives the file's levels back; core puts the handler override back:\n" + afterReload);

        proxy.resetHandler(consoleHandler(), false);
        assertTrue(logAndWait(app, output, "d").contains("INFO fixture.Worker info d"));
    }

    /** Diagnostics (A9): a plain File appender is found by `doctor` by its log4j2.xml name. */
    @Test
    void doctorFindsAPlainFileAppender_byItsName() throws Exception {
        Path output = work.resolve("doctor.log");
        Process app = launch(output);
        waitFor(output, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        pollForLogger(proxy, WORKER);

        var findings = proxy.diagnose();
        var growth = findings.stream()
                .filter(f -> "handler.unbounded-growth".equals(f.getCheck()) && diskHandler().equals(f.getSubject()))
                .findFirst();
        assertTrue(growth.isPresent(), "the plain File appender has no size cap: " + findings);
        assertTrue(findings.stream().noneMatch(f -> consoleHandler().equals(f.getSubject())
                && "handler.unbounded-growth".equals(f.getCheck())), "the console is not a file: " + findings);
    }

    /**
     * A burst with no stale or missing trim (Slice 8, "Acceptance" Async): events alternate a message the rule
     * matches with one it does not, logged as fast as the fixture can. In the asynchronous modes the events
     * reuse {@code MutableLogEvent}s and cross threads; every matching one is still trimmed, no other one is,
     * and the rule counts each trimmed event once.
     */
    @Test
    void aBurstOfMixedEvents_trimsExactlyTheMatchingOnes_andCountsEachOnce() throws Exception {
        Path output = work.resolve("burst.log");
        Process app = launch(output);
        waitFor(output, "FIXTURE-READY");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        pollForLogger(proxy, WORKER);
        proxy.addRuleTrim(WORKER, "TRIMME", false, null, null, false, null, 1, false, "e2e", "SESSION", 60);

        int count = 400;
        int before = Files.readString(output).length();
        sendAndWait(app, output, "BURST " + count + " z", "LOGGED z");
        String text = waitFor(output, "SENTINEL z").substring(before);

        int matching = 0;
        int others = 0;
        for (String event : text.split("\\R(?=ERROR fixture\\.Worker )")) {
            boolean trimmedEvent = event.contains("[stack trace trimmed: ");
            if (event.contains("TRIMME z-")) {
                matching++;
                assertEquals(!trimExpectedOff(), trimmedEvent, "a matching event:\n" + event);
            } else if (event.contains("KEEPME z-")) {
                others++;
                assertFalse(trimmedEvent, "a stale trim on an event the rule does not match:\n" + event);
                assertTrue(event.contains("at org.") || event.contains("\tat "), "its whole trace:\n" + event);
            }
        }
        assertEquals(count / 2, matching, "every matching event was written");
        assertEquals(count / 2, others, "every other event was written");
        assertEquals(List.of((long) (count / 2)), proxy.listRules().stream().map(r -> r.getHitCount()).toList(),
                "counted once per matching event");
    }

    /** Sends {@code LOGEX <tag>} and returns only what was written since, up to {@code LOGGED <tag>}. */
    private static String exceptionAndWait(Process app, Path output, String tag) throws Exception {
        int before = Files.exists(output) ? Files.readString(output).length() : 0;
        OutputStream stdin = app.getOutputStream();
        stdin.write(("LOGEX " + tag + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        return settled(output, tag).substring(before);
    }

    /**
     * The output once the fixture has logged {@code tag} and the logging framework has written all of it:
     * {@code LOGGED} is printed by the application, {@code SENTINEL} is the last event it logged, which an
     * asynchronous appender or logger writes after everything before it.
     */
    private static String settled(Path output, String tag) throws Exception {
        waitFor(output, "LOGGED " + tag);
        return waitFor(output, "SENTINEL " + tag);
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
        String pattern = "<PatternLayout pattern=\"%level %logger %msg%n\"/>";
        boolean asyncAppender = "async-appender".equals(MODE);
        Files.writeString(config, String.join("\n",
                "<Configuration status=\"warn\">",
                "  <Appenders><Console name=\"Out\" target=\"SYSTEM_OUT\">" + pattern + "</Console>",
                "    <File name=\"Disk\" fileName=\"" + work.resolve("app.log") + "\">" + pattern + "</File>",
                asyncAppender ? "    <Async name=\"AsyncOut\"><AppenderRef ref=\"Out\"/></Async>" : "",
                asyncAppender ? "    <Async name=\"AsyncDisk\"><AppenderRef ref=\"Disk\"/></Async>" : "",
                "  </Appenders>",
                "  <Loggers><Root level=\"info\">",
                asyncAppender ? "    <AppenderRef ref=\"AsyncOut\"/><AppenderRef ref=\"AsyncDisk\"/>"
                        : "    <AppenderRef ref=\"Out\"/><AppenderRef ref=\"Disk\"/>",
                "  </Root></Loggers>",
                "</Configuration>", ""));
        List<String> command = new ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/java",
                "-javaagent:" + agentJar,
                "-Dlogaperture.home=" + work.resolve("home"),
                "-Dfixture.log4j.jars=" + log4jJars()));
        if ("async-loggers".equals(MODE)) {
            command.add("-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector");
        }
        command.addAll(List.of(
                "-cp", classPathWithoutLog4jAndLogback(),
                BundledLog4j2FixtureApp.class.getName(),
                config.toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    private static String fileName(String classPathEntry) {
        return Path.of(classPathEntry).getFileName().toString();
    }

    /**
     * The Log4j adapter's classes, as the reactor puts them on this JVM's class path ({@code .../logaperture-adapter-log4j2/
     * target/classes} or its jar): never on the system class path in production, where they are stored under a prefix
     * inside the agent jar, so the fixture must not have them there either. Matched on a path segment or the jar's name,
     * not on a substring of the whole path.
     */
    private static boolean isAdapterModule(String classPathEntry) {
        Path entry = Path.of(classPathEntry);
        for (Path segment : entry) {
            if (segment.toString().equals("logaperture-adapter-log4j2")) {
                return true;
            }
        }
        return fileName(classPathEntry).startsWith("logaperture-adapter-log4j2-");
    }

    private static String log4jJars() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.contains("log4j-api") || entry.contains("log4j-core")
                        || ("async-loggers".equals(MODE) && fileName(entry).startsWith("disruptor-")))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String classPathWithoutLog4jAndLogback() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("logback") && !entry.contains("log4j-api")
                        && !entry.contains("log4j-core") && !fileName(entry).startsWith("disruptor-")
                        && !isAdapterModule(entry))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String logAndWait(Process app, Path output, String tag) throws Exception {
        int before = Files.exists(output) ? Files.readString(output).length() : 0;
        OutputStream stdin = app.getOutputStream();
        stdin.write(("LOG " + tag + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        return settled(output, tag).substring(before);
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
