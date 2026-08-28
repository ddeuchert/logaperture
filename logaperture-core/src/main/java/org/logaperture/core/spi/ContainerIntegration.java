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

import java.lang.instrument.Instrumentation;

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
     * backend is safe to touch. {@code onFirstContextReady} runs once, on
     * whatever thread completes the first context's install — the agent
     * uses it to publish its "control plane is up" marker only after there
     * is really something to control.
     *
     * @param inst                the agent's {@link Instrumentation}, for
     *                            class-load-time readiness detection
     * @param policy              the capability policy every mutation is checked against
     * @param auditLog            the sink every mutation and reversion is recorded to
     * @param onFirstContextReady run once, after the first context is installed
     */
    AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog, Runnable onFirstContextReady);

    /** Where the {@code -javaagent} flag goes, for diagnostics and help. Default {@link InstallGuidance#NONE}. */
    default InstallGuidance guidance() {
        return InstallGuidance.NONE;
    }
}
