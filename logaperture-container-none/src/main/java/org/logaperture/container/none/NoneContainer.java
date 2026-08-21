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
import org.logaperture.core.AuditLog;
import org.logaperture.core.BaselineRegistry;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.LevelControlService;
import org.logaperture.core.OverrideRegistry;
import org.logaperture.core.spi.LoggingAdapter;

/**
 * Composition root for the plain {@code java -jar} container — "the
 * baseline, built first" (doc/logaperture-spec.md §4.6). No server-specific
 * discovery needed: this class is the entire module.
 */
public final class NoneContainer {

    private NoneContainer() {
    }

    /**
     * Builds the Logback adapter, eagerly walks and captures baseline for
     * every currently-known logger (doc/specs/level-control.md: baseline
     * capture must happen before any override, "without this, reset is
     * undefined"), and returns a ready-to-use {@link LevelControlService}.
     *
     * @throws org.logaperture.adapter.logback.AdapterInstallException if
     *         SLF4J isn't bound to a Logback {@code LoggerContext}
     */
    public static LevelControlService install(CapabilityPolicy policy, AuditLog auditLog) {
        LoggingAdapter adapter = LogbackAdapterFactory.forCurrentContext();

        BaselineRegistry baselines = new BaselineRegistry();
        for (String name : adapter.knownLoggerNames()) {
            baselines.captureIfAbsent(name, adapter);
        }

        OverrideRegistry overrides = new OverrideRegistry();
        return new LevelControlService(adapter, baselines, overrides, policy, auditLog, principal(), "jmx");
    }

    /**
     * §9.7's "principal (JVM UID, since JMX auth is the JVM's own)" for
     * this slice — the JVM's own account name, matching the audit-trail
     * field this feeds.
     */
    private static String principal() {
        return System.getProperty("user.name", "unknown");
    }
}
