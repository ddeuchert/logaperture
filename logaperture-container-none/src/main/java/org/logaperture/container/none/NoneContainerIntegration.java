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

import org.logaperture.adapter.logback.LogbackAdapterFactory;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.Objects;

/**
 * The {@link ContainerIntegration} for a plain {@code java -jar} app — the
 * always-present fallback the agent uses when no real container is detected
 * (doc/specs/wildfly-support.md, Slice 1). One context, no redeploy or
 * reload lifecycle: {@link #detect()} is unconditionally {@code true} and
 * {@link #activate} installs level control for the single Logback
 * {@code LoggerContext} once SLF4J has bound to it.
 */
public final class NoneContainerIntegration implements ContainerIntegration {

    private final Duration sweepInterval;

    public NoneContainerIntegration() {
        this(NoneContainer.DEFAULT_SWEEP_INTERVAL);
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
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog, Runnable onFirstContextReady) {
        NoneContainer root = new NoneContainer(policy, auditLog, sweepInterval);
        LogbackLoadDetector.awaitLogbackAndThen(inst, () -> {
            root.installContext(ContextHandle.of(
                    ContextHandle.SYSTEM, "none", LogbackAdapterFactory.forCurrentContext()));
            onFirstContextReady.run();
        });
        return root.operations();
    }
}
