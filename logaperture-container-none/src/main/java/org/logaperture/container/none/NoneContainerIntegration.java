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
package org.logaperture.container.none;

import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.adapter.logback.LogbackAdapterFactory;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.loader.KnownFrameworks;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@link ContainerIntegration} for a plain {@code java -jar} app — the
 * always-present fallback the agent uses when no real container is detected
 * (doc/specs/wildfly-support.md, Slice 1). One context, no redeploy or
 * reload lifecycle: {@link #detect()} is unconditionally {@code true}.
 *
 * <p>{@link #activate} binds one backend, chosen up front (doc/specs/plain-jvm-jul.md #1): if
 * Logback is on the system class path it installs level control for the single Logback
 * {@code LoggerContext} once SLF4J has bound to it, exactly as in 1.0; otherwise it waits for the
 * application to initialise {@code java.util.logging} and installs over that. Never both: the
 * agent's own MBean registration initialises JUL in every Logback application, so a JUL context
 * there would offer rules and {@code top} that never see a Logback event.
 */
public final class NoneContainerIntegration implements ContainerIntegration {

    /** Found by resource lookup, which loads no class and touches no JUL. */
    static final String LOGBACK_CONTEXT_RESOURCE = "ch/qos/logback/classic/LoggerContext.class";

    /** The JDK's property naming the {@code LogManager} subclass. */
    static final String JUL_MANAGER_PROPERTY = "java.util.logging.manager";

    private final Duration sweepInterval;
    private volatile NoneContainer host;
    /** Set when JUL was left out because {@code log4j-jul} fronts a Log4j 2; what installs it after all. */
    private volatile Runnable julFallback;
    private volatile boolean julFallbackStarted;
    /** The {@code LogManager} class that left JUL out, set with {@link #julFallback}. */
    private volatile String frontingManager;
    /** What the system class loader's detector could not bind before there was a fallback to run. */
    private final Set<String> notBoundBeforeFallback = new HashSet<>();

    public NoneContainerIntegration() {
        this(SweepPolicy.interval());
    }

    /** Test seam: a short expiry-sweep interval instead of the real 30s one. */
    NoneContainerIntegration(Duration sweepInterval) {
        this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval");
    }

    @Override
    public String id() {
        return "none";
    }

    @Override
    public boolean detect() {
        return true; // the fallback -- tried last, always matches
    }

    @Override
    public AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog, VendorDefaults vendorDefaults,
            boolean stormDetectionEnabled, Consumer<AggregateLevelControl> onFirstContextReady) {
        NoneContainer root = new NoneContainer(policy, auditLog, sweepInterval, vendorDefaults,
                stormDetectionEnabled);
        this.host = root;

        boolean logback = logbackOnClassPath(ClassLoader.getSystemClassLoader());
        Diagnostics.debug("none integration: binding " + (logback ? "Logback" : "java.util.logging")
                + " (Logback " + (logback ? "is" : "is not") + " on the system class path)");
        Supplier<LoggingAdapter> adapter = logback
                ? LogbackAdapterFactory::forCurrentContext
                : JulAdapterFactory::forPlainJvm;

        // Runs later, on the detector thread. Guard it: install failure must
        // degrade to a Diagnostics line, never an uncaught exception on that
        // thread (doc/specs/level-control.md "Failure handling").
        Runnable installSystemContext = () -> {
            try {
                root.installContext(ContextHandle.of(ContextHandle.SYSTEM, "none", adapter.get()));
                onFirstContextReady.accept(root.operations());
            } catch (Throwable t) {
                Diagnostics.error("LogAperture failed to install level control for the none container", t);
            }
        };
        if (logback) {
            LogbackLoadDetector.awaitLogbackAndThen(inst, installSystemContext);
        } else {
            waitForJul(inst, installSystemContext);
        }
        return root.operations();
    }

    /**
     * doc/specs/class-loader-detector.md C1: where JUL is only a front for a framework the detector binds, a second
     * context for it would list every logger twice. Decided now from the property, which touches no JUL, and again
     * on the manager in place once JUL is in use, since an application may set the property in its own code.
     */
    private void waitForJul(Instrumentation inst, Runnable installSystemContext) {
        // J4: if Log4j 2 then cannot be bound, the same JUL context goes in after all
        Runnable fallback = () -> JulLoadDetector.awaitJulThen(inst, installSystemContext);
        String configured = System.getProperty(JUL_MANAGER_PROPERTY);
        if (frontsABoundFramework(configured)) {
            Diagnostics.debug("none integration: java.util.logging is managed by " + configured
                    + "; leaving it to the detector");
            armJulFallback(configured, fallback);
            return;
        }
        Runnable decide = () -> {
            // only here, once something else has initialised JUL, may it be asked which manager it has
            String inPlace = java.util.logging.LogManager.getLogManager().getClass().getName();
            julInUse(inPlace, installSystemContext, fallback);
        };
        JulLoadDetector.awaitJulThen(inst, decide);
    }

    /**
     * JUL is in use under {@code manager}: install its context, unless the manager fronts a framework the detector
     * binds. Then only {@code fallback} is kept, and run at once if that framework has already failed to bind.
     * Package-visible for the test.
     */
    void julInUse(String manager, Runnable install, Runnable fallback) {
        if (!frontsABoundFramework(manager)) {
            install.run();
            return;
        }
        Diagnostics.debug("none integration: java.util.logging is managed by " + manager
                + "; leaving it to the detector");
        synchronized (this) {
            armJulFallback(manager, fallback);
            for (String framework : notBoundBeforeFallback) {
                if (frameworkNotBound(framework, ClassLoader.getSystemClassLoader())) {
                    break;
                }
            }
        }
    }

    private static boolean frontsABoundFramework(String manager) {
        return KnownFrameworks.julManagerFrontsABoundFramework(manager, KnownFrameworks.all());
    }

    /** Whether {@code manager} is the JUL manager of the watched {@code framework}. */
    private static boolean managerFronts(String manager, String framework) {
        return KnownFrameworks.all().stream().filter(watch -> watch.framework().equals(framework))
                .anyMatch(watch -> KnownFrameworks.julManagerFrontsABoundFramework(manager, List.of(watch)));
    }

    /** What {@link #frameworkNotBound} runs: set when JUL was left out for {@code log4j-jul}. Package-visible for the test. */
    synchronized void armJulFallback(String manager, Runnable install) {
        frontingManager = manager;
        julFallback = install;
    }

    /**
     * doc/specs/jul-fallback-on-log4j-bind-failure.md J2, J4: the Log4j 2 {@code log4j-jul} fronts is the one the
     * system class loader sees, so only its failure installs the {@code java.util.logging} context, once.
     */
    @Override
    public synchronized boolean frameworkNotBound(String framework, ClassLoader owner) {
        if (owner != ClassLoader.getSystemClassLoader()) {
            return false;
        }
        Runnable fallback = julFallback;
        if (fallback == null) {
            notBoundBeforeFallback.add(framework);
            return false;
        }
        if (!managerFronts(frontingManager, framework)) {
            return false;
        }
        if (!julFallbackStarted) {
            julFallbackStarted = true;
            Diagnostics.warn("LogAperture could not control " + framework + " (see above); controlling "
                    + "java.util.logging instead, which " + frontingManager + " fronts");
            fallback.run();
        }
        return true;
    }

    @Override
    public void adoptContext(ContextHandle context) {
        requireHost().adoptContext(context);
    }

    @Override
    public void releaseContext(String stableKey) {
        requireHost().releaseContext(stableKey);
    }

    private NoneContainer requireHost() {
        NoneContainer active = host;
        if (active == null) {
            throw new IllegalStateException("the none integration has not been activated");
        }
        return active;
    }

    /** doc/specs/plain-jvm-jul.md #1. Package-visible for the test. */
    static boolean logbackOnClassPath(ClassLoader loader) {
        return loader != null && loader.getResource(LOGBACK_CONTEXT_RESOURCE) != null;
    }
}
