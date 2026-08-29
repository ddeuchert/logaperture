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
package org.logaperture.container.wildfly;

import org.logaperture.adapter.jbosslogmanager.JbossLogManagerAdapterFactory;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.InstallGuidance;
import org.logaperture.core.spi.LoggingAdapter;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The {@link ContainerIntegration} for standalone WildFly — see
 * doc/specs/wildfly-support.md Slice 3. {@link #detect()} probes only
 * system properties and class presence (never {@code java.util.logging} —
 * the premain gotcha); {@link #activate} waits, off-thread and via a side
 * channel, until JBoss LogManager is genuinely installed, then binds one
 * {@link org.logaperture.adapter.jbosslogmanager.JbossLogManagerAdapter} to
 * the server's system {@code LogContext} and installs level control for it.
 *
 * <p>Standalone only. Domain mode is out of scope for v1 (§15.6): a
 * domain-mode launch is detected and declined.
 */
public final class WildFlyContainerIntegration implements ContainerIntegration {

    private static final String JBOSS_MODULES_CLASS = "org.jboss.modules.Module";
    private static final String DOMAIN_BASE_DIR_PROPERTY = "jboss.domain.base.dir";

    private final Duration sweepInterval;

    public WildFlyContainerIntegration() {
        this(WildFlyContainer.DEFAULT_SWEEP_INTERVAL);
    }

    /** Test seam: a short verification/expiry sweep interval. */
    WildFlyContainerIntegration(Duration sweepInterval) {
        this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval");
    }

    @Override
    public String id() {
        return "wildfly";
    }

    @Override
    public boolean detect() {
        boolean jbossLogManagerRequested =
                WildFlyLogManagerReadiness.JBOSS_LOG_MANAGER.equals(System.getProperty("java.util.logging.manager"));
        if (!jbossLogManagerRequested || !isClassPresent(JBOSS_MODULES_CLASS)) {
            return false; // not a JBoss-Modules server (Quarkus-JVM is a separate integration)
        }
        if (System.getProperty(DOMAIN_BASE_DIR_PROPERTY) != null) {
            Diagnostics.warn("LogAperture: WildFly domain mode is not supported (v1); level control not installed");
            return false;
        }
        return true;
    }

    @Override
    public AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog,
            Consumer<AggregateLevelControl> onFirstContextReady) {
        WildFlyContainer host = new WildFlyContainer(policy, auditLog, sweepInterval);

        Runnable install = () -> {
            try {
                LoggingAdapter adapter = JbossLogManagerAdapterFactory.forCurrentContext();
                host.installContext(ContextHandle.of(ContextHandle.SYSTEM, "wildfly", adapter));
                wireConfigurationListener(host);
                onFirstContextReady.accept(host.operations());
                Diagnostics.info("LogAperture level control installed (wildfly container, system LogContext)");
            } catch (Throwable t) {
                Diagnostics.error("LogAperture failed to install level control for WildFly", t);
            }
        };
        Thread detector = new Thread(
                () -> WildFlyLogManagerReadiness.awaitJBossLogManagerThen(install), "logaperture-wildfly-detect");
        detector.setDaemon(true);
        detector.start();

        return host.operations();
    }

    @Override
    public InstallGuidance guidance() {
        return new InstallGuidance(
                "Attach the agent in $WILDFLY_HOME/bin/standalone.conf",
                List.of("Add to standalone.conf: JAVA_OPTS=\"$JAVA_OPTS -javaagent:/path/to/logaperture-agent.jar\"",
                        "Restart WildFly in standalone mode",
                        "The agent's overrides live only in its own store and never touch standalone.xml"));
    }

    /**
     * Mechanism 1 (doc/specs/wildfly-support.md, "LogContext discovery and
     * lifecycle"): {@code org.jboss.logmanager.LogManager} exposes {@code
     * addConfigurationListener(Runnable)}, fired on {@code readConfiguration}
     * / {@code updateConfiguration} — which is the path a {@code
     * /subsystem=logging} change and an XML edit + {@code :reload} both take.
     * Register the verification sweep against it so a management change is
     * corrected promptly; the periodic sweep remains the floor.
     */
    private static void wireConfigurationListener(WildFlyContainer host) {
        java.util.logging.LogManager logManager = java.util.logging.LogManager.getLogManager();
        if (logManager instanceof org.jboss.logmanager.LogManager jbossLogManager) {
            jbossLogManager.addConfigurationListener(host::runVerificationSweepNow);
            Diagnostics.debug("LogAperture: registered a JBoss LogManager configuration-change listener");
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, WildFlyContainerIntegration.class.getClassLoader());
            return true;
        } catch (Throwable notPresent) {
            return false;
        }
    }
}
