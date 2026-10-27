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

import org.logaperture.control.jmx.EnvironmentReportData;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code logctl attach [<pid>]}: load the agent into a JVM that started without {@code -javaagent}
 * (doc/specs/logctl-attach.md). Runs without a control plane, since there is none until it has
 * finished.
 */
final class AttachCommand implements Standalone {

    /** doc/specs/logctl-attach.md #5: how long to wait for the agent to install. */
    static final Duration WAIT = Duration.ofSeconds(10);

    private final Long pid;
    private final String vendorDefaults;
    private final String stormDetection;
    private final String agentJar;
    private final boolean json;
    private final Duration wait;

    AttachCommand(Long pid, String vendorDefaults, String stormDetection, String agentJar, boolean json) {
        this(pid, vendorDefaults, stormDetection, agentJar, json, WAIT);
    }

    AttachCommand(Long pid, String vendorDefaults, String stormDetection, String agentJar, boolean json,
            Duration wait) {
        this.pid = pid;
        this.vendorDefaults = vendorDefaults;
        this.stormDetection = stormDetection;
        this.agentJar = agentJar;
        this.json = json;
        this.wait = wait;
    }

    @Override
    public int run(Long explicitPid, PrintStream out, PrintStream err, Prompter jvmQuestion, Connector connector) {
        JvmAttacher attacher = connector.attacher();
        String agentArguments = agentArguments(); // before anything is touched: a bad option fails alone
        Long named = pid != null ? pid : explicitPid;
        JvmAttacher.Target target = named != null ? attacher.inspect(named) : pick(attacher, jvmQuestion);

        if (target.hasLogAperture()) {
            report(out, Result.alreadyRunning(target));
            return CliError.OK;
        }

        AgentJar.Choice jar = AgentJar.choose(agentJar == null ? null : Path.of(agentJar));
        JvmAttacher.Loaded loaded = attacher.load(target.pid(), jar.path(), agentArguments, wait);

        String backend = null;
        if (loaded.version() != null) {
            backend = backendOf(connector, target.pid());
        }
        report(out, Result.loaded(target.pid(), loaded, backend, jar));
        return CliError.OK;
    }

