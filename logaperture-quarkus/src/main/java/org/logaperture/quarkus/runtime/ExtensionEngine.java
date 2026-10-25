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
package org.logaperture.quarkus.runtime;

import io.quarkus.runtime.ShutdownContext;
import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.api.DoctorFinding;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.container.jbosslogmanager.JBossLogManagerContainer;
import org.logaperture.container.quarkus.QuarkusEngine;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.LibraryRecipeScanner;
import org.logaperture.core.StderrAuditLog;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.startup.EngineStartup;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The engine, started in-process (doc/specs/quarkus-extension.md): no readiness gate and no early
 * re-sweep, because the recorder runs after Quarkus has installed JBoss LogManager and activated its
 * handlers (spike finding 3). Fail-open: a failure is reported and the application's logging is left
 * alone.
 */
final class ExtensionEngine {

    private ExtensionEngine() {
    }

    static void start(LogApertureConfig settings, ShutdownContext shutdown) {
        // First, before any engine class reads its settings: Diagnostics reads its level once, when
        // it is first used.
        applyAsSystemProperties(settings);
        try {
            if (!EngineStartup.claim(EngineStartup.Delivery.EXTENSION)) {
                return; // an attach got in between; it owns the JVM
            }
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            AuditLog auditLog = new StderrAuditLog();
            VendorDefaults vendorDefaults = EngineStartup.loadVendorDefaults(settings.vendorDefaults());
            JBossLogManagerContainer host = QuarkusEngine.newHost(policy, auditLog, vendorDefaults,
                    settings.stormDetection(), SweepPolicy.interval(), ExtensionEngine::quarkusVersion);
            LibraryRecipeScanner recipes = LibraryRecipeScanner.forClassLoader(ExtensionEngine.class.getClassLoader());
            Consumer<AggregateLevelControl> onFirstContextReady = EngineStartup.onFirstContextReady(
                    QuarkusEngine.CONTAINER_ID, EngineStartup.Delivery.EXTENSION, policy, auditLog, vendorDefaults,
                    recipes);
            JulLoggingAdapter adapter = QuarkusEngine.installSystemContext(host, onFirstContextReady);
            Supplier<List<DoctorFinding>> containerFindings = () -> QuarkusEngine.formatterFindings(adapter);
            host.setContainerFindings(containerFindings);
            Runnable stopEngine = () -> stop(host, adapter);
            shutdown.addShutdownTask(stopEngine);
            Diagnostics.debug("level control installed (quarkus extension, system LogContext)");
        } catch (Throwable t) {
            Diagnostics.error("LogAperture failed to start in the Quarkus extension", t);
        }
    }

    /**
     * #4: on application shutdown -- every dev-mode restart, and the JVM's exit -- withdraw the control
     * surface, revert the session-only logger overrides (the next engine would not know them), stop
     * the sweeper and release the state file, then take LogAperture's filters and formatter wraps off
     * the handlers. The next start (in dev mode, in a new class loader) resumes the {@code for} and
     * {@code sticky} settings from the state file.
     */
    private static void stop(JBossLogManagerContainer host, JulLoggingAdapter adapter) {
        try {
            EngineStartup.withdrawControlSurface();
            host.operations().revertSessionOverrides();
            host.close();
            adapter.uninstall();
        } catch (Throwable t) {
            Diagnostics.warn("LogAperture did not stop cleanly", t);
        }
    }

    /**
     * #8: each {@code quarkus.logaperture.*} setting becomes the {@code -Dlogaperture.*} property the
     * engine reads, unless that property is already set on the command line, which wins.
     */
    static void applyAsSystemProperties(LogApertureConfig settings) {
        setIfAbsent("logaperture.home", settings.home());
        setIfAbsent("logaperture.instanceId", settings.instanceId());
        setIfAbsent("logaperture.recipes", settings.recipes());
        setIfAbsent("logaperture.audit.file", settings.audit().file());
        setIfAbsent("logaperture.diagnostics.level", settings.diagnostics().level());
        setIfAbsent("logaperture.drop.summaryInterval", settings.drop().summaryInterval());
        setIfAbsent("logaperture.followHandlers", settings.followHandlers().map(String::valueOf));
    }

    private static void setIfAbsent(String property, Optional<String> value) {
        value.ifPresent(v -> System.getProperties().putIfAbsent(property, v));
    }

    /** For {@code logctl env}: the {@code quarkus-core} jar's own version. */
    private static Optional<String> quarkusVersion() {
        Package quarkusCore = io.quarkus.runtime.Application.class.getPackage();
        return Optional.ofNullable(quarkusCore == null ? null : quarkusCore.getImplementationVersion());
    }
}
