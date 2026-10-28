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
import java.util.List;
import java.util.Properties;

/** The JDK Attach API behind {@link JvmAttacher} (doc/specs/logctl-attach.md). */
final class VmAttacher implements JvmAttacher {

    private static final long POLL_MILLIS = 200;

    @Override
    public List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        try {
            for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
                long pid;
                try {
                    pid = Long.parseLong(descriptor.id());
                } catch (NumberFormatException notAPid) {
                    continue;
                }
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
        } catch (LinkageError noAttachModule) {
            throw noJdkAttach();
        }
        return targets;
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
        if (ProcessHandle.of(pid).isEmpty()) {
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
        return AgentConnection.attachFailure(pid, cause, HostProcesses.system());
    }
}
