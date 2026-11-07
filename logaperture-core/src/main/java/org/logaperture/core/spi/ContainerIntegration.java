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
package org.logaperture.core.spi;

import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.VendorDefaults;

import java.lang.instrument.Instrumentation;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One container the agent knows how to bring logging under control for —
 * see doc/specs/wildfly-support.md, "The {@code ContainerIntegration} SPI"
 * (and doc/logaperture-spec.md §15.2). Slice 1 ships exactly one
 * implementation, {@code none} (a plain {@code java -jar} app); Slice 3
 * adds {@code wildfly}.
 *
 * <p>The agent builds every available integration, picks the first whose
 * {@link #detect()} returns true ({@code none} is the always-true fallback,
 * tried last), and calls {@link #activate}. The chosen integration owns
 * everything from there: waiting until its logging backend is safe to
 * touch, discovering every logging context, installing level control per
 * context, wiring any redeploy/reload lifecycle, and running the expiry
 * sweep. It hands back one {@link AggregateLevelControl} — the surface
 * every control plane (JMX now) binds to.
 */
public interface ContainerIntegration {

    /** Stable id, e.g. {@code "none"}, {@code "wildfly"}. Used in diagnostics and, later, {@code doctor} output. */
    String id();

    /**
     * Is this container present? Resource- and class-presence probing only
     * — never speculative class loading (doc/logaperture-spec.md §15.2, and
     * §15.6's premain gotcha). Must be cheap and side-effect-free: it is
     * called on every registered integration at startup.
     */
    boolean detect();

    /**
     * Bring this container's logging under control. Returns promptly with
     * the (initially empty) {@link AggregateLevelControl}; the actual
     * discovery and per-context install complete asynchronously, once the
     * backend is safe to touch. {@code onFirstContextReady} is handed the
     * aggregate once, on whatever thread completes the first context's
     * install — the agent uses it to register the JMX surface and publish its
     * "control plane is up" marker only after there is really something to
     * control. It is the same instance this method returns; the callback
     * exists so the agent never has to race the return value against the
     * async install.
     *
     * @param inst                the agent's {@link Instrumentation}, for
     *                            class-load-time readiness detection
     * @param policy              the capability policy every mutation is checked against
     * @param auditLog            the sink every mutation and reversion is recorded to
     * @param onFirstContextReady handed the aggregate once the first context is installed
     */
    default AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog,
            Consumer<AggregateLevelControl> onFirstContextReady) {
        return activate(inst, policy, auditLog, VendorDefaults.none(), onFirstContextReady);
    }

    /**
     * {@link #activate(Instrumentation, CapabilityPolicy, AuditLog, Consumer)}, plus the vendor
     * defaults file the agent parsed at {@code premain} (doc/specs/vendor-defaults.md "The
     * baseline layer") -- applied by every context this integration installs, and reported by
     * {@code logctl status}/{@code env}/{@code doctor}. Storm detection starts disabled, its
     * default (doc/specs/storm-detection-toggle.md T1).
     */
    default AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog, VendorDefaults vendorDefaults,
            Consumer<AggregateLevelControl> onFirstContextReady) {
        return activate(inst, policy, auditLog, vendorDefaults, false, onFirstContextReady);
    }

    /**
     * The full form: the vendor defaults file, and whether storm detection starts enabled -- the
     * agent's {@code --storm-detection=on|off} argument (doc/specs/storm-detection-toggle.md T2).
     * The integration builds the agent's one storm-detection switch from it and hands it to every
     * context it installs.
     */
    AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog, VendorDefaults vendorDefaults,
            boolean stormDetectionEnabled, Consumer<AggregateLevelControl> onFirstContextReady);

    /**
     * Installs level control for a context that appeared <em>after</em> {@link #activate}: one the
     * class-loader detector bound in a loader of its own -- a Spring Boot fat jar, a Tomcat webapp, a
     * WildFly deployment with a framework of its own (doc/specs/class-loader-detector.md, C6).
     * Same install as the container's own context, except that every override already in force is
     * re-broadcast onto it. Never called before {@link #activate} returned.
     *
     * <p>The default does nothing, so an integration that has no host to install into simply does
     * not offer these contexts.
     */
    default void adoptContext(ContextHandle context) {
    }

    /** Drops a context {@link #adoptContext} installed; unknown keys are ignored. */
    default void releaseContext(String stableKey) {
    }

    /**
     * The class-loader detector found {@code framework} in {@code owner} and ended without binding it (too old,
     * failed, or no started context in time). An integration that can control the same application another way
     * may do so (doc/specs/jul-fallback-on-log4j-bind-failure.md, J3, J4): {@code none} installs
     * {@code java.util.logging} when it left it out because {@code log4j-jul} fronts the system Log4j 2.
     * Returns whether it did (or already had). The default does nothing.
     */
    default boolean frameworkNotBound(String framework, ClassLoader owner) {
        return false;
    }

    /** Where the {@code -javaagent} flag goes, for diagnostics and help. Default {@link InstallGuidance#NONE}. */
    default InstallGuidance guidance() {
        return InstallGuidance.NONE;
    }

    /**
     * Best-effort version of the detected container/framework itself — not
     * the logging backend it routes through, see {@link
     * org.logaperture.core.spi.LoggingAdapter#backendInfo()} for that — e.g.
     * {@code "34.0.1.Final"} for WildFly. For {@code logctl env}
     * (doc/specs/environment-report.md "Adapter / container SPI"); {@link
     * #id()} already supplies the name. Default empty.
     */
    default Optional<String> version() {
        return Optional.empty();
    }
}
