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

import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.container.jbosslogmanager.JBossLogManagerContainer;
import org.logaperture.container.jbosslogmanager.JBossLogManagerReadiness;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.InstallGuidance;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
        return QuarkusEngine.CONTAINER_ID;
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
        JBossLogManagerContainer host = QuarkusEngine.newHost(policy, auditLog, vendorDefaults, stormDetectionEnabled,
                sweepInterval, this::version);

        Runnable install = () -> {
            try {
                JulLoggingAdapter adapter = QuarkusEngine.installSystemContext(host, onFirstContextReady,
                        configLoader(inst));
                startEarlyResweep(host, adapter);
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
    private static void startEarlyResweep(JBossLogManagerContainer host, JulLoggingAdapter adapter) {
        DelayedHandlerWatch.Lazy activation = DelayedHandlerWatch.lazy(java.util.logging.Logger.getLogger(""));
        Instant installed = Instant.now();
        Supplier<List<DoctorFinding>> containerFindings = () -> {
            List<DoctorFinding> findings = new java.util.ArrayList<>(earlySweepFindings(activation, installed));
            findings.addAll(QuarkusEngine.formatterFindings(adapter));
            findings.addAll(QuarkusEngine.handlerNameFindings(adapter));
            return findings;
        };
        host.setContainerFindings(containerFindings);
        host.startEarlyResweep(SweepPolicy.startupWindow(), activation, Duration.ofMillis(50), Duration.ofMinutes(2));
    }

    private static final long CONFIG_LOADER_RESCAN_NANOS = Duration.ofSeconds(2).toNanos();

    /**
     * The class loader that defines MicroProfile Config's {@code ConfigProvider}, found among the
     * loaded classes (it is the application's, not the agent's) and remembered once found.
     */
    private static Supplier<ClassLoader> configLoader(Instrumentation inst) {
        AtomicReference<ClassLoader> found = new AtomicReference<>();
        AtomicLong nextScan = new AtomicLong();
        return () -> {
            ClassLoader loader = found.get();
            long now = System.nanoTime();
            if (loader == null && now - nextScan.get() >= 0) {
                loader = QuarkusConfigReader.definingLoader(inst.getAllLoadedClasses());
                found.set(loader);
                nextScan.set(now + CONFIG_LOADER_RESCAN_NANOS); // a full class scan: not on every sweep tick
            }
            return loader;
        };
    }

    /** How long after install the delayed handler may still be missing before {@code doctor} says so. */
    private static final Duration LOCATE_GRACE = Duration.ofSeconds(30);

    /**
     * Q2: if the exact trigger could not be set up -- the delayed handler was never found, as on a
     * Quarkus version that names it differently -- {@code doctor} says so, and what still covers it.
     */
    static List<DoctorFinding> earlySweepFindings(DelayedHandlerWatch.Lazy activation, Instant installed) {
        if (activation.isLocated() || Instant.now().isBefore(installed.plus(LOCATE_GRACE))) {
            return List.of();
        }
        return List.of(new DoctorFinding("agent.early-sweep", Severity.INFO, "quarkus",
                "The early re-sweep trigger is off: Quarkus's QuarkusDelayedHandler was not found on the root logger.",
                "Level and rule changes made while Quarkus starts are still repaired by the startup sweep "
                        + "(every 250 ms for the first 15 s) and the periodic sweep.", null));
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
