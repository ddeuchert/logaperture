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
import org.logaperture.core.AuditRecord;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.LibraryRecipeScanner;
import org.logaperture.core.RecipeCatalog;
import org.logaperture.core.RecipeOperations;
import org.logaperture.core.RecipeService;
import org.logaperture.core.StderrAuditLog;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.VendorDefaultsFile;
import org.logaperture.core.spi.ContainerIntegration;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Set by the first {@link #start} that gets past the kill switch, whether or not that start then
     * succeeds (issue #120). The JVM calls {@code premain} once per {@code -javaagent:} entry, and
     * {@code agentmain} again on every attach; every call after the first must not bootstrap a second
     * time. One flag covers them all: every entry resolves {@code LogApertureAgent} through the system
     * class loader, so they all share this class.
     */
    private static final AtomicBoolean STARTED = new AtomicBoolean();

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
        start(inst, null, Entry.PREMAIN);
    }

    /** How the JVM entered the agent -- only named in the line a duplicate start writes. */
    enum Entry {
        PREMAIN("the duplicate -javaagent entry"),
        AGENTMAIN("the attach request");

        private final String ignored;

        Entry(String ignored) {
            this.ignored = ignored;
        }
    }

    /**
     * @param agentArgs the {@code -javaagent:...=<args>} string, or {@code null} -- doc/specs/
     *                  vendor-defaults.md "Agent arguments"
     */
    static void start(Instrumentation inst, String agentArgs, Entry entry) {
        if (Boolean.getBoolean(DISABLED_PROPERTY)) {
            return; // global kill switch, honoured without needing the control plane reachable
        }
        if (!STARTED.compareAndSet(false, true)) {
            Diagnostics.warn(duplicateStartMessage(entry));
            return;
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
            // The vendor defaults load is audited here too, not in premain: only an installed context
            // actually applies the file (a container that never installs one applied nothing).
            Consumer<AggregateLevelControl> onFirstContextReady = operations -> {
                auditVendorDefaults(vendorDefaults, auditLog);
                publishControlSurface(container, operations, recipes(inst, policy, vendorDefaults, operations),
                        vendorDefaults);
            };
            container.activate(inst, policy, auditLog, vendorDefaults, onFirstContextReady);
        } catch (Throwable t) {
            Diagnostics.error("LogAperture agent bootstrap failed to start", t);
        }
    }

    /** Issue #120: the one line a second start writes instead of bootstrapping again. */
    static String duplicateStartMessage(Entry entry) {
        return "LogAperture is already started in this JVM; ignoring " + entry.ignored + ".";
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
                Diagnostics.warn(warning);
            }
            if (arguments.vendorDefaults().isEmpty()) {
                return VendorDefaults.none();
            }
            VendorDefaults vendorDefaults = VendorDefaultsFile.load(arguments.vendorDefaults().get());
            reportVendorDefaults(vendorDefaults);
            return vendorDefaults;
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to read the agent arguments, continuing without vendor defaults", e);
            return VendorDefaults.none();
        }
    }

    private static void reportVendorDefaults(VendorDefaults vendorDefaults) {
        String path = vendorDefaults.path().map(Path::toString).orElse("?");
        if (vendorDefaults.status() == VendorDefaults.Status.REJECTED) {
            Diagnostics.warn("vendor defaults file " + path + " was rejected -- none of its settings "
                    + "apply:\n  " + String.join("\n  ", vendorDefaults.errors()));
            return;
        }
        // Loaded: the startup banner names it (doc/specs/quieter-output.md Q3).
        if (vendorDefaults.writable()) {
            Diagnostics.warn("vendor defaults file " + path + " (or its directory) is writable by the "
                    + "account this JVM runs as -- anyone who can run code as that account can change the "
                    + "baseline logging configuration");
        }
    }

    /**
     * {@code list recipes} / {@code show recipe} (doc/specs/recipes.md): the vendor defaults file's
     * recipes, the recipes folder, and the libraries found through {@code inst}'s loaded classes.
     * Never throws -- a failure here leaves the rest of the control surface working, with no recipes.
     */
    static RecipeOperations recipes(Instrumentation inst, CapabilityPolicy policy, VendorDefaults vendorDefaults,
            AggregateLevelControl operations) {
        try {
            LibraryRecipeScanner scanner = inst == null ? LibraryRecipeScanner.none()
                    : new LibraryRecipeScanner(inst::getAllLoadedClasses);
            RecipeCatalog catalog = new RecipeCatalog(vendorDefaults.recipes(), RecipeCatalog.defaultFolder(), scanner);
            return new RecipeService(policy, catalog, operations, operations, operations);
        } catch (RuntimeException e) {
            Diagnostics.warn("recipes are unavailable", e);
            return RecipeOperations.none();
        }
    }

    /**
     * One audit record for a loaded vendor defaults file, naming its path and hash -- doc/specs/
     * quieter-output.md Q1 -- in place of one per entry. A rejected file writes none (its errors are
     * reported instead).
     */
    static void auditVendorDefaults(VendorDefaults vendorDefaults, AuditLog auditLog) {
        if (vendorDefaults.status() != VendorDefaults.Status.LOADED) {
            return;
        }
        try {
            auditLog.record(new AuditRecord(Instant.now(), System.getProperty("user.name", "unknown"),
                    VendorDefaults.AUDIT_SOURCE, vendorDefaults.path().map(Path::toString).orElse("?"), null,
                    "loaded sha256=" + vendorDefaults.sha256().orElse("?") + " (" + vendorDefaults.summary() + ")",
                    null, AuditRecord.Action.MUTATION).withTarget(AuditRecord.Target.FILE));
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to audit the vendor defaults load", e);
        }
    }

    /**
     * The one startup line -- doc/specs/quieter-output.md Q3: version, container, the vendor defaults
     * file and what it set, and how many saved settings were restored.
     */
    static String banner(String version, String containerId, VendorDefaults vendorDefaults, int restored) {
        StringBuilder line = new StringBuilder("LogAperture ").append(version).append(" active (")
                .append("wildfly".equals(containerId) ? "WildFly" : "JVM").append(')');
        List<String> parts = new java.util.ArrayList<>();
        if (vendorDefaults.status() == VendorDefaults.Status.LOADED) {
            parts.add("vendor defaults " + vendorDefaults.path().map(Path::toString).orElse("?") + " ("
                    + vendorDefaults.summary() + ")");
        }
        if (restored > 0) {
            parts.add(restored + (restored == 1 ? " sticky setting" : " sticky settings") + " restored");
        }
        if (!parts.isEmpty()) {
            line.append(": ").append(String.join("; ", parts));
        }
        return line.toString();
    }

    private static void publishControlSurface(ContainerIntegration container, AggregateLevelControl operations,
            RecipeOperations recipes, VendorDefaults vendorDefaults) {
        try {
            JmxRegistrar.register(operations, operations, operations, operations, operations, operations, operations,
                    operations, recipes); // AggregateLevelControl implements all eight operation interfaces
            System.setProperty(VERSION_PROPERTY, agentVersion());
            Diagnostics.notice(banner(agentVersion(), container.id(), vendorDefaults, operations.restoredSettings()));
        } catch (Throwable t) {
            Diagnostics.error("LogAperture failed to register the JMX control surface", t);
        }
    }

    private static String agentVersion() {
        String version = LogApertureAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev"; // null when run from classes dir, e.g. AgentBootstrapTest
    }
}
