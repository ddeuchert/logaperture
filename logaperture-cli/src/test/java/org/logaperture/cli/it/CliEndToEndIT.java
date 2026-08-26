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
package org.logaperture.cli.it;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.cli.Main;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The exit criterion from doc/specs/cli-transport.md, run for real:
 * {@code logctl} discovers a JVM with no PID argument, drives {@code
 * levels}/{@code debug}/{@code status}/{@code reset} over the attach +
 * local-JMX transport, and reports the documented exit codes for the
 * zero-candidate and several-candidate cases.
 */
class CliEndToEndIT {

    private static final String LOGGER = "com.acme.batch.Worker";

    private final List<Process> fixtures = new ArrayList<>();

    /**
     * Discovery counts every attachable JVM this user owns that carries the
     * {@code logaperture.version} marker — which the agent module's own
     * {@code LevelControlEndToEndIT} fixture also sets. In the default
     * serial reactor that IT has finished (and torn its fixtures down)
     * before this module builds, so the only marked JVMs here are the ones
     * we launch. If that's not true — a crashed leftover, or a {@code -T}
     * parallel build — fail with a clear message rather than a mystifying
     * "expected 0 but was 4".
     */
    @BeforeEach
    void noStrayMarkedJvms() {
        List<Long> stray = markedJvmPids();
        assertTrue(stray.isEmpty(),
                "a LogAperture-marked JVM is already running (pids " + stray + ") before this IT launched any — "
                        + "a leftover fixture from a crashed run, or a parallel (-T) build. Kill it and re-run.");
    }

    @AfterEach
    void stopFixtures() {
        for (Process fixture : fixtures) {
            stop(fixture);
        }
    }

    @Test
    void discoversTheSoleJvmAndDrivesTheFullLoop() throws Exception {
        Process fixture = launchFixture();
        awaitReady(fixture);

        Result levels = run("levels", "com.acme");
        assertEquals(0, levels.exitCode, levels.err);
        assertTrue(levels.out.contains(LOGGER), levels.out);
        assertTrue(levels.out.contains("INFO"), levels.out);

        Result debug = run("debug", LOGGER, "for", "1m", "--reason", "cli-e2e");
        assertEquals(0, debug.exitCode, debug.err);
        assertTrue(debug.out.contains(LOGGER + " → DEBUG"), debug.out);
        assertTrue(debug.out.contains("FOR, reverts"), debug.out);

        Result status = run("status");
        assertEquals(0, status.exitCode, status.err);
        assertTrue(status.out.contains(LOGGER), status.out);
        assertTrue(status.out.contains("DEBUG"), status.out);
        assertTrue(status.out.contains("FOR"), status.out);
        assertTrue(status.out.contains("cli-e2e"), status.out);

        Result reset = run("reset", LOGGER);
        assertEquals(0, reset.exitCode, reset.err);
        assertTrue(reset.out.contains(LOGGER + " → INFO (baseline)"), reset.out);

        Result statusAfter = run("status");
        assertEquals(0, statusAfter.exitCode, statusAfter.err);
        assertEquals("No active overrides.", statusAfter.out.strip());

        Result resetAll = run("reset", "--all");
        assertEquals(0, resetAll.exitCode, resetAll.err);
        assertTrue(resetAll.out.contains("Reverted 0 override(s)."), resetAll.out);
    }

    @Test
    void jsonOutputIsMachineReadable() throws Exception {
        Process fixture = launchFixture();
        awaitReady(fixture);

        Result levels = run("levels", "--json");
        assertEquals(0, levels.exitCode, levels.err);
        assertTrue(levels.out.strip().startsWith("[{"), levels.out);
        assertTrue(levels.out.contains("\"effectiveLevel\":\"INFO\""), levels.out);
    }

    @Test
    void twoCandidatesWithoutPidIsExitFourAndPidDisambiguates() throws Exception {
        Process first = launchFixture();
        Process second = launchFixture();
        awaitReady(first);
        awaitReady(second);

        Result ambiguous = run("levels");
        assertEquals(4, ambiguous.exitCode, ambiguous.out + ambiguous.err);
        assertTrue(ambiguous.err.contains(Long.toString(first.pid())), ambiguous.err);
        assertTrue(ambiguous.err.contains(Long.toString(second.pid())), ambiguous.err);

        Result targeted = run("--pid", Long.toString(first.pid()), "levels", "com.acme");
        assertEquals(0, targeted.exitCode, targeted.err);
        assertTrue(targeted.out.contains(LOGGER), targeted.out);
    }

    @Test
    void noProcessAtThatPidIsExitThree() {
        Result result = run("--pid", Long.toString(unusedPid()), "status");
        assertEquals(3, result.exitCode, result.out + result.err);
    }

    @Test
    void explicitPidOfAnAgentlessJvmIsExitThree() throws Exception {
        Process plain = launchPlainJvm();
        try {
            awaitPlainReady(plain);
            Result result = run("--pid", Long.toString(plain.pid()), "status");
            assertEquals(3, result.exitCode, result.out + result.err);
            assertTrue(result.err.contains("no LogAperture agent"), result.err);
        } finally {
            stop(plain);
        }
    }

    // --- harness ---------------------------------------------------------

    private Process launchFixture() throws Exception {
        return launch("org.logaperture.cli.it.CliFixtureApp");
    }

    /** A JVM with no agent and no marker — reachable by --pid, but not a LogAperture control target. */
    private Process launchPlainJvm() throws Exception {
        return launch("org.logaperture.cli.it.PlainApp");
    }

    private Process launch(String mainClass) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-cp", System.getProperty("java.class.path"),
                mainClass);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        fixtures.add(process);
        return process;
    }

    private static void awaitReady(Process fixture) throws Exception {
        awaitLine(fixture, "FIXTURE-READY");
    }

    private static void awaitPlainReady(Process plain) throws Exception {
        awaitLine(plain, "PLAIN-READY");
    }

    private static void awaitLine(Process process, String token) throws Exception {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("process exited before printing '" + token + "'");
            }
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && line.contains(token)) {
                    return;
                }
            } else {
                Thread.sleep(50);
            }
        }
        fail("process never printed '" + token + "' within 15s");
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Attachable JVMs owned by this user that already carry the discovery marker — same probe {@code Discovery} uses. */
    private static List<Long> markedJvmPids() {
        List<Long> pids = new ArrayList<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            VirtualMachine vm = null;
            try {
                vm = VirtualMachine.attach(descriptor);
                if (vm.getSystemProperties().getProperty("logaperture.version") != null) {
                    pids.add(Long.parseLong(descriptor.id()));
                }
            } catch (Exception cannotInspect) {
                // not attachable / not a pid → can't be one of ours
            } finally {
                if (vm != null) {
                    try {
                        vm.detach();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }
        }
        return pids;
    }

    /** A PID that is (almost certainly) not a live process, for the no-JVM path. */
    private static long unusedPid() {
        for (long candidate = 999_999; candidate > 0; candidate--) {
            if (ProcessHandle.of(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused pid");
    }

    private static void stop(Process process) {
        if (!process.isAlive()) {
            return;
        }
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write('\n');
            stdin.flush();
        } catch (Exception ignored) {
            // destroyForcibly below is the backstop
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record Result(int exitCode, String out, String err) {
    }
}
