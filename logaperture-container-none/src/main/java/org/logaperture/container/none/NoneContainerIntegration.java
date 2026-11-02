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
import java.util.Objects;
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
        } else if (KnownFrameworks.julManagerFrontsABoundFramework(
                System.getProperty(JUL_MANAGER_PROPERTY), KnownFrameworks.all())) {
            // doc/specs/class-loader-detector.md C1: JUL is only a front for a framework the detector
            // binds, and a second context for it would list every logger twice. Read as a property,
            // which touches no JUL.
            Diagnostics.debug("none integration: java.util.logging is managed by "
                    + System.getProperty(JUL_MANAGER_PROPERTY) + "; leaving it to the detector");
        } else {
            JulLoadDetector.awaitJulThen(inst, installSystemContext);
        }
        return root.operations();
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
