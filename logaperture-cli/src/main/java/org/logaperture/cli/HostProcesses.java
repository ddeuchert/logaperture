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
package org.logaperture.cli;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

/**
 * What {@code logctl} can learn about the machine's processes beyond what the JDK shows it.
 * The JDK's own views stop at the current account: {@code VirtualMachine.list()} reads only this
 * user's {@code hsperfdata} directory, and {@code ProcessHandle.of(pid)} is empty for a process the
 * caller may not open, even when the process is running and attachable. On Windows an elevated
 * Administrator can attach to a service running as {@code LocalSystem} but neither view shows it
 * (doc/specs/service-account-access.md, findings 3 to 5). This class supplies the missing sources,
 * Windows only; on any other platform every method answers as if the JDK's view were complete.
 */
final class HostProcesses {

    /** Runs a command and returns its standard output, or {@code null} if it could not run. */
    interface Runner {
        String run(String... command);
    }

    private final boolean windows;
    private final LongPredicate handleVisible;
    private final Runner runner;
    private final Path systemRoot;
    private final long currentPid;

    HostProcesses(boolean windows, LongPredicate handleVisible, Runner runner, Path systemRoot, long currentPid) {
        this.windows = windows;
        this.handleVisible = handleVisible;
        this.runner = runner;
        this.systemRoot = systemRoot;
        this.currentPid = currentPid;
    }

    static HostProcesses system() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        String root = System.getenv("SystemRoot");
        return new HostProcesses(windows, pid -> ProcessHandle.of(pid).isPresent(), HostProcesses::runCommand,
                Paths.get(root != null ? root : "C:\\Windows"), ProcessHandle.current().pid());
    }

    boolean windows() {
        return windows;
    }

    long currentPid() {
        return currentPid;
    }

    /**
     * Whether a process with this PID may be running. {@code ProcessHandle.of} is trusted when it
     * finds one; on Windows an empty answer is checked against {@code tasklist}, which lists every
     * process whoever owns it. When {@code tasklist} cannot run the answer is unknown and this says
     * {@code true}: the attach then reports its own outcome, instead of a false "no such process".
     */
    boolean exists(long pid) {
        if (handleVisible.test(pid)) {
            return true;
        }
        if (!windows) {
            return false;
        }
        String out = runner.run(tasklist(), "/fo", "csv", "/nh", "/fi", "pid eq " + pid);
        return out == null || parseTasklist(out).contains(pid);
    }

    /** {@code tasklist.exe} by its full path, never by name: a bare name can resolve in the working directory. */
    private String tasklist() {
        return systemRoot.resolve("System32").resolve("tasklist.exe").toString();
    }

    /**
     * PIDs of JVMs {@code VirtualMachine.list()} may not show: Windows only, the {@code java.exe} and
     * {@code javaw.exe} rows of one {@code tasklist}, plus the pid files in the system profiles'
     * {@code hsperfdata_*} directories that name a process {@code tasklist} lists (a crashed JVM
     * leaves its file behind, and the PID may since belong to something else or to nothing). A
     * file's PID can still have been reused by an unrelated process; that is the one residual risk.
     * Only PIDs from these sources are probed. Sorted, without this process.
     */
    TreeSet<Long> otherAccountJvmPids() {
        TreeSet<Long> pids = new TreeSet<>();
        if (!windows) {
            return pids;
        }
        String out = runner.run(tasklist(), "/fo", "csv", "/nh");
        TreeSet<Long> running = new TreeSet<>();
        if (out != null) {
            for (TasklistRow row : parseTasklistRows(out)) {
                running.add(row.pid());
                String image = row.image().toLowerCase();
                if (image.equals("java.exe") || image.equals("javaw.exe")) {
                    pids.add(row.pid());
                }
            }
        }
        for (long pid : hsperfdataPids(systemRoot)) {
            if (out == null || running.contains(pid)) {
                pids.add(pid);
            }
        }
        pids.remove(currentPid);
        return pids;
    }

    /** One process row of {@code tasklist /fo csv /nh}. */
    record TasklistRow(String image, long pid) {
    }

    /**
     * The rows of {@code tasklist /fo csv /nh} output: {@code "java.exe","7100","Services","0","7,656,664 K"}.
     * A line without a quoted numeric second field (the "no tasks" notice, in any language) is skipped.
     */
    static List<TasklistRow> parseTasklistRows(String output) {
        List<TasklistRow> rows = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("\"")) {
                continue;
            }
            String[] cells = trimmed.substring(1).split("\",\\s*\"", -1);
            if (cells.length < 2) {
                continue;
            }
            try {
                rows.add(new TasklistRow(cells[0].replace("\"", ""), Long.parseLong(cells[1].replace("\"", "").trim())));
            } catch (NumberFormatException notAPid) {
                // a header or a notice, not a process row
            }
        }
        return rows;
    }

    /** The PIDs of {@link #parseTasklistRows}. */
    static List<Long> parseTasklist(String output) {
        List<Long> pids = new ArrayList<>();
        for (TasklistRow row : parseTasklistRows(output)) {
            pids.add(row.pid());
        }
        return pids;
    }

    /**
     * Pid files in {@code hsperfdata_*} directories under the places Windows keeps the temporary
     * directory of a service account: {@code <root>\System32\config\systemprofile\AppData\Local\Temp}
     * (where {@code LocalSystem} writes), {@code <root>\ServiceProfiles\*\AppData\Local\Temp}
     * (the {@code LocalService} and {@code NetworkService} accounts) and {@code <root>\Temp}.
     * A directory that cannot be read is skipped.
     */
    static TreeSet<Long> hsperfdataPids(Path systemRoot) {
        List<Path> temps = new ArrayList<>();
        temps.add(systemRoot.resolve("System32").resolve("config").resolve("systemprofile")
                .resolve("AppData").resolve("Local").resolve("Temp"));
        temps.add(systemRoot.resolve("Temp"));
        Path profiles = systemRoot.resolve("ServiceProfiles");
        if (Files.isDirectory(profiles)) {
            try (DirectoryStream<Path> accounts = Files.newDirectoryStream(profiles)) {
                for (Path account : accounts) {
                    temps.add(account.resolve("AppData").resolve("Local").resolve("Temp"));
                }
            } catch (IOException | RuntimeException unreadable) {
                // skipped: the other sources still apply
            }
        }
        TreeSet<Long> pids = new TreeSet<>();
        for (Path temp : temps) {
            if (!Files.isDirectory(temp)) {
                continue;
            }
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(temp, "hsperfdata_*")) {
                for (Path dir : dirs) {
                    addPidFiles(dir, pids);
                }
            } catch (IOException | RuntimeException unreadable) {
                // skipped
            }
        }
        return pids;
    }

    private static void addPidFiles(Path dir, TreeSet<Long> pids) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                try {
                    pids.add(Long.parseLong(file.getFileName().toString()));
                } catch (NumberFormatException notAPidFile) {
                    // not a pid file
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // skipped
        }
    }

    /**
     * Runs a command with its output in a temporary file, so the timeout applies while the command
     * runs: reading a pipe to its end would block for as long as the command does.
     */
    private static String runCommand(String... command) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("logctl-", ".out");
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            return new String(Files.readAllBytes(output), Charset.defaultCharset());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // a leftover temporary file
                }
            }
        }
    }

    private static final long COMMAND_TIMEOUT_SECONDS = 10;
}
