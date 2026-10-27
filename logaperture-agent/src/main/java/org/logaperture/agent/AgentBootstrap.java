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
import org.logaperture.container.quarkus.QuarkusContainerIntegration;
import org.logaperture.container.wildfly.WildFlyContainerIntegration;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.LibraryRecipeScanner;
import org.logaperture.core.StderrAuditLog;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.startup.EngineStartup;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
     * last". WildFly and Quarkus never match the same launch (Quarkus needs its own entry point).
     */
    private static List<ContainerIntegration> integrations() {
        return List.of(new WildFlyContainerIntegration(), new QuarkusContainerIntegration(), new NoneContainerIntegration());
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
        if (!EngineStartup.claim(EngineStartup.Delivery.AGENT)) {
            // doc/specs/quarkus-extension.md #3: only an attach can get here, into a JVM where the
            // Quarkus extension already runs. premain always claims first.
            Diagnostics.warn(claimedMessage(EngineStartup.claimant().orElse("?")));
            return;
        }
        try {
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            AuditLog auditLog = new StderrAuditLog();
            AgentArguments arguments = parseArguments(agentArgs);
            VendorDefaults vendorDefaults = EngineStartup.loadVendorDefaults(arguments.vendorDefaults());
            boolean stormDetection = arguments.stormDetection();

            ContainerIntegration container = integrations().stream()
                    .filter(ContainerIntegration::detect)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no ContainerIntegration detected -- none should always match"));

            // The control surface is registered, and the discovery marker published, only once the
            // first context is actually installed -- so "MBean present" implies "there is something
            // to control" (the CLI polls for the MBean, then calls it). The callback is handed the
            // aggregate directly, so there is no return value to race against the async install.
            // The vendor defaults load is audited there too, not in premain: only an installed
            // context actually applies the file (a container that never installs one applied nothing).
            Consumer<AggregateLevelControl> onFirstContextReady = EngineStartup.onFirstContextReady(container.id(),
                    EngineStartup.Delivery.AGENT, policy, auditLog, vendorDefaults, recipeScanner(inst));
            container.activate(inst, policy, auditLog, vendorDefaults, stormDetection, onFirstContextReady);
            if (entry == Entry.AGENTMAIN) {
                AttachWatch.start(container.id()); // doc/specs/logctl-attach.md #6: say why, if nothing comes up
            }
        } catch (Throwable t) {
            Diagnostics.error("LogAperture agent bootstrap failed to start", t);
        }
    }

    /** quarkus-extension.md #3: the one line an agent start writes when another route owns the JVM. */
    static String claimedMessage(String claimant) {
        return "LogAperture is already running in this JVM as the Quarkus " + claimant + "; the agent starts nothing.";
    }

    /** Issue #120: the one line a second start writes instead of bootstrapping again. */
    static String duplicateStartMessage(Entry entry) {
        return "LogAperture is already started in this JVM; ignoring " + entry.ignored + ".";
    }

    /**
     * Parses the agent arguments and reports each warning -- on the {@code premain} thread, so it
     * may not touch {@code java.util.logging} (logaperture-spec.md §15.6). Never throws: arguments
     * that can't be read at all leave the agent on its defaults.
     */
    static AgentArguments parseArguments(String agentArgs) {
        try {
            AgentArguments arguments = AgentArguments.parse(agentArgs, Path.of(System.getProperty("user.dir", ".")));
            for (String warning : arguments.warnings()) {
                Diagnostics.warn(warning);
            }
            return arguments;
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to read the agent arguments, continuing with the defaults", e);
            return new AgentArguments(Optional.empty(), List.of());
        }
    }

    /**
     * Library recipes are found through every class loader the agent's {@code Instrumentation} can
     * see (doc/specs/recipes.md); no {@code Instrumentation} (a unit test) finds none.
     */
    static LibraryRecipeScanner recipeScanner(Instrumentation inst) {
        return inst == null ? LibraryRecipeScanner.none() : new LibraryRecipeScanner(inst::getAllLoadedClasses);
    }
}
