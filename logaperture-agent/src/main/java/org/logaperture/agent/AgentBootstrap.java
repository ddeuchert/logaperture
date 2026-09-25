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
package org.logaperture.agent;

import org.logaperture.bridge.Diagnostics;
import org.logaperture.container.none.NoneContainerIntegration;
import org.logaperture.container.wildfly.WildFlyContainerIntegration;
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.StderrAuditLog;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.VendorDefaultsFile;
import org.logaperture.core.spi.ContainerIntegration;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Detect-then-install: pick the first {@link ContainerIntegration} whose
 * {@link ContainerIntegration#detect() detect()} is true ({@code none} is
 * the always-true fallback, tried last), hand it the {@link
 * Instrumentation} / policy / audit sink, and register the {@link
 * AggregateLevelControl} it returns with {@link JmxRegistrar}. Every step is
 * individually try/caught to {@link Diagnostics} — install failure must
 * never propagate into, or block startup of, the target application
 * (doc/specs/level-control.md "Failure handling").
 */
final class AgentBootstrap {

    private static final String DISABLED_PROPERTY = "logaperture.disabled";

    /**
     * Set (to the agent's version) only once the first logging context has
     * installed, so its presence is a reliable "the control plane is up"
     * marker — what {@code logaperture-cli}'s discovery filters candidate
     * JVMs on (doc/specs/cli-transport.md "Discovery").
     */
    private static final String VERSION_PROPERTY = "logaperture.version";

    private AgentBootstrap() {
    }

    /**
     * The ordered integration list — "most specific first, {@code none}
     * last". Slice 3 prepends the WildFly integration.
     */
    private static List<ContainerIntegration> integrations() {
        return List.of(new WildFlyContainerIntegration(), new NoneContainerIntegration());
    }

    static void start(Instrumentation inst) {
        start(inst, null);
    }

    /**
     * @param agentArgs the {@code -javaagent:...=<args>} string, or {@code null} -- doc/specs/
     *                  vendor-defaults.md "Agent arguments"
     */
    static void start(Instrumentation inst, String agentArgs) {
        if (Boolean.getBoolean(DISABLED_PROPERTY)) {
            return; // global kill switch, honoured without needing the control plane reachable
        }
        try {
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            AuditLog auditLog = new StderrAuditLog();
            VendorDefaults vendorDefaults = loadVendorDefaults(agentArgs);

            ContainerIntegration container = integrations().stream()
                    .filter(ContainerIntegration::detect)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no ContainerIntegration detected -- none should always match"));

            // The control surface is registered, and the discovery marker
            // published, only once the first context is actually installed --
            // so "MBean present" implies "there is something to control"
            // (the CLI polls for the MBean, then calls it). The callback is
            // handed the aggregate directly, so there is no return value to
            // race against the async install.
            Consumer<AggregateLevelControl> onFirstContextReady =
                    operations -> publishControlSurface(container, operations);
            container.activate(inst, policy, auditLog, vendorDefaults, onFirstContextReady);
        } catch (Throwable t) {
            Diagnostics.error("LogAperture agent bootstrap failed to start", t);
        }
    }

    /**
     * Parses the agent arguments and, if {@code --vendor-defaults=} names a file, reads and
     * validates it -- on the {@code premain} thread, so neither step may touch {@code
     * java.util.logging} (logaperture-spec.md §15.6). Never throws: a bad argument or a rejected
     * file is reported and the agent carries on without vendor defaults.
     */
    static VendorDefaults loadVendorDefaults(String agentArgs) {
        try {
            AgentArguments arguments = AgentArguments.parse(agentArgs, Path.of(System.getProperty("user.dir", ".")));
            for (String warning : arguments.warnings()) {
                Diagnostics.warn("LogAperture: " + warning);
            }
            if (arguments.vendorDefaults().isEmpty()) {
                return VendorDefaults.none();
            }
            VendorDefaults vendorDefaults = VendorDefaultsFile.load(arguments.vendorDefaults().get());
            reportVendorDefaults(vendorDefaults);
            return vendorDefaults;
        } catch (RuntimeException e) {
            Diagnostics.warn("LogAperture: failed to read the agent arguments, continuing without vendor defaults", e);
            return VendorDefaults.none();
        }
    }

    private static void reportVendorDefaults(VendorDefaults vendorDefaults) {
        String path = vendorDefaults.path().map(Path::toString).orElse("?");
        if (vendorDefaults.status() == VendorDefaults.Status.REJECTED) {
            Diagnostics.warn("LogAperture: vendor defaults file " + path + " was rejected -- none of its settings "
                    + "apply:\n  " + String.join("\n  ", vendorDefaults.errors()));
            return;
        }
        Diagnostics.info("LogAperture: vendor defaults loaded from " + path + " (" + vendorDefaults.summary() + ")");
        if (vendorDefaults.writable()) {
            Diagnostics.warn("LogAperture: vendor defaults file " + path + " (or its directory) is writable by the "
                    + "account this JVM runs as -- anyone who can run code as that account can change the "
                    + "baseline logging configuration");
        }
    }

    private static void publishControlSurface(ContainerIntegration container, AggregateLevelControl operations) {
        try {
            JmxRegistrar.register(operations, operations, operations, operations, operations, operations, operations); // AggregateLevelControl implements all seven interfaces
            System.setProperty(VERSION_PROPERTY, agentVersion());
            Diagnostics.info("LogAperture level control installed (" + container.id() + " container, JMX surface)");
        } catch (Throwable t) {
            Diagnostics.error("LogAperture failed to register the JMX control surface", t);
        }
    }

    private static String agentVersion() {
        String version = LogApertureAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev"; // null when run from classes dir, e.g. AgentBootstrapTest
    }
}
