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
     * Whether a process with this PID is running. {@code ProcessHandle.of} is trusted when it
     * finds one; on Windows an empty answer is checked against {@code tasklist}, which lists every
     * process whoever owns it.
     */
    boolean exists(long pid) {
        if (handleVisible.test(pid)) {
            return true;
        }
        if (!windows) {
            return false;
        }
        String out = runner.run("tasklist", "/fo", "csv", "/nh", "/fi", "pid eq " + pid);
        return out != null && parseTasklist(out).contains(pid);
    }

    /**
     * PIDs of JVMs {@code VirtualMachine.list()} may not show: Windows only, {@code java.exe} and
     * {@code javaw.exe} from {@code tasklist}, plus the pid files in the system profiles'
     * {@code hsperfdata_*} directories (which also finds a JVM hosted inside a service wrapper).
     * Only PIDs from these sources are ever probed: attaching on Windows starts a thread in the
     * target, so an arbitrary process is not a candidate. Sorted, without this process.
     */
    TreeSet<Long> otherAccountJvmPids() {
        TreeSet<Long> pids = new TreeSet<>();
        if (!windows) {
            return pids;
        }
        for (String image : List.of("java.exe", "javaw.exe")) {
            String out = runner.run("tasklist", "/fo", "csv", "/nh", "/fi", "imagename eq " + image);
            if (out != null) {
                pids.addAll(parseTasklist(out));
            }
        }
        pids.addAll(hsperfdataPids(systemRoot));
        pids.remove(currentPid);
        return pids;
    }

    /**
     * The PIDs in {@code tasklist /fo csv /nh} output: {@code "java.exe","7100","Services","0","7,656,664 K"}.
     * A line without a quoted numeric second field (the "no tasks" notice, in any language) is skipped.
     */
    static List<Long> parseTasklist(String output) {
        List<Long> pids = new ArrayList<>();
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
                pids.add(Long.parseLong(cells[1].replace("\"", "").trim()));
            } catch (NumberFormatException notAPid) {
                // a header or a notice, not a process row
            }
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

    private static String runCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
