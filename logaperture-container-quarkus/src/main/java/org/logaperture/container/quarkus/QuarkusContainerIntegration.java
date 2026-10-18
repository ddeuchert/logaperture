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
package org.logaperture.container.quarkus;

import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.container.jbosslogmanager.JBossLogManagerContainer;
import org.logaperture.container.jbosslogmanager.JBossLogManagerReadiness;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.InstallGuidance;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@link ContainerIntegration} for Quarkus in JVM mode — doc/specs/quarkus-support.md.
 * {@link #detect()} probes only a class-file resource, system properties and the file system
 * (never {@code java.util.logging} — the premain gotcha); {@link #activate} waits, off-thread,
 * until JBoss LogManager is installed <em>and</em> the application has started initialising it
 * ({@link JBossLogManagerReadiness#JUL_INITIALISATION_STARTED}, Q1), then binds one {@link
 * JulLoggingAdapter} to the single logging context and installs level control for it.
 *
 * <p>The extra condition matters: the WildFly gate fires on line 1 of {@code QuarkusEntryPoint.main},
 * and could make the first JUL call before Quarkus has set its build-time {@code min-level} floor,
 * which JBoss LogManager then fixes per logger for good. Waiting for {@code LogContext} means the
 * agent only ever joins an initialisation the application began.
 *
 * <p>Native image is never supported (spec §15.9), and the JBoss LogManager configuration
 * listener never fires on Quarkus, so unlike WildFly none is registered.
 */
public final class QuarkusContainerIntegration implements ContainerIntegration {

    private final Duration sweepInterval;
    private final Supplier<String> javaCommand;
    private final Supplier<String> classPath;
    private final Supplier<Boolean> entryPointPresent;

    public QuarkusContainerIntegration() {
        this(SweepPolicy.interval(), () -> System.getProperty("sun.java.command"),
                () -> System.getProperty("java.class.path"), QuarkusContainerIntegration::entryPointOnSystemLoader);
    }

    /** Test seam: a short sweep interval and the three launch facts detection reads. */
    QuarkusContainerIntegration(Duration sweepInterval, Supplier<String> javaCommand, Supplier<String> classPath,
            Supplier<Boolean> entryPointPresent) {
        this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval");
        this.javaCommand = javaCommand;
        this.classPath = classPath;
        this.entryPointPresent = entryPointPresent;
    }

    @Override
    public String id() {
        return "quarkus";
    }

    @Override
    public boolean detect() {
        return QuarkusLaunch.isQuarkusLaunch(entryPointPresent.get(), javaCommand.get(), classPath.get());
    }

    @Override
    public AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog, VendorDefaults vendorDefaults,
            boolean stormDetectionEnabled, Consumer<AggregateLevelControl> onFirstContextReady) {
        // A supplier, re-resolved on every logctl env: a file read, so nothing is baked in at premain.
        JBossLogManagerContainer host = new JBossLogManagerContainer("Quarkus", policy, auditLog, sweepInterval,
                this::version, vendorDefaults, stormDetectionEnabled);

        Runnable install = () -> {
            try {
                JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(new QuarkusHandlerNameResolver());
                host.installContext(ContextHandle.of(ContextHandle.SYSTEM, "quarkus", adapter));
                onFirstContextReady.accept(host.operations());
                startEarlyResweep(host);
                Diagnostics.debug("level control installed (quarkus container, system LogContext)");
            } catch (Throwable t) {
                Diagnostics.error("LogAperture failed to install level control for Quarkus", t);
            }
        };
        Runnable awaitJulThenInstall = () -> JBossLogManagerReadiness.awaitJBossLogManagerThen(
                inst, JBossLogManagerReadiness.JUL_INITIALISATION_STARTED, install);
        Thread detector = new Thread(awaitJulThenInstall, "logaperture-quarkus-detect");
        detector.setDaemon(true);
        detector.start();

        return host.operations();
    }

    /**
     * Q2: both mechanisms. The exact trigger (the delayed handler's {@code isActivated()}) is
     * polled every 50 ms for up to two minutes -- Quarkus start-up can be slow -- and the startup
     * fast-sweep window is the safety net for versions where the handler can't be found.
     */
    private static void startEarlyResweep(JBossLogManagerContainer host) {
        BooleanSupplier activation = DelayedHandlerWatch.lazy(java.util.logging.Logger.getLogger(""));
        host.startEarlyResweep(SweepPolicy.startupWindow(), activation, Duration.ofMillis(50), Duration.ofMinutes(2));
    }

    /** The Quarkus version for {@code logctl env}, read from the {@code quarkus-core} jar (Q8). */
    @Override
    public Optional<String> version() {
        return QuarkusLaunch.version(javaCommand.get(), classPath.get());
    }

    @Override
    public InstallGuidance guidance() {
        return new InstallGuidance(
                "Add the agent to the Quarkus JVM's command line (JVM mode only, not native image)",
                List.of(
                        "Start the application with: java -javaagent:/path/to/logaperture-agent.jar "
                                + "-jar quarkus-run.jar",
                        "In Quarkus's container images set JAVA_OPTS_APPEND=\"-javaagent:/path/to/logaperture-agent.jar\"",
                        "The agent controls logging through java.util.logging (which JBoss LogManager backs), "
                                + "so nothing else is needed."));
    }

    private static boolean entryPointOnSystemLoader() {
        try {
            return ClassLoader.getSystemClassLoader().getResource(QuarkusLaunch.ENTRY_POINT_RESOURCE) != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
