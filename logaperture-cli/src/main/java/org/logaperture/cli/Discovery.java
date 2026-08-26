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
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            Candidate candidate = probe(descriptor);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            throw new CliError(CliError.NO_JVM,
                    "No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.");
        }
        if (candidates.size() > 1) {
            throw new CliError(CliError.AMBIGUOUS, ambiguityMessage(candidates));
        }
        return candidates.get(0).pid();
    }

    private static Candidate probe(VirtualMachineDescriptor descriptor) {
        long pid;
        try {
            pid = Long.parseLong(descriptor.id());
        } catch (NumberFormatException notAPid) {
            return null;
        }
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(descriptor);
            String version = vm.getSystemProperties().getProperty(MARKER_PROPERTY);
            return version == null ? null : new Candidate(pid, descriptor.displayName(), version);
        } catch (Exception cannotInspect) {
            // A JVM we can't attach to (a race, a different user, this same VM) can't be a candidate anyway.
            return null;
        } finally {
            detachQuietly(vm);
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

    private static void detachQuietly(VirtualMachine vm) {
        if (vm == null) {
            return;
        }
        try {
            vm.detach();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private record Candidate(long pid, String displayName, String version) {
    }
}
