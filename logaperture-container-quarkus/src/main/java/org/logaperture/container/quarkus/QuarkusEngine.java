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
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;
import org.logaperture.container.jbosslogmanager.JBossLogManagerContainer;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContextHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Quarkus host, shared by both delivery routes (doc/specs/quarkus-extension.md #10): the agent's
 * {@link QuarkusContainerIntegration} calls it once its readiness gate opens, and the Quarkus
 * extension calls it from its runtime-init recorder, where JBoss LogManager is already installed
 * and Quarkus's handlers are active. Both must only call {@link #installSystemContext} once
 * {@code java.util.logging} is safe to touch.
 */
public final class QuarkusEngine {

    /** The display name {@code logctl env} shows, and the container id the startup line keys on. */
    public static final String CONTAINER_NAME = "Quarkus";
    public static final String CONTAINER_ID = "quarkus";

    private QuarkusEngine() {
    }

    /** The (still empty) host: builds nothing that touches {@code java.util.logging}. */
    public static JBossLogManagerContainer newHost(CapabilityPolicy policy, AuditLog auditLog,
            VendorDefaults vendorDefaults, boolean stormDetectionEnabled, Duration sweepInterval,
            Supplier<Optional<String>> quarkusVersion) {
        return new JBossLogManagerContainer(CONTAINER_NAME, policy, auditLog, sweepInterval, quarkusVersion,
                vendorDefaults, stormDetectionEnabled);
    }

    /**
     * Binds one {@link JulLoggingAdapter} to Quarkus's single logging context, installs level control
     * for it, and hands the aggregate to {@code onFirstContextReady}. Throws if the install fails;
     * the caller reports it.
     *
     * @return the adapter, for {@link #formatterFindings}
     */
    public static JulLoggingAdapter installSystemContext(JBossLogManagerContainer host,
            Consumer<AggregateLevelControl> onFirstContextReady) {
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(new QuarkusHandlerNameResolver());
        host.installContext(ContextHandle.of(ContextHandle.SYSTEM, CONTAINER_ID, adapter));
        onFirstContextReady.accept(host.operations());
        return adapter;
    }

    /** The {@code doctor} check id for a handler whose formatter was left alone. */
    public static final String FORMATTER_KEPT_CHECK = "handler.formatter-kept";

    /**
     * quarkus-extension.md #5: one {@code doctor} line per handler LogAperture did not wrap because
     * the handler requires its own formatter type -- in practice the Dev UI log stream, in dev mode.
     */
    public static List<DoctorFinding> formatterFindings(JulLoggingAdapter adapter) {
        List<DoctorFinding> findings = new ArrayList<>();
        for (String handlerClass : adapter.handlersLeftUnwrapped()) {
            String shortName = handlerClass.substring(handlerClass.lastIndexOf('.') + 1);
            findings.add(new DoctorFinding(FORMATTER_KEPT_CHECK, Severity.INFO, shortName,
                    shortName + " requires its own formatter, so LogAperture leaves it unwrapped: trim rules and "
                            + "top don't apply to what it writes.",
                    "Nothing to do. In dev mode this is Quarkus's Dev UI log stream.", null));
        }
        return findings;
    }
}
