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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * doc/specs/quarkus-support.md "Acceptance": a real Quarkus application in JVM mode
 * ({@code java -jar quarkus-run.jar}) with the agent attached, driven by the real {@code logctl}.
 * Runs only under the {@code quarkus} profile, which builds the sample application and passes its
 * {@code quarkus-run.jar} in {@code logaperture.quarkus.run.jar}; otherwise every test skips.
 * Needs no Docker. {@code mvn -Pquarkus verify [-Dquarkus.version=3.33.4]}.
 *
 * <p>Not covered here: the startup fast-sweep window with the activation trigger disabled (there is
 * no switch to disable it, and the spec plans no new property); it is covered by
 * {@code JBossLogManagerContainerTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusIT {

    private static final String WORKER = "org.logaperture.sample.quarkus.Worker";

    private final HttpClient http = HttpClient.newHttpClient();

    private Path runJar;
    private Path agentJar;
    private Path cliJar;
    @TempDir
    Path work;

    private final List<App> started = new ArrayList<>();

    @BeforeAll
    void locate() {
        String run = System.getProperty("logaperture.quarkus.run.jar", "");
        assumeTrue(!run.isBlank(), "the quarkus profile is not active");
        runJar = Path.of(run);
        assumeTrue(Files.exists(runJar), runJar + " has not been built");
        agentJar = Path.of(System.getProperty("logaperture.agent.jar"));
        cliJar = Path.of(System.getProperty("logaperture.cli.jar"));
    }

    @AfterEach
    void stopAll() {
        for (App app : started) {
            app.stop();
        }
        started.clear();
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void bootIsIntact_andTheAgentLeavesTheMinLevelFloorAlone() throws Exception {
        App app = start("boot");

        String answer = app.get("/log?tag=boot");

        assertTrue(answer.contains("manager=org.jboss.logmanager.LogManager"), answer);
        // the build-time floor (DEBUG = 500) is exactly what Quarkus set: the agent did not get to
        // initialise JUL ahead of Quarkus (spec Q1)
        assertTrue(answer.contains("floor=500"), answer);
        assertTrue(answer.contains("trace=false"), answer);
        assertTrue(app.output().contains("Listening on"), "Quarkus started normally:\n" + app.output());
        assertFalse(app.output().contains("[logaperture] ERROR"), app.output());
    }

    @Test
    void debugWorks_andTraceWarnsInsteadOfDoingNothing() throws Exception {
        App app = start("levels");
        assertTrue(app.get("/log?tag=a").contains("debug=false"));

        Logctl debug = logctl(app, "set", "logger", WORKER, "DEBUG", "for", "5m");
        assertEquals(0, debug.exitCode(), debug.stderr());
        assertFalse(debug.stdout().contains("WARN:"), debug.stdout());
        String withDebug = app.get("/log?tag=debugOn");
        assertTrue(withDebug.contains("debug=true"), withDebug);
        assertTrue(pollUntil(() -> app.logFile().contains("DEBUG-line debugOn")), "DEBUG reached the file");

        Logctl trace = logctl(app, "set", "logger", WORKER, "TRACE", "for", "5m");
        assertEquals(0, trace.exitCode(), trace.stderr());
        assertTrue(trace.stdout().contains("WARN: " + WORKER + " is set to TRACE but the logging backend only emits"
                + " DEBUG and above"), trace.stdout());
        assertTrue(trace.stdout().contains("quarkus.log.min-level=DEBUG"), trace.stdout());
        assertTrue(app.get("/log?tag=traceOn").contains("trace=false"), "the floor held");

        String listed = logctl(app, "list", "loggers", WORKER, "--show-all").stdout();
        assertTrue(listed.lines().anyMatch(l -> l.contains(WORKER) && l.contains("DEBUG") && !l.contains("TRACE")),
                "list loggers shows what is emitted, not what was asked for:\n" + listed);

        String doctor = logctl(app, "doctor").stdout();
        assertTrue(doctor.contains(WORKER) && doctor.contains("only emits DEBUG"), doctor);
    }

    @Test
    void handlersAreCataloguedByNameOrType_andTopCountsTheMainLogFile() throws Exception {
        App app = start("handlers");
        for (int i = 0; i < 5; i++) {
            app.get("/log?tag=top" + i);
        }

        String handlers = logctl(app, "list", "handlers", "--show-all").stdout();
        assertTrue(handlers.contains("console"), handlers);
        assertTrue(handlers.contains("file"), handlers);
        assertFalse(handlers.contains("QuarkusDelayedHandler"), handlers);
        // doc/specs/quarkus-handler-names.md: the name written in application.properties, on the
        // handler that writes the audit file, and a change by that name reaches it.
        assertTrue(handlers.contains("AUDIT") && handlers.contains("audit.log"), handlers);
        Logctl raised = logctl(app, "set", "handler", "AUDIT", "DEBUG");
        assertEquals(0, raised.exitCode(), raised.stderr());

        assertTrue(pollUntil(() -> logctl(app, "top").stdout().contains(WORKER)),
                "top counted the sample's logger in the main log file");
    }

    @Test
    void rootHandlerNamesStayOnTheirFiles_whenAHandlerIsAddedAfterStartUp() throws Exception {
        App app = start("root-names");
        String before = logctl(app, "list", "handlers", "--show-all").stdout();
        assertRow(before, "ROOTA", "roota.log");
        assertRow(before, "ROOTB", "rootb.log");

        // doc/specs/quarkus-handler-names.md, amendment for #294: a handler added to the root after
        // start-up goes after the named ones; matched by position alone, it would shift both names.
        Path late = work.resolve("logs").resolve("late.log");
        assertEquals("added", app.get("/handlers/add-root-file?path=" + java.net.URLEncoder.encode(late.toString(), StandardCharsets.UTF_8)));
        assertTrue(pollUntil(() -> logctl(app, "list", "handlers", "--show-all").stdout().contains("late.log")),
                "LogAperture saw the added handler");

        String after = logctl(app, "list", "handlers", "--show-all").stdout();
        assertRow(after, "ROOTA", "roota.log");
        assertRow(after, "ROOTB", "rootb.log");
        assertFalse(logctl(app, "doctor").stdout().contains("quarkus.handler-names"),
                "every list was matched, by path");
        Logctl raised = logctl(app, "set", "handler", "ROOTB", "DEBUG");
        assertEquals(0, raised.exitCode(), raised.stderr());
        assertRow(logctl(app, "list", "handlers", "--show-all").stdout(), "ROOTB", "DEBUG");
    }

    /** The {@code list handlers} row for {@code name} contains {@code expected}. */
    private static void assertRow(String table, String name, String expected) {
        String row = table.lines().filter(line -> line.startsWith(name + " ")).findFirst().orElse("");
        assertTrue(row.contains(expected), name + " row should contain " + expected + ":\n" + table);
    }

    @Test
    void stickyOverride_survivesARestart() throws Exception {
        App first = start("sticky");
        assertEquals(0, logctl(first, "set", "logger", WORKER, "DEBUG", "sticky").exitCode());
        assertTrue(first.get("/log?tag=one").contains("debug=true"));
        first.stop();

        App second = start("sticky"); // the same state directory
        assertTrue(pollUntil(() -> second.get("/log?tag=two").contains("debug=true")),
                "the sticky override was resumed after the restart");
    }

    @Test
    void stickyDropRule_isEffectiveSoonAfterStart() throws Exception {
        App first = start("drop");
        Logctl added = logctl(first, "add", "rule", "drop", WORKER, "--message-contains", "REPEAT-line",
                "--no-sample-full", "sticky");
        assertEquals(0, added.exitCode(), added.stderr());
        first.stop();

        long startedAt = System.nanoTime();
        App second = start("drop");
        // Quarkus's own start-up logging setup replaces our filter; the activation trigger and the
        // startup sweep window put it back. Poll for a request whose repeated lines are all dropped.
        boolean effective = pollUntil(Duration.ofSeconds(30), () -> {
            String tag = "r" + System.nanoTime();
            second.get("/log?tag=" + tag + "&repeat=5");
            sleep(300);
            return second.output().contains("INFO-line " + tag) && !second.output().contains("REPEAT-line " + tag);
        });
        assertTrue(effective, "the sticky drop rule took effect after the restart:\n" + second.output());
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
        assertTrue(seconds < 30, "took " + seconds + "s");
    }

    // --- the application under test ----------------------------------------------------------------

    private App start(String stateName) throws Exception {
        App app = new App(stateName);
        started.add(app);
        app.start();
        return app;
    }

    private final class App {
        private final Path home;
        private final Path logFile;
        private final Path outputFile;
        private final int port;
        private Process process;

        App(String stateName) throws IOException {
            this.home = Files.createDirectories(work.resolve("state-" + stateName));
            Files.createDirectories(work.resolve("logs"));
            this.logFile = work.resolve("logs").resolve(stateName + ".log");
            this.outputFile = work.resolve("logs").resolve(stateName + ".out");
            this.port = freePort();
        }

        void start() throws Exception {
            Files.deleteIfExists(logFile);
            List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-javaagent:" + agentJar,
                    "-Dlogaperture.home=" + home,
                    "-Dlogaperture.instanceId=quarkus-it",
                    "-Dquarkus.http.port=" + port,
                    "-Dquarkus.http.host=127.0.0.1",
                    "-Dquarkus.log.file.enabled=true",
                    "-Dquarkus.log.file.path=" + logFile,
                    "-Dquarkus.log.handler.file.\"AUDIT\".enable=true",
                    "-Dquarkus.log.handler.file.\"AUDIT\".path=" + logFile.resolveSibling("audit.log"),
                    // doc/specs/quarkus-handler-names.md #14: SYS is configured but not enabled, so
                    // Quarkus does not build it, and AUDIT must still get its name.
                    "-Dquarkus.log.handler.syslog.\"SYS\".app-name=sample",
                    "-Dquarkus.log.category.\"org.logaperture.sample.quarkus\".handlers=SYS,AUDIT",
                    "-Dquarkus.log.handler.file.\"ROOTA\".enable=true",
                    "-Dquarkus.log.handler.file.\"ROOTA\".path=" + logFile.resolveSibling("roota.log"),
                    "-Dquarkus.log.handler.file.\"ROOTB\".enable=true",
                    "-Dquarkus.log.handler.file.\"ROOTB\".path=" + logFile.resolveSibling("rootb.log"),
                    "-Dquarkus.log.handlers=ROOTA,ROOTB",
                    "-jar", runJar.toString());
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile.toFile()))
                    .start();
            assertTrue(pollUntil(Duration.ofSeconds(90), () -> process.isAlive() && ready()),
                    "the application did not become ready:\n" + output());
            assertTrue(pollUntil(Duration.ofSeconds(60), () -> logctl(this, "status").exitCode() == 0),
                    "logctl could not find the agent:\n" + output());
        }

        private boolean ready() {
            try {
                return get("/log?tag=ready").contains("trace=");
            } catch (RuntimeException notYet) {
                return false;
            }
        }

        String get(String path) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return response.body();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        long pid() {
            return process.pid();
        }

        String output() {
            return read(outputFile);
        }

        String logFile() {
            return read(logFile);
        }

        void stop() {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // --- logctl and small helpers ------------------------------------------------------------------

    private record Logctl(int exitCode, String stdout, String stderr) {
    }

    private Logctl logctl(App app, String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", cliJar.toString(), "--pid", Long.toString(app.pid())));
        command.addAll(List.of(args));
        try {
            Path out = Files.createTempFile(work, "logctl", ".out");
            Path err = Files.createTempFile(work, "logctl", ".err");
            Process p = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return new Logctl(p.isAlive() ? -1 : p.exitValue(), read(out), read(err));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean pollUntil(BooleanSupplier condition) {
        return pollUntil(Duration.ofSeconds(20), condition);
    }

    private static boolean pollUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException notYet) {
                // keep polling
            }
            sleep(250);
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
