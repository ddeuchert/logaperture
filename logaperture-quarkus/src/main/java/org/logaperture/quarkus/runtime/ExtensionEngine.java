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

import java.util.ArrayList;
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
        start(settings, shutdown, Optional.empty());
    }

    /** @param bundled the text of the vendor defaults file bundled in the application, if any */
    static void start(LogApertureConfig settings, ShutdownContext shutdown, Optional<String> bundled) {
        // First, before any engine class reads its settings: Diagnostics reads its level once, when
        // it is first used.
        List<String> propertiesSet = applyAsSystemProperties(settings);
        boolean claimed = false;
        JBossLogManagerContainer host = null;
        JulLoggingAdapter adapter = null;
        try {
            if (!EngineStartup.claim(EngineStartup.Delivery.EXTENSION)) {
                clearProperties(propertiesSet);
                return; // an attach got in between; it owns the JVM
            }
            claimed = true;
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            AuditLog auditLog = new StderrAuditLog();
            VendorDefaults vendorDefaults = vendorDefaults(settings, bundled);
            host = QuarkusEngine.newHost(policy, auditLog, vendorDefaults,
                    settings.stormDetection(), SweepPolicy.interval(), ExtensionEngine::quarkusVersion);
            Consumer<AggregateLevelControl> onFirstContextReady = EngineStartup.onFirstContextReady(
                    QuarkusEngine.CONTAINER_ID, EngineStartup.Delivery.EXTENSION, policy, auditLog, vendorDefaults,
                    recipeScanner());
            Supplier<ClassLoader> appLoader = ExtensionEngine.class::getClassLoader;
            adapter = QuarkusEngine.installSystemContext(host, onFirstContextReady, appLoader);
            JulLoggingAdapter installed = adapter;
            Supplier<List<DoctorFinding>> containerFindings = () -> {
                List<DoctorFinding> findings = new java.util.ArrayList<>(QuarkusEngine.formatterFindings(installed));
                findings.addAll(QuarkusEngine.handlerNameFindings(installed));
                return findings;
            };
            host.setContainerFindings(containerFindings);
            JBossLogManagerContainer running = host;
            Runnable stopEngine = () -> stop(running, installed, propertiesSet);
            shutdown.addShutdownTask(stopEngine);
            Diagnostics.debug("level control installed (quarkus extension, system LogContext)");
        } catch (Throwable t) {
            Diagnostics.error("LogAperture failed to start in the Quarkus extension", t);
            // Leave nothing behind, so the next start -- a dev-mode reload -- begins clean instead of
            // standing down on this start's claim.
            if (claimed) {
                stop(host, adapter, propertiesSet);
            } else {
                clearProperties(propertiesSet);
            }
        }
    }

    /**
     * bundled-vendor-defaults.md #4, #5: an external file, if configured, replaces the bundled one;
     * otherwise the bundled one applies unless it is switched off.
     */
    static VendorDefaults vendorDefaults(LogApertureConfig settings, Optional<String> bundled) {
        if (settings.vendorDefaults().isPresent()) {
            if (bundled.isPresent()) {
                Diagnostics.info("the vendor defaults file " + settings.vendorDefaults().get()
                        + " replaces the one bundled in the application");
            }
            return EngineStartup.loadVendorDefaults(settings.vendorDefaults());
        }
        if (bundled.isPresent() && settings.bundledVendorDefaults()) {
            return EngineStartup.loadBundledVendorDefaults(bundled.get());
        }
        if (bundled.isPresent()) {
            Diagnostics.info("the vendor defaults file bundled in the application is switched off "
                    + "(quarkus.logaperture.bundled-vendor-defaults=false)");
        }
        return VendorDefaults.none();
    }

    /**
     * #4: on application shutdown -- every dev-mode restart, and the JVM's exit -- withdraw the control
     * surface and the claim, put every logger LogAperture changed back to its own level (session
     * overrides for good; {@code for}, {@code sticky} and vendor levels until the next engine resumes
     * them from the state file), stop the sweeper and release the state file, take LogAperture's
     * filters and formatter wraps off the handlers, and clear the {@code -Dlogaperture.*} properties
     * this start set, so the next start reads the configuration afresh. Each step runs even if an
     * earlier one fails: a held state-file lock would cost every later reload its persistence.
     *
     * @param host    {@code null} if start failed before the host was built
     * @param adapter {@code null} if start failed before the context was installed
     */
    private static void stop(JBossLogManagerContainer host, JulLoggingAdapter adapter, List<String> propertiesSet) {
        step("withdraw the control surface", EngineStartup::withdrawControlSurface);
        if (host != null) {
            step("restore the loggers' own levels", () -> host.operations().restoreNativeLevelsForStop());
            step("close the host", host::close);
        }
        if (adapter != null) {
            step("take the handler wraps off", adapter::uninstall);
        }
        clearProperties(propertiesSet);
    }

    private static void step(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Diagnostics.warn("LogAperture could not " + what + " while stopping", t);
        }
    }

    /**
     * #6: library recipes are found through the extension's own class loader and the application's
     * (the thread's context loader at runtime init). In a fast-jar they are the same loader; in dev
     * mode the application's own classes and its hot-reloadable modules are only in the second.
     */
    private static LibraryRecipeScanner recipeScanner() {
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(ExtensionEngine.class.getClassLoader());
        ClassLoader application = Thread.currentThread().getContextClassLoader();
        if (application != null) {
            loaders.add(application);
        }
        return LibraryRecipeScanner.forClassLoaders(loaders);
    }

    /**
     * #8: each {@code quarkus.logaperture.*} setting becomes the {@code -Dlogaperture.*} property the
     * engine reads, unless that property is already set on the command line, which wins.
     *
     * @return the properties this call set, for {@link #stop} to clear: a property left set would look
     *         like a command-line one to the next start (a dev-mode reload) and hide a changed setting
     */
    static List<String> applyAsSystemProperties(LogApertureConfig settings) {
        List<String> set = new ArrayList<>();
        setIfAbsent("logaperture.home", settings.home(), set);
        setIfAbsent("logaperture.instanceId", settings.instanceId(), set);
        setIfAbsent("logaperture.recipes", settings.recipes(), set);
        setIfAbsent("logaperture.audit.file", settings.audit().file(), set);
        setIfAbsent("logaperture.diagnostics.level", settings.diagnostics().level(), set);
        setIfAbsent("logaperture.drop.summaryInterval", settings.drop().summaryInterval(), set);
        setIfAbsent("logaperture.followHandlers", settings.followHandlers().map(String::valueOf), set);
        return List.copyOf(set);
    }

    private static void setIfAbsent(String property, Optional<String> value, List<String> set) {
        if (value.isPresent() && System.getProperties().putIfAbsent(property, value.get()) == null) {
            set.add(property);
        }
    }

    private static void clearProperties(List<String> properties) {
        properties.forEach(System::clearProperty);
    }

    /** For {@code logctl env}: the {@code quarkus-core} jar's own version. */
    private static Optional<String> quarkusVersion() {
        Package quarkusCore = io.quarkus.runtime.Application.class.getPackage();
        return Optional.ofNullable(quarkusCore == null ? null : quarkusCore.getImplementationVersion());
    }
}
