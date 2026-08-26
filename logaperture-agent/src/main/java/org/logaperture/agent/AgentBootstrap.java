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
import org.logaperture.container.none.NoneContainer;
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.StderrAuditLog;

import java.lang.instrument.Instrumentation;

/**
 * Wires kill-switch &rarr; policy &rarr; audit sink &rarr; Logback-load
 * detector &rarr; on callback: {@link NoneContainer#install} &rarr; {@link
 * JmxRegistrar#register}. Every step is individually try/caught to {@link
 * Diagnostics} — install failure must never propagate into, or block
 * startup of, the target application (doc/specs/level-control.md "Failure
 * handling"; doc/logaperture-spec.md's "ordinary failure handling").
 */
final class AgentBootstrap {

    private static final String DISABLED_PROPERTY = "logaperture.disabled";

    private AgentBootstrap() {
    }

    static void start(Instrumentation inst) {
        if (Boolean.getBoolean(DISABLED_PROPERTY)) {
            return; // global kill switch, honoured without needing the control plane reachable
        }
        try {
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            AuditLog auditLog = new StderrAuditLog();
            LogbackLoadDetector.awaitLogbackAndThen(inst, () -> onLogbackReady(policy, auditLog));
        } catch (Throwable t) {
            Diagnostics.error("LogAperture agent bootstrap failed to start", t);
        }
    }

    private static void onLogbackReady(CapabilityPolicy policy, AuditLog auditLog) {
        try {
            // The returned Installation is deliberately never closed here --
            // its expiry-sweep thread is a daemon (never blocks JVM exit) and
            // its state-store lock is released by the OS at process exit,
            // per doc/specs/persistence.md.
            NoneContainer.Installation installation = NoneContainer.install(policy, auditLog);
            JmxRegistrar.register(installation.service());
            Diagnostics.info("LogAperture level control installed (none container, Logback adapter, JMX surface)");
        } catch (Throwable t) {
            Diagnostics.error("LogAperture failed to install level control", t);
        }
    }
}
