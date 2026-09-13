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

import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.InstallGuidance;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@link ContainerIntegration} for standalone WildFly — see
 * doc/specs/wildfly-support.md Slice 3. {@link #detect()} probes only
 * system properties and class presence (never {@code java.util.logging} —
 * the premain gotcha); {@link #activate} waits, off-thread and via a side
 * channel, until JBoss LogManager is genuinely installed, then binds one
 * {@link org.logaperture.adapter.jul.JulLoggingAdapter} to
 * the server's system {@code LogContext} and installs level control for it.
 *
 * <p>Standalone only. Domain mode is out of scope for v1 (§15.6): a
 * domain-mode launch is detected and declined.
 */
public final class WildFlyContainerIntegration implements ContainerIntegration {

    private static final String JBOSS_MODULES_CLASS = "org.jboss.modules.Module";
    private static final String DOMAIN_BASE_DIR_PROPERTY = "jboss.domain.base.dir";
    /** Pulls "34.0.1.Final" out of a Galleon feature-pack location like {@code "...:current#34.0.1.Final"}. */
    private static final Pattern FEATURE_PACK_VERSION = Pattern.compile("current#([^\"\\s]+)");
    private static final String JBOSS_HOME_PROPERTY = "jboss.home.dir";

    private final Duration sweepInterval;

    public WildFlyContainerIntegration() {
        this(SweepPolicy.interval());
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
        // Runs at premain, before jboss-modules programmatically sets
        // java.util.logging.manager -- so that property is NOT a usable
        // signal here (it is checked later, in WildFlyLogManagerReadiness).
        // Never touch java.util.logging. Use only cmdline -D properties and
        // class presence: jboss-modules on the system classpath + a
        // jboss.home.dir / an org.jboss.as.* main class.
        String launchCommand = String.valueOf(System.getProperty("sun.java.command"));
        boolean jbossModulesServer = isClassPresent(JBOSS_MODULES_CLASS)
                && (System.getProperty(JBOSS_HOME_PROPERTY) != null || launchCommand.contains("org.jboss.as."));
        if (!jbossModulesServer) {
            return false; // not a JBoss-Modules server (Quarkus-JVM is a separate integration)
        }
        if (System.getProperty(DOMAIN_BASE_DIR_PROPERTY) != null
                || launchCommand.contains("org.jboss.as.host-controller")
                || launchCommand.contains("org.jboss.as.process-controller")) {
            Diagnostics.warn("LogAperture: WildFly domain mode is not supported (v1); level control not installed");
            return false;
        }
        return true;
    }

    @Override
    public AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog,
            Consumer<AggregateLevelControl> onFirstContextReady) {
        // this::version, not version().orElse(null) -- see version()'s javadoc.
        // jboss.home.dir is not yet visible to System.getProperty at this
        // (premain) point in every real launch tried, so calling version()
        // here bakes in "no version" permanently. Deferred, it is re-resolved
        // fresh whenever logctl env actually runs, long after WildFly's own
        // bootstrap has set it.
        WildFlyContainer host = new WildFlyContainer(policy, auditLog, sweepInterval, this::version);

        Runnable install = () -> {
            try {
                JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(new WildFlyHandlerNameResolver());
                host.installContext(ContextHandle.of(ContextHandle.SYSTEM, "wildfly", adapter));
                wireConfigurationListener(host, adapter);
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

    /**
     * doc/specs/environment-report.md Decision #3, revised after real-WildFly
     * feedback: the original design assumed {@code $JBOSS_HOME/version.txt};
     * confirmed against two real images that no such file exists in either
     * (issue: a user got no version at all). WildFly's actual on-disk layout
     * for its own version changed between major versions instead, so this
     * tries two real, pure-file-read sources, in order:
     *
     * <ol>
     *   <li>{@link #versionFromGalleonProvisioning} — Galleon-provisioned
     *       installs (the quay.io images from WildFly 27 on).</li>
     *   <li>{@link #versionFromProductManifest} — older, classic installs
     *       (pre-Galleon) that instead carry a product manifest.</li>
     * </ol>
     *
     * Confirmed against real WildFly 26.1.3.Final (classic manifest, no
     * {@code .galleon} directory) and 34.0.1.Final (Galleon layout, no
     * product manifest) — each image has exactly one of the two, never
     * both. Pure file I/O either way — never touches {@code
     * java.util.logging}, so safe to call at premain time as far as the
     * premain gotcha (class doc) goes.
     *
     * <p><b>But do not call this from {@link #activate} itself</b> — a second
     * real-WildFly finding, past the file-layout one above: {@code
     * jboss.home.dir} is not a genuine JVM launcher {@code -D} property in
     * every real launch configuration observed. It is present in the final
     * process's {@code /proc/<pid>/cmdline} (and so looks like a normal
     * command-line flag), but under this project's own dev/IT launch
     * command (WildFly's {@code standalone.sh} constructing one {@code java}
     * invocation), it reads back {@code null} from {@code
     * System.getProperty} at premain time and only becomes visible once
     * {@code org.jboss.modules.Main} / {@code org.jboss.as}'s own bootstrap
     * has run far enough to set it programmatically — strictly after
     * premain, which runs before any application {@code main()}. {@link
     * #detect()} tolerates this because {@code jboss.home.dir} is only one
     * of its two disjuncts (the {@code sun.java.command} match is what
     * actually fires here); {@code version()} has no such fallback, so
     * calling it eagerly at {@link #activate} time silently produced no
     * container version at all against every real launch tried — best-effort
     * degraded all the way to empty, exactly as designed, just too early to
     * ever see anything. Call it lazily instead, no earlier than {@code
     * logctl env} actually running (by which point the server has long
     * finished booting) — see {@link #activate}'s comment.
     */
    @Override
    public Optional<String> version() {
        String jbossHome = System.getProperty(JBOSS_HOME_PROPERTY);
        if (jbossHome == null) {
            return Optional.empty();
        }
        Path home = Path.of(jbossHome);
        Optional<String> galleon = versionFromGalleonProvisioning(home);
        return galleon.isPresent() ? galleon : versionFromProductManifest(home);
    }

    /**
     * {@code $JBOSS_HOME/.galleon/provisioning.xml} names every resolved
     * feature-pack as {@code <name>@maven(...):<channel>#<version>}, e.g.
     * {@code "wildfly@maven(org.jboss.universe:community-universe):current#34.0.1.Final"}.
     * Every feature-pack in a coherent WildFly build shares one release
     * version, so the first {@code #<version>} found is taken — no XML
     * parser needed for one substring pull out of an attribute value.
     * Package-visible so a test can lock in this parsing against a real
     * {@code provisioning.xml} sample without a real WildFly.
     */
    static Optional<String> versionFromGalleonProvisioning(Path jbossHome) {
        Path provisioningXml = jbossHome.resolve(".galleon").resolve("provisioning.xml");
        try {
            Matcher match = FEATURE_PACK_VERSION.matcher(Files.readString(provisioningXml));
            return match.find() ? Optional.of(match.group(1)) : Optional.empty();
        } catch (IOException | RuntimeException notReadable) {
            return Optional.empty();
        }
    }

    /**
     * The classic (pre-Galleon) product manifest at {@code
     * $JBOSS_HOME/modules/system/layers/base/org/jboss/as/product/main/dir/
     * META-INF/MANIFEST.MF} — the same file WildFly's own {@code
     * org.jboss.as.version.ProductConfig} reads to print its boot banner
     * ("WildFly Full 26.1.3.Final ... starting"). Package-visible so a test
     * can lock in this parsing against a real manifest sample without a
     * real WildFly.
     */
    static Optional<String> versionFromProductManifest(Path jbossHome) {
        Path manifestPath = jbossHome.resolve("modules").resolve("system").resolve("layers").resolve("base")
                .resolve("org").resolve("jboss").resolve("as").resolve("product").resolve("main").resolve("dir")
                .resolve("META-INF").resolve("MANIFEST.MF");
        try (var in = Files.newInputStream(manifestPath)) {
            String version = new java.util.jar.Manifest(in).getMainAttributes()
                    .getValue("JBoss-Product-Release-Version");
            return Optional.ofNullable(version);
        } catch (IOException | RuntimeException notReadable) {
            return Optional.empty();
        }
    }

    @Override
    public InstallGuidance guidance() {
        return new InstallGuidance(
                "Attach the agent in $JBOSS_HOME/bin/standalone.conf (standalone mode only)",
                List.of(
                        "Append one line to standalone.conf: "
                                + "JAVA_OPTS=\"$JAVA_OPTS -javaagent:/path/to/logaperture-agent.jar\"",
                        "Restart WildFly. The agent controls logging through java.util.logging (which JBoss "
                                + "LogManager backs), so nothing else is needed.",
                        "The agent's overrides live only in its own store and never touch standalone.xml."));
    }

    /**
     * Mechanism 1 (doc/specs/wildfly-support.md): JBoss LogManager exposes
     * {@code addConfigurationListener(Runnable)}, fired on {@code
     * readConfiguration} / {@code updateConfiguration} — the path a {@code
     * /subsystem=logging} change and an XML edit + {@code :reload} both take.
     * Wired reflectively (no compile-time reference to {@code
     * org.jboss.logmanager}); absent that method, the periodic verification
     * sweep is the only re-apply mechanism.
     *
     * <p>The callback first drops {@code adapter}'s cached handler-name
     * resolution (issue #14) so a renamed or newly-added {@code
     * /subsystem=logging} handler is re-resolved, then runs the verification
     * sweep that re-applies any override the change overwrote.
     */
    private static void wireConfigurationListener(WildFlyContainer host, JulLoggingAdapter adapter) {
        Runnable onConfigChange = () -> {
            adapter.invalidateNameCache();
            host.runVerificationSweepNow();
        };
        if (registerConfigurationListener(java.util.logging.LogManager.getLogManager(), onConfigChange)) {
            Diagnostics.debug("LogAperture: registered a JBoss LogManager configuration-change listener");
        } else {
            Diagnostics.debug("LogAperture: no JBoss LogManager configuration-change hook; "
                    + "relying on the periodic verification sweep");
        }
    }

    /**
     * Reflectively registers {@code callback} on {@code
     * org.jboss.logmanager.LogManager.addConfigurationListener(Runnable)}.
     * Package-visible so a test can lock in that API assumption against a
     * JBoss LogManager version bump.
     *
     * @return {@code true} if the listener was registered
     */
    static boolean registerConfigurationListener(java.util.logging.LogManager logManager, Runnable callback) {
        if (!"org.jboss.logmanager.LogManager".equals(logManager.getClass().getName())) {
            return false;
        }
        try {
            logManager.getClass()
                    .getMethod("addConfigurationListener", Runnable.class)
                    .invoke(logManager, callback);
            return true;
        } catch (ReflectiveOperationException | RuntimeException noHook) {
            Diagnostics.debug("LogAperture: addConfigurationListener not available (" + noHook + ")");
            return false;
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
