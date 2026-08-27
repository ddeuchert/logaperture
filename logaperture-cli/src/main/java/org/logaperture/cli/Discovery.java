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

import java.util.ArrayList;
import java.util.List;

/**
 * "No PID argument when exactly one candidate JVM is running. Discover it."
 * (doc/logaperture-spec.md §14.5). A candidate is a JVM this OS user can
 * attach to whose system properties carry the {@code logaperture.version}
 * marker the agent sets once it has installed
 * (doc/specs/cli-transport.md "Discovery").
 */
final class Discovery {

    static final String MARKER_PROPERTY = "logaperture.version";

    private Discovery() {
    }

    static long resolveTargetPid(Long explicitPid) {
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
        if (candidates.size() > 1) {
            throw new CliError(CliError.AMBIGUOUS, ambiguityMessage(candidates));
        }
        return candidates.get(0).pid();
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
            String version = vm.getSystemProperties().getProperty(MARKER_PROPERTY);
            return version == null ? Probe.NOT_APPLICABLE : Probe.of(new Candidate(pid, descriptor.displayName(), version));
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
            rows.add(List.of(Long.toString(candidate.pid()), candidate.version(), candidate.displayName()));
        }
        return Format.table(List.of("PID", "VERSION", "COMMAND"), rows)
                + "\nSeveral candidates — pass --pid <n>.";
    }

    private record Candidate(long pid, String displayName, String version) {
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
