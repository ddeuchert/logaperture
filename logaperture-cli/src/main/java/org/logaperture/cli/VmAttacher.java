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

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** The JDK Attach API behind {@link JvmAttacher} (doc/specs/logctl-attach.md). */
final class VmAttacher implements JvmAttacher {

    private static final long POLL_MILLIS = 200;

    @Override
    public List<Target> targets() {
        return targets(HostProcesses.system());
    }

    /**
     * The JDK's list, then on Windows the JVMs of other accounts that list cannot show (a service under
     * {@code LocalSystem}): doc/specs/attach-windows-existence.md W2.
     */
    List<Target> targets(HostProcesses host) {
        List<Target> targets = new ArrayList<>();
        Set<Long> listed = new HashSet<>();
        try {
            for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
                long pid;
                try {
                    pid = Long.parseLong(descriptor.id());
                } catch (NumberFormatException notAPid) {
                    continue;
                }
                listed.add(pid);
                if (pid == ProcessHandle.current().pid()) {
                    continue;
                }
                VirtualMachine vm = null;
                try {
                    vm = VirtualMachine.attach(descriptor);
                    targets.add(targetOf(pid, descriptor.displayName(), vm.getSystemProperties()));
                } catch (Exception cannotInspect) {
                    // not ours to attach to (another user, a race): not a candidate
                } finally {
                    Quietly.detach(vm);
                }
            }
            targets.addAll(probeUnlisted(listed, host, VmAttacher::probe));
        } catch (LinkageError noAttachModule) {
            throw noJdkAttach();
        }
        return targets;
    }

    /** Attaches to one PID and reads it; a seam for the test. */
    @FunctionalInterface
    interface PidProbe {
        Target probe(long pid) throws Exception;
    }

    private static Target probe(long pid) throws Exception {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
            return targetOf(pid, "", vm.getSystemProperties());
        } finally {
            Quietly.detach(vm);
        }
    }

    /**
     * Probes each {@link #unlisted} PID once; one that cannot be attached to (not a JVM, another account, a race)
     * is not a candidate. Package-visible for the test.
     */
    static List<Target> probeUnlisted(Set<Long> listed, HostProcesses host, PidProbe probe) {
        List<Target> found = new ArrayList<>();
        for (long pid : unlisted(listed, host)) {
            try {
                found.add(probe.probe(pid));
            } catch (Exception cannotInspect) {
                // skipped, as in the JDK list's own loop
            }
        }
        return found;
    }

    /**
     * The PIDs of other accounts' JVMs the JDK's list did not name, without this process: Windows only,
     * from {@link HostProcesses#otherAccountJvmPids()}, which is also all that discovery probes. Package-visible
     * for the test.
     */
    static List<Long> unlisted(Set<Long> listed, HostProcesses host) {
        List<Long> pids = new ArrayList<>();
        for (long pid : host.otherAccountJvmPids()) {
            if (!listed.contains(pid) && pid != host.currentPid()) {
                pids.add(pid);
            }
        }
        return pids;
    }

    @Override
    public Target inspect(long pid) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
            String name = ProcessHandle.of(pid).flatMap(p -> p.info().command()).orElse("");
            return targetOf(pid, name, vm.getSystemProperties());
        } catch (LinkageError noAttachModule) {
            throw noJdkAttach();
        } catch (Exception e) {
            throw AgentConnection.attachFailure(pid, e, HostProcesses.system());
        } finally {
            Quietly.detach(vm);
        }
    }

    @Override
    public Loaded load(long pid, Path agentJar, String agentArgs, Duration wait) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
            vm.loadAgent(agentJar.toString(), agentArgs);
            VirtualMachine attached = vm;
            return awaitMarker(attached::getSystemProperties, wait, POLL_MILLIS);
        } catch (LinkageError noAttachModule) {
            throw noJdkAttach();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CliError(CliError.UNEXPECTED, "Interrupted while waiting for the agent in PID " + pid + ".");
        } catch (Exception e) {
            throw loadFailure(pid, e);
        } finally {
            Quietly.detach(vm);
        }
    }

    /** Where the marker is read from; the real one is {@code VirtualMachine.getSystemProperties}. */
    @FunctionalInterface
    interface Properties_ {
        Properties get() throws Exception;
    }

    /**
     * Polls for the marker for up to {@code wait}. The agent is already loaded by now, so a poll that fails
     * (the target went away, its attach socket closed) is not a failed attach: it is a load nobody could
     * confirm, and reported as that. Package-visible for the test.
     */
    static Loaded awaitMarker(Properties_ source, Duration wait, long pollMillis) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            Properties properties;
            try {
                properties = source.get();
            } catch (Exception cannotAsk) {
                return new Loaded(null, 0);
            }
            String version = properties.getProperty(Discovery.MARKER_PROPERTY);
            if (version != null || System.nanoTime() - deadline >= 0) {
                return new Loaded(version, javaFeatureOf(properties.getProperty("java.specification.version")));
            }
            Thread.sleep(pollMillis);
        }
    }

    private static Target targetOf(long pid, String displayName, Properties properties) {
        Instant startedAt = ProcessHandle.of(pid).flatMap(process -> process.info().startInstant()).orElse(null);
        return new Target(pid, displayName, properties.getProperty(Discovery.MARKER_PROPERTY),
                properties.getProperty("logaperture.delivery"), startedAt, properties.getProperty("user.dir"));
    }

    /** {@code 17} for {@code "17"}, {@code 8} for {@code "1.8"}, 0 when it is not a version. */
    static int javaFeatureOf(String specification) {
        if (specification == null) {
            return 0;
        }
        String feature = specification.startsWith("1.") ? specification.substring(2) : specification;
        try {
            return Integer.parseInt(feature.split("\\.")[0]);
        } catch (NumberFormatException notAVersion) {
            return 0;
        }
    }

    static CliError noJdkAttach() {
        return new CliError(CliError.UNEXPECTED, "This Java has no attach support (the jdk.attach module). "
                + "Run logctl with a JDK, not a JRE.");
    }

    /** doc/specs/logctl-attach.md #4: the cause, and the way out. Package-visible for the test. */
    static CliError loadFailure(long pid, Exception cause) {
        return loadFailure(pid, cause, HostProcesses.system());
    }

    /**
     * As above; "no such process" is {@link HostProcesses#exists}'s answer, not {@code ProcessHandle}'s, which is
     * empty on Windows for a running process the caller may not open (doc/specs/attach-windows-existence.md W1).
     */
    static CliError loadFailure(long pid, Exception cause, HostProcesses host) {
        if (!host.exists(pid)) {
            return new CliError(CliError.NO_JVM, "No process with PID " + pid + ".");
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        if (cause instanceof AgentLoadException && message.toLowerCase(java.util.Locale.ROOT).contains("dynamic agent loading")) {
            return new CliError(CliError.ATTACH_DENIED, "PID " + pid + " was started with dynamic agent loading turned "
                    + "off (-XX:-EnableDynamicAgentLoading), so no agent can be loaded into it. Restart it with "
                    + "-javaagent:logaperture-agent.jar, or without that flag.\n"
                    + "(A future Java may turn dynamic loading off by default; the JVM would then need "
                    + "-XX:+EnableDynamicAgentLoading at launch, which is as much of a restart.)");
        }
        if (cause instanceof AgentLoadException) {
            return new CliError(CliError.UNEXPECTED, "PID " + pid + " could not load the agent jar: " + message);
        }
        if (cause instanceof AgentInitializationException) {
            return new CliError(CliError.UNEXPECTED, "The agent was loaded into PID " + pid + " but failed to start: "
                    + message);
        }
        return AgentConnection.attachDenied(pid, cause, host.windows()); // exists() was asked above: not twice
    }
}
