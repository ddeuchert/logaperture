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

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * "No PID argument when exactly one candidate JVM is running. Discover it."
 * (doc/logaperture-spec.md §14.5). A candidate is a JVM this OS user can
 * attach to whose system properties carry the {@code logaperture.version}
 * marker the agent sets once it has installed
 * (doc/specs/cli-transport.md "Discovery").
 */
final class Discovery {

    static final String MARKER_PROPERTY = "logaperture.version";

    /** doc/specs/pick-jvm.md: the numbered question's rows are shortened to fit this width. */
    static final int QUESTION_WIDTH = 120;

    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private Discovery() {
    }

    static long resolveTargetPid(Long explicitPid) {
        return resolveTargetPid(explicitPid, null);
    }

    /**
     * @param jvmQuestion asks which JVM when several are candidates (doc/specs/pick-jvm.md), or
     *                    {@code null} when nothing may be asked -- several is then exit 4, as before
     */
    static long resolveTargetPid(Long explicitPid, Prompter jvmQuestion) {
        if (explicitPid != null) {
            if (ProcessHandle.of(explicitPid).isEmpty()) {
                throw new CliError(CliError.NO_JVM, "No process with PID " + explicitPid + ".");
            }
            return explicitPid;
        }

        List<Candidate> candidates = new ArrayList<>();
        int uninspectable = 0;
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            Probe probe = probe(descriptor);
            if (probe.candidate() != null) {
                candidates.add(probe.candidate());
            } else if (probe.failed()) {
                uninspectable++;
            }
        }

