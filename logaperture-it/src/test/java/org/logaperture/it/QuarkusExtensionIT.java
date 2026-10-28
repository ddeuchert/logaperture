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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * doc/specs/quarkus-extension.md "Acceptance": the sample application built with the LogAperture
 * extension (logaperture-sample-quarkus-extension), run with {@code java -jar quarkus-run.jar} and no
 * {@code -javaagent}, driven by the real {@code logctl}. Runs with the {@code quarkus} profile, on the
 * tested minor by default and on the LTS with {@code -Dquarkus.version}; skips itself otherwise.
 * Dev mode is checked by hand (see the PR): it needs the extension from a Maven repository, not
 * this reactor.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusExtensionIT {

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
        String run = System.getProperty("logaperture.quarkus.extension.run.jar", "");
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
    void bootIsIntact_andEnvNamesTheExtension() throws Exception {
        App app = start(new App("boot"));

        assertTrue(app.output().contains("Listening on"), app.output());
        assertTrue(app.output().contains("active (Quarkus extension)"), "the startup line:\n" + app.output());
        assertFalse(app.output().contains("[logaperture] ERROR"), app.output());
        String env = logctl(app, "env").stdout();
        assertTrue(env.startsWith("LogAperture extension"), env);
        assertTrue(env.contains("Quarkus 3."), env);
        assertTrue(logctl(app, "env", "--json").stdout().contains("\"delivery\":\"extension\""));
    }

    @Test
    void theFloorIsTrace_soTraceWorksWithoutARebuild() throws Exception {
        App app = start(new App("floor"));
        assertTrue(app.get("/log?tag=a").contains("floor=400"), "#1: the extension's default lowered the floor");

        Logctl trace = logctl(app, "set", "logger", WORKER, "TRACE", "for", "5m");
        assertEquals(0, trace.exitCode(), trace.stderr());
        assertFalse(trace.stdout().contains("WARN:"), trace.stdout());
        assertTrue(app.get("/log?tag=traceOn").contains("trace=true"));
        assertTrue(pollUntil(() -> app.logFile().contains("TRACE-line traceOn")), "TRACE reached the file");
    }

    @Test
    void handlersTopAndDropRulesWork() throws Exception {
        App app = start(new App("handlers"));
        for (int i = 0; i < 5; i++) {
            app.get("/log?tag=top" + i);
        }

        String handlers = logctl(app, "list", "handlers", "--show-all").stdout();
        assertTrue(handlers.contains("console") && handlers.contains("file"), handlers);
        // doc/specs/quarkus-handler-names.md: the name written in application.properties, on the
        // handler that writes the audit file, and a change by that name reaches it.
        assertTrue(handlers.contains("AUDIT") && handlers.contains("audit.log"), handlers);
        Logctl raised = logctl(app, "set", "handler", "AUDIT", "DEBUG");
        assertEquals(0, raised.exitCode(), raised.stderr());
        assertTrue(pollUntil(() -> logctl(app, "top").stdout().contains(WORKER)), "top counted the log file");

        Logctl added = logctl(app, "add", "rule", "drop", WORKER, "--message-contains", "REPEAT-line",
                "--no-sample-full", "for", "5m");
        assertEquals(0, added.exitCode(), added.stderr());
        app.get("/log?tag=dropped&repeat=5");
        assertTrue(pollUntil(() -> app.output().contains("INFO-line dropped")));
        assertFalse(app.output().contains("REPEAT-line dropped"), "the drop rule took effect at once");
    }

    @Test
    void stickyOverride_isInForceFromTheStartOfTheNextRun() throws Exception {
        App first = start(new App("sticky"));
        assertEquals(0, logctl(first, "set", "logger", WORKER, "DEBUG", "sticky").exitCode());
        assertEquals(0, logctl(first, "set", "logger", "io.quarkus.vertx", "DEBUG", "sticky").exitCode());
        first.stop();

        App second = start(new App("sticky")); // the same state directory
        assertTrue(second.output().contains("2 sticky settings restored"), second.output());
        // No readiness gate and no re-sweep: in force before the first request, and early enough to
        // catch Vert.x's start-up DEBUG line (spike finding 3).
        assertTrue(second.output().contains("DEBUG [io.quarkus.vertx"), second.output());
        assertTrue(second.get("/log?tag=two").contains("debug=true"));
    }

    /**
     * doc/specs/bundled-vendor-defaults.md: the sample bundles a vendor defaults file setting
     * {@code org.logaperture.sample.quarkus.Bundled} to DEBUG. It applies and is named by its label;
     * an external file replaces it (#4); the switch turns it off (#5).
     */
    @Test
    void theBundledVendorDefaults_apply_canBeReplaced_andSwitchedOff() throws Exception {
        String bundledLogger = "org.logaperture.sample.quarkus.Bundled";
        App bundled = start(new App("bundled"));
        assertTrue(bundled.output().contains("vendor defaults bundled:META-INF/logaperture/vendor-defaults.yaml"),
                bundled.output());
        assertTrue(logctl(bundled, "env").stdout().contains("bundled:META-INF/logaperture/vendor-defaults.yaml"));
        String listed = logctl(bundled, "list", "loggers", bundledLogger, "--show-all").stdout();
        assertTrue(listed.lines().anyMatch(l -> l.startsWith(bundledLogger) && l.contains("DEBUG")), listed);
        bundled.stop();

        Path external = Files.writeString(work.resolve("external.yaml"),
                "schemaVersion: 1\nloggers:\n  - name: org.logaperture.sample.quarkus.External\n    level: WARN\n");
        App replaced = start(new App("replaced").env("QUARKUS_LOGAPERTURE_VENDOR_DEFAULTS", external.toString()));
        String env = logctl(replaced, "env").stdout();
        assertTrue(env.contains(external.toString()) && !env.contains("bundled:"), env);
        replaced.stop();

        App off = start(new App("off").env("QUARKUS_LOGAPERTURE_BUNDLED_VENDOR_DEFAULTS", "false"));
        assertTrue(logctl(off, "env").stdout().contains("not configured"));
    }

    @Test
    void theKillSwitch_startsNothing() throws Exception {
        App app = new App("off").env("QUARKUS_LOGAPERTURE_ENABLED", "false").expectLogctl(false);
        start(app);

        assertFalse(app.output().contains("[logaperture]"), app.output());
        assertTrue(logctl(app, "status").exitCode() != 0, "nothing for logctl to find");
    }

    @Test
    void withTheAgentToo_onlyTheAgentRuns() throws Exception {
        App app = start(new App("both").jvmArg("-javaagent:" + agentJar));

        assertTrue(app.output().contains("active (Quarkus)"), app.output());
        assertTrue(app.output().contains("the Quarkus extension starts nothing"), app.output());
        assertTrue(logctl(app, "env").stdout().startsWith("LogAperture agent"));
    }

    // --- the application under test ----------------------------------------------------------------

    private App start(App app) throws Exception {
        started.add(app);
        app.start();
        return app;
    }

    private final class App {
        private final Path home;
        private final Path logFile;
        private final Path outputFile;
        private final int port;
        private final List<String> jvmArgs = new ArrayList<>();
        private final Map<String, String> env = new HashMap<>();
        private boolean expectLogctl = true;
        private Process process;

        App(String stateName) throws IOException {
            this.home = Files.createDirectories(work.resolve("state-" + stateName));
            Files.createDirectories(work.resolve("logs"));
            this.logFile = work.resolve("logs").resolve(stateName + ".log");
            this.outputFile = work.resolve("logs").resolve(stateName + ".out");
            this.port = freePort();
        }

        App jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        App env(String name, String value) {
            env.put(name, value);
            return this;
        }

        App expectLogctl(boolean expect) {
            this.expectLogctl = expect;
            return this;
        }

        void start() throws Exception {
            Files.deleteIfExists(logFile);
            Files.deleteIfExists(outputFile);
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString()));
            command.addAll(jvmArgs);
            command.addAll(List.of(
                    // #8: the extension's settings come from Quarkus config; the environment here.
                    "-Dquarkus.http.port=" + port,
                    "-Dquarkus.http.host=127.0.0.1",
                    "-Dquarkus.log.file.enabled=true",
                    "-Dquarkus.log.file.path=" + logFile,
                    "-Dquarkus.log.handler.file.\"AUDIT\".enable=true",
                    "-Dquarkus.log.handler.file.\"AUDIT\".path=" + logFile.resolveSibling("audit.log"),
                    "-Dquarkus.log.category.\"org.logaperture.sample.quarkus\".handlers=AUDIT",
                    "-jar", runJar.toString()));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile.toFile()));
            builder.environment().put("QUARKUS_LOGAPERTURE_HOME", home.toString());
            builder.environment().put("QUARKUS_LOGAPERTURE_INSTANCE_ID", "quarkus-extension-it");
            builder.environment().putAll(env);
            process = builder.start();
            assertTrue(pollUntil(Duration.ofSeconds(90), () -> process.isAlive() && ready()),
                    "the application did not become ready:\n" + output());
            if (expectLogctl) {
                assertTrue(pollUntil(Duration.ofSeconds(60), () -> logctl(this, "status").exitCode() == 0),
                        "logctl could not find LogAperture:\n" + output());
            }
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
