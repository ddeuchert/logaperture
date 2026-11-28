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

import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.control.jmx.LevelControlMXBean;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reproducer for <a href="https://github.com/ddeuchert/logaperture/issues/279">issue #279</a>: in a
 * Logback application, registering the control MXBean creates the platform MBean server, which
 * initialises {@code java.util.logging}. A {@code java.util.logging.manager} the application sets after
 * that is ignored.
 *
 * <p>Without the agent the application keeps its own manager. With the agent and no argument, it sets the property
 * once the startup line has appeared, that is, after the control surface is published, and gets the JDK's.
 * With {@code --wait-for-log-manager=on} (doc/specs/wait-for-log-manager.md) it keeps its own.
 *
 * <p>{@link #withTheAgent_theApplicationsManagerIsIgnored} is the default behaviour, which that spec leaves as it
 * was: publishing initialises {@code java.util.logging}.
 *
 * <p>{@code -Xlog:class+load} shows who loaded {@code LogManager}: nothing in the application before it
 * asks for it, the agent before that.
 */
class LogbackLateJulManagerIT {

    private static final String SETTING = "SETTING-MANAGER";

    /** The line {@code -Xlog:class+load} prints when {@code LogManager}'s root logger class loads. */
    private static final String JUL_LOADED = "java.util.logging.LogManager$RootLogger source:";

    @TempDir
    private Path work;

    private final List<Process> processes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Process process : processes) {
            process.destroyForcibly();
        }
    }

    /** The control: nothing but the application touches {@code java.util.logging}, so its choice holds. */
    @Test
    void withoutTheAgent_theApplicationsManagerWins() throws Exception {
        Path output = work.resolve("without.log");
        Process app = launch(output, List.of());
        waitFor(output, "APP-READY");

        String all = setManagerAndWait(app, output);

        assertTrue(all.contains("MANAGER " + LateJulManagerFixtureApp.MANAGER_CLASS), all);
        assertTrue(all.indexOf(JUL_LOADED) > all.indexOf(SETTING),
                "JUL must load only once the application asks for it:\n" + julLines(all));
    }

    /** Issue #279: the default behaviour, without {@code --wait-for-log-manager}. */
    @Test
    void withTheAgent_theApplicationsManagerIsIgnored() throws Exception {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "system property logaperture.agent.jar must point at the shaded jar");
        Path output = work.resolve("with.log");
        Process app = launch(output, List.of("-javaagent:" + agentJar));
        waitFor(output, "active (JVM)");
        waitFor(output, "APP-READY");

        String all = setManagerAndWait(app, output);

        assertTrue(all.contains("MANAGER " + LogManager.class.getName() + System.lineSeparator()),
                "without --wait-for-log-manager the agent initialises JUL first:\n" + all);
        int loaded = all.indexOf(JUL_LOADED);
        assertTrue(loaded >= 0 && loaded < all.indexOf(SETTING),
                "the agent should have loaded JUL before the application set its manager:\n" + julLines(all));
    }

    /** With {@code --wait-for-log-manager=on} the agent is never the first to touch JUL. */
    @Test
    void waitingForTheLogManager_theApplicationsManagerWins() throws Exception {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "system property logaperture.agent.jar must point at the shaded jar");
        Path output = work.resolve("waiting.log");
        Process app = launch(output, List.of("-javaagent:" + agentJar + "=--wait-for-log-manager=on"));
        waitFor(output, "APP-READY");
        Thread.sleep(2000); // ten times the window the agent leaves without the option
        assertTrue(!Files.readString(output).contains("active (JVM)"), "published before JUL was in use");
        // What logctl reads before connecting; reading it must not initialise JUL either (asserted below).
        assertEquals("java.util.logging", propertiesOf(app.pid()).getProperty("logaperture.waiting"));

        OutputStream stdin = app.getOutputStream();
        stdin.write("SET\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        waitFor(output, "active (JVM)");
        LevelControlMXBean proxy = LevelControlEndToEndIT.pollForMxBeanProxy(
                LevelControlEndToEndIT.attachAndConnect(app.pid()));
        proxy.setLogger(LateJulManagerFixtureApp.class.getName(), "DEBUG", "e2e", "SESSION", 60, false);
        assertNull(propertiesOf(app.pid()).getProperty("logaperture.waiting"));
        stdin.close();
        assertTrue(app.waitFor(30, TimeUnit.SECONDS), "the fixture app did not exit");

        String all = Files.readString(output);
        assertTrue(all.contains("MANAGER " + LateJulManagerFixtureApp.MANAGER_CLASS), julLines(all));
        assertTrue(all.indexOf(JUL_LOADED) > all.indexOf(SETTING), julLines(all));
        assertTrue(all.indexOf("active (JVM)") > all.indexOf(SETTING), julLines(all));
    }

    /** The case the command-line property can't cover: only the application's class loader sees the manager. */
    @Test
    void waitingForTheLogManager_aManagerOnlyTheApplicationCanLoadWins() throws Exception {
        String agentJar = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJar, "system property logaperture.agent.jar must point at the shaded jar");
        Path nested = Files.createDirectories(work.resolve("nested"));
        Path source = Files.writeString(work.resolve("NestedLogManager.java"),
                "public class NestedLogManager extends java.util.logging.LogManager {}\n");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        assertEquals(0, compiler.run(null, null, null, "-d", nested.toString(), source.toString()));
        Path output = work.resolve("nested.log");
        Process app = launch(output, List.of("-javaagent:" + agentJar + "=--wait-for-log-manager=on"),
                nested.toString(), "NestedLogManager");
        waitFor(output, "APP-READY");
        Thread.sleep(2000);

        String all = setManagerAndWait(app, output);

        assertTrue(all.contains("MANAGER NestedLogManager"), julLines(all));
    }

    private static Properties propertiesOf(long pid) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            return vm.getSystemProperties();
        } finally {
            vm.detach();
        }
    }

    private Process launch(Path output, List<String> agentOptions, String... appArguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.addAll(agentOptions);
        command.add("-Xlog:class+load:stdout:none");
        command.add("-Dlogaperture.home=" + work.resolve("home"));
        command.add("-cp");
        command.add(logbackAppClassPath());
        command.add(LateJulManagerFixtureApp.class.getName());
        command.addAll(List.of(appArguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    /** A Logback application and nothing more: the module's other test libraries stay off its class path. */
    private static String logbackAppClassPath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.contains("logback") || entry.contains("slf4j-api")
                        || entry.endsWith("test-classes"))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String setManagerAndWait(Process app, Path output) throws Exception {
        try (OutputStream stdin = app.getOutputStream()) {
            stdin.write("SET\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
        assertTrue(app.waitFor(30, TimeUnit.SECONDS), "the fixture app did not exit");
        String all = Files.readString(output);
        assertTrue(all.contains(SETTING) && all.contains("MANAGER "), all);
        return all;
    }

    /** The lines worth reading in a failure message; the whole class-load log is thousands of lines. */
    private static String julLines(String output) {
        return output.lines()
                .filter(line -> line.startsWith("java.util.logging.LogManager") || line.contains(SETTING)
                        || line.startsWith("MANAGER ") || line.contains("[logaperture]"))
                .collect(Collectors.joining("\n"));
    }

    private static void waitFor(Path output, String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String content = "";
        while (System.nanoTime() < deadline) {
            content = Files.exists(output) ? Files.readString(output) : "";
            if (content.contains(text)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("'" + text + "' never appeared in the fixture's output:\n" + julLines(content));
    }
}