        if (candidates.isEmpty()) {
            String message =
                    "No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.";
            if (uninspectable > 0) {
                message += "\n(" + uninspectable + " running JVM(s) could not be inspected — if one of them "
                        + "is yours, name it with --pid <n>.)";
            }
            throw new CliError(CliError.NO_JVM, message);
        }
        return choose(candidates, jvmQuestion);
    }

    /**
     * One of one or more candidates, in PID order: the only one, the one the operator picks when
     * {@code jvmQuestion} may ask, or else exit 4 with the table (doc/specs/pick-jvm.md J1-J6).
     */
    static long choose(List<Candidate> candidates, Prompter jvmQuestion) {
        if (candidates.size() == 1) {
            return candidates.get(0).pid();
        }
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(java.util.Comparator.comparingLong(Candidate::pid));
        if (jvmQuestion == null) {
            throw new CliError(CliError.AMBIGUOUS, ambiguityMessage(sorted));
        }
        PrintStream out = jvmQuestion.out();
        out.println(sorted.size() + " LogAperture JVMs are running:");
        out.println(numberedTable(sorted).indent(2).stripTrailing());
        while (true) {
            String answer;
            try {
                answer = jvmQuestion.ask("", "Which? (a number from the list, or a PID; Enter to cancel)");
            } catch (Prompter.Cancelled endOfInput) {
                answer = "";
            }
            if (answer.isEmpty()) {
                throw new CliError(CliError.AMBIGUOUS, "No JVM chosen.");
            }
            Candidate chosen = pick(sorted, answer);
            if (chosen == null) {
                out.println("'" + answer + "' isn't in the list -- answer 1 to " + sorted.size()
                        + ", or one of the PIDs shown.");
                continue;
            }
            out.println("Using PID " + chosen.pid() + " — pass --pid " + chosen.pid() + " to skip this question.");
            out.println();
            return chosen.pid();
        }
    }

    /** J4: a list number, or one of the listed PIDs; {@code null} for anything else. */
    private static Candidate pick(List<Candidate> sorted, String answer) {
        long n;
        try {
            n = Long.parseLong(answer.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (n >= 1 && n <= sorted.size()) {
            return sorted.get((int) n - 1);
        }
        for (Candidate candidate : sorted) {
            if (candidate.pid() == n) {
                return candidate;
            }
        }
        return null;
    }

    private static Probe probe(VirtualMachineDescriptor descriptor) {
        long pid;
        try {
            pid = Long.parseLong(descriptor.id());
        } catch (NumberFormatException notAPid) {
            return Probe.NOT_APPLICABLE;
        }
        if (pid == ProcessHandle.current().pid()) {
            // The CLI's own JVM: attaching to self fails on most platforms. Not a candidate,
            // and not an "un-attachable" one worth counting either.
            return Probe.NOT_APPLICABLE;
        }
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(descriptor);
            Properties properties = vm.getSystemProperties();
            String version = properties.getProperty(MARKER_PROPERTY);
            if (version == null) {
                return Probe.NOT_APPLICABLE;
            }
            Instant startedAt = ProcessHandle.of(pid).flatMap(process -> process.info().startInstant()).orElse(null);
            return Probe.of(new Candidate(pid, descriptor.displayName(), version, startedAt,
                    properties.getProperty("user.dir")));
        } catch (Exception cannotInspect) {
            // A JVM we can't attach to (a race, a different user, this same VM) can't be a candidate.
            // Still count it: if discovery then finds nothing, the message should own up to the JVMs
            // it couldn't see rather than flatly claim none is enabled (doc/specs/cli-transport.md
            // "Discovery" — "skipped silently" for the resolve step, but the zero-candidate report
            // is more honest with the count).
            return Probe.FAILED;
        } finally {
            Quietly.detach(vm);
        }
    }

    private static String ambiguityMessage(List<Candidate> candidates) {
        List<List<String>> rows = new ArrayList<>();
        for (Candidate candidate : candidates) {
            rows.add(List.of(Long.toString(candidate.pid()), candidate.version(), started(candidate),
                    directory(candidate), candidate.displayName()));
        }
        return Format.table(List.of("PID", "VERSION", "STARTED", "DIRECTORY", "COMMAND"), rows)
                + "\nSeveral candidates — pass --pid <n>.";
    }

    /** The question's table: numbered, and COMMAND shortened so an indented row fits {@link #QUESTION_WIDTH}. */
    private static String numberedTable(List<Candidate> sorted) {
        List<String> headers = List.of("#", "PID", "VERSION", "STARTED", "DIRECTORY", "COMMAND");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Candidate candidate = sorted.get(i);
            rows.add(new ArrayList<>(List.of(Integer.toString(i + 1), Long.toString(candidate.pid()),
                    candidate.version(), started(candidate), directory(candidate), candidate.displayName())));
        }
        int before = 0;
        for (int c = 0; c < headers.size() - 1; c++) {
            int width = headers.get(c).length();
            for (List<String> row : rows) {
                width = Math.max(width, row.get(c).length());
            }
            before += width + 2;
        }
        int commandWidth = Math.max("COMMAND".length(), QUESTION_WIDTH - 2 - before);
        for (List<String> row : rows) {
            String command = row.get(headers.size() - 1);
            if (command.length() > commandWidth) {
                row.set(headers.size() - 1, command.substring(0, commandWidth - 1) + "…");
            }
        }
        return Format.table(headers, rows);
    }

    private static String started(Candidate candidate) {
        return candidate.startedAt() == null ? "?" : STARTED.format(candidate.startedAt());
    }

    /** {@code user.dir}, with this user's home shortened to {@code ~}; {@code ?} if the JVM didn't say. */
    private static String directory(Candidate candidate) {
        String dir = candidate.directory();
        if (dir == null) {
            return "?";
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty() && (dir.equals(home) || dir.startsWith(home + "/"))) {
            return "~" + dir.substring(home.length());
        }
        return dir;
    }

    /**
     * @param startedAt the process start time, {@code null} if the OS didn't say
     * @param directory the JVM's {@code user.dir}, {@code null} if unset
     */
    record Candidate(long pid, String displayName, String version, Instant startedAt, String directory) {
    }

    /** One descriptor's outcome: our agent (a {@link Candidate}), not ours, or un-attachable. */
    private record Probe(Candidate candidate, boolean failed) {
        static final Probe NOT_APPLICABLE = new Probe(null, false);
        static final Probe FAILED = new Probe(null, true);

        static Probe of(Candidate candidate) {
            return new Probe(candidate, false);
        }
    }
}
