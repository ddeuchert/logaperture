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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * doc/specs/wait-for-log-manager.md "logctl": the real {@code logctl} jar against a Logback application started with
 * {@code --wait-for-log-manager=on} that chooses its own {@code java.util.logging.manager} when told to. Nothing
 * {@code logctl} does while the agent waits may take that choice from it. No Docker needed.
 */
class WaitForLogManagerIT {

    private static final String FIXTURE = """
            public class LateManagerFixture {
                public static class Manager extends java.util.logging.LogManager {
                }

                public static void main(String[] args) throws Exception {
                    org.slf4j.LoggerFactory.getLogger("com.acme.Fixture").info("fixture-started");
                    var stdin = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                    stdin.readLine();
                    System.setProperty("java.util.logging.manager", "LateManagerFixture$Manager");
                    System.out.println("MANAGER " + java.util.logging.LogManager.getLogManager().getClass().getName());
                    stdin.readLine();
                }
            }
            """;

    private record Result(int exitCode, String stdout, String stderr) {
    }

    @TempDir
    private Path scratch;

    private Process app;

    @AfterEach
    void stopFixture() {
        if (app != null) {
            app.destroyForcibly();
        }
    }

    @Test
    void logctlLeavesAWaitingJvmAlone_andControlsItOnceTheApplicationHasItsManager() throws Exception {
        Path output = scratch.resolve("fixture.out");
        app = startFixture(output);
        String pid = Long.toString(app.pid());
        awaitOutput(output, "fixture-started");

        for (String[] command : new String[][] {{"status"}, {"list", "loggers"},
                {"set", "logger", "com.acme", "DEBUG"}}) {
            List<String> withPid = new ArrayList<>(List.of(command));
            withPid.add("--pid");
            withPid.add(pid);

            Result refused = logctl(withPid.toArray(new String[0]));

            assertEquals(3, refused.exitCode(), refused.stdout() + refused.stderr());
            assertTrue(refused.stderr().contains("is not active yet: it is waiting"), refused.stderr());
        }
        Result attached = logctl("attach", pid);
        assertEquals(0, attached.exitCode(), attached.stderr());
        assertTrue(attached.stdout().contains("is waiting for the application"), attached.stdout());

        sendLine("go");
        awaitOutput(output, "active (JVM)");
        String all = Files.readString(output);
        assertTrue(all.contains("MANAGER LateManagerFixture$Manager"), "the application kept its manager:\n" + all);

        Result status = logctl("status", "--pid", pid);
        assertEquals(0, status.exitCode(), status.stderr());
    }

    private Process startFixture(Path output) throws Exception {
        String agentJar = required("logaperture.agent.jar");
        String classPath = String.join(File.pathSeparator, jarOf(org.slf4j.Logger.class),
                jarOf(ch.qos.logback.classic.Logger.class), jarOf(ch.qos.logback.core.Appender.class));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        Path source = Files.writeString(scratch.resolve("LateManagerFixture.java"), FIXTURE);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        assertEquals(0, compiler.run(null, null, null, "-cp", classPath, "-d", classes.toString(), source.toString()));
        return new ProcessBuilder(javaExecutable(), "-javaagent:" + agentJar + "=--wait-for-log-manager=on",
                "-Dlogaperture.home=" + scratch.resolve("target-home"),
                "-cp", classes + File.pathSeparator + classPath, "LateManagerFixture")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private void sendLine(String line) throws IOException {
        OutputStream stdin = app.getOutputStream();
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private Result logctl(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExecutable(),
                "-Dlogaperture.home=" + scratch.resolve("cli-home"), "-jar", required("logaperture.cli.jar")));
        command.addAll(List.of(args));
        Path out = Files.createTempFile(scratch, "logctl", ".out");
        Path err = Files.createTempFile(scratch, "logctl", ".err");
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile())
                .start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("logctl " + String.join(" ", args) + " did not finish in 90 s");
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static void awaitOutput(Path output, String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String content = "";
        while (System.nanoTime() < deadline) {
            content = Files.exists(output) ? Files.readString(output) : "";
            if (content.contains(text)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("'" + text + "' never appeared in the fixture's output:\n" + content);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, property + " must be set (reactor build order provides it)");
        assertTrue(Files.isRegularFile(Path.of(value)), "not built: " + value + " -- run `mvn package`");
        return value;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