    /**
     * The {@code -javaagent:...=<args>} string for the options given, or {@code null} for none. A
     * relative vendor-defaults path is made absolute against this process's directory: the target's
     * would be the wrong one to resolve it against.
     */
    String agentArguments() {
        List<String> options = new ArrayList<>();
        if (vendorDefaults != null) {
            Path file = Path.of(vendorDefaults).toAbsolutePath();
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new CliError(CliError.USAGE, "--vendor-defaults " + vendorDefaults + " is not a readable file.");
            }
            options.add("--vendor-defaults=" + file);
        }
        if (stormDetection != null) {
            options.add("--storm-detection=" + stormDetection);
        }
        return options.isEmpty() ? null : String.join(",", options);
    }

    /**
     * No PID given: the JVMs without LogAperture, to choose from. Even a single one is asked about, since
     * a machine's only other JVM may be an IDE or a build daemon. With nothing to ask on, exit 4 with the
     * table (doc/specs/logctl-attach.md #1).
     */
    private static JvmAttacher.Target pick(JvmAttacher attacher, Prompter jvmQuestion) {
        List<JvmAttacher.Target> candidates = new ArrayList<>();
        for (JvmAttacher.Target target : attacher.targets()) {
            if (!target.hasLogAperture()) {
                candidates.add(target);
            }
        }
        candidates.sort(Comparator.comparingLong(JvmAttacher.Target::pid));
        if (candidates.isEmpty()) {
            throw new CliError(CliError.NO_JVM, "No JVM to attach to: none is running as this user without "
                    + "LogAperture. Name a process with 'logctl attach <pid>'.");
        }
        if (jvmQuestion == null) {
            throw new CliError(CliError.AMBIGUOUS, table(candidates, false)
                    + "\nName the JVM: logctl attach <pid>.");
        }
        PrintStream out = jvmQuestion.out();
        out.println(candidates.size() == 1 ? "1 JVM can be attached to:" : candidates.size() + " JVMs can be attached to:");
        out.println(table(candidates, true).indent(2).stripTrailing());
        while (true) {
            String answer;
            try {
                answer = jvmQuestion.ask("", "Attach to which? (a number from the list, or a PID; Enter to cancel)");
            } catch (Prompter.Cancelled endOfInput) {
                answer = "";
            }
            if (answer.isEmpty()) {
                throw new CliError(CliError.AMBIGUOUS, "No JVM chosen.");
            }
            JvmAttacher.Target chosen = choice(candidates, answer);
            if (chosen == null) {
                out.println("'" + answer + "' isn't in the list -- answer 1 to " + candidates.size()
                        + ", or one of the PIDs shown.");
                continue;
            }
            out.println("Attaching to PID " + chosen.pid() + " — run 'logctl attach " + chosen.pid()
                    + "' to skip this question.");
            out.println();
            return chosen;
        }
    }

    private static JvmAttacher.Target choice(List<JvmAttacher.Target> candidates, String answer) {
        long n;
        try {
            n = Long.parseLong(answer.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (n >= 1 && n <= candidates.size()) {
            return candidates.get((int) n - 1);
        }
        for (JvmAttacher.Target candidate : candidates) {
            if (candidate.pid() == n) {
                return candidate;
            }
        }
        return null;
    }

    private static String table(List<JvmAttacher.Target> candidates, boolean numbered) {
        List<String> headers = new ArrayList<>();
        if (numbered) {
            headers.add("#");
        }
        headers.addAll(List.of("PID", "STARTED", "DIRECTORY", "COMMAND"));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            JvmAttacher.Target target = candidates.get(i);
            Discovery.Candidate shape = new Discovery.Candidate(target.pid(), target.displayName(), "",
                    target.startedAt(), target.directory());
            List<String> row = new ArrayList<>();
            if (numbered) {
                row.add(Integer.toString(i + 1));
            }
            row.addAll(List.of(Long.toString(target.pid()), Discovery.started(shape), Discovery.directory(shape),
                    shorten(target.displayName())));
            rows.add(row);
        }
        return Format.table(headers, rows);
    }

    private static String shorten(String command) {
        return command.length() > 60 ? command.substring(0, 59) + "…" : command;
    }

    /** The backend as the agent names it, or {@code null}: the report is a courtesy, never a failure. */
    private static String backendOf(Connector connector, long pid) {
        try (ControlPlane plane = connector.connect(pid)) {
            EnvironmentReportData report = plane.mbean().environmentReport();
            String name = report.getBackendName();
            if (name == null) {
                return null;
            }
            return report.getBackendVersion() == null ? name : name + " " + report.getBackendVersion();
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** What happened, for both renderings. */
    private record Result(long pid, String state, String version, String backend, String delivery, int javaVersion,
            AgentJar.Choice jar) {

        static Result alreadyRunning(JvmAttacher.Target target) {
            return new Result(target.pid(), "already-running", target.version(), null, target.delivery(), 0, null);
        }

        static Result loaded(long pid, JvmAttacher.Loaded loaded, String backend, AgentJar.Choice jar) {
            return new Result(pid, loaded.version() == null ? "loaded" : "active", loaded.version(), backend, null,
                    loaded.javaVersion(), jar);
        }
    }

    private void report(PrintStream out, Result result) {
        if (json) {
            out.println(Json.attach(result.pid(), result.state(), result.version(), result.backend(),
                    result.delivery(), result.jar() == null ? null : result.jar().path().toString(),
                    result.jar() == null ? null : result.jar().source().name().toLowerCase(java.util.Locale.ROOT)));
            return;
        }
        long pid = result.pid();
        switch (result.state()) {
            case "already-running" -> {
                String how = "extension".equals(result.delivery())
                        ? "through the Quarkus extension"
                        : "through the agent, from -javaagent or an earlier attach";
                if (result.version() == null) {
                    out.println("PID " + pid + " already has LogAperture " + how
                            + " and it is starting up. Nothing was loaded.");
                } else {
                    out.println("PID " + pid + " already runs LogAperture " + result.version() + ", " + how
                            + ". Nothing was loaded.");
                }
            }
            case "active" -> {
                out.println("LogAperture " + result.version() + " is active in PID " + pid
                        + (result.backend() == null ? "" : " (" + result.backend() + ")") + ".");
                details(out, result);
            }
            default -> {
                out.println("The LogAperture agent is loaded in PID " + pid + " but not active yet: it starts when "
                        + "the application first logs through Logback or java.util.logging. Run 'logctl status' "
                        + "once it has.");
                details(out, result);
            }
        }
    }

    private static void details(PrintStream out, Result result) {
        out.println("  Agent jar: " + result.jar().path() + " (" + result.jar().reason() + ")");
        if (result.javaVersion() >= 21) {
            out.println("  Java " + result.javaVersion() + " prints a warning about dynamic agent loading on that "
                    + "JVM's own standard error. It is expected.");
        }
        out.println("  This lasts until PID " + result.pid() + " stops. To keep it, add -javaagent:<agent jar> "
                + "to its launch line.");
    }
}
