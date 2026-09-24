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

import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.ActiveLoggerFloor;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AggregateLevelControl.ContextControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.BaselineRegistry;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.DefaultHandlerGroupRegistry;
import org.logaperture.core.DoctorService;
import org.logaperture.core.EnvironmentReportService;
import org.logaperture.core.FileStateStore;
import org.logaperture.core.HandlerBaselineRegistry;
import org.logaperture.core.HandlerInstallPolicy;
import org.logaperture.core.HandlerLevelControlService;
import org.logaperture.core.HandlerOverrideRegistry;
import org.logaperture.core.LevelControlService;
import org.logaperture.core.LoggerOverrideChangeListener;
import org.logaperture.core.OverrideRegistry;
import org.logaperture.core.RuleService;
import org.logaperture.core.StormService;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.TopService;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Composition root for standalone WildFly — see doc/specs/wildfly-support.md
 * Slice 3. Structurally parallels {@code NoneContainer}: it owns one
 * {@link AggregateLevelControl}, the shared {@link StateStore}, and the
 * single sweep thread, and installs level control per context via
 * {@link #installContext}. For a stock standalone WildFly with no
 * {@code use-deployment-logging-config} and no {@code <logging-profile>}
 * there is exactly one context — the server's own system {@code LogContext},
 * which every deployment's loggers route to as well (the M0 finding).
 *
 * <p>The sweep thread does two jobs each tick: expire timed overrides, then
 * run the verification sweep (§15.5) that re-applies any override a
 * {@code /subsystem=logging} change or an XML edit + {@code :reload}
 * silently overwrote — JBoss LogManager has no reconfiguration event of its
 * own (§4.3). A {@code LogManager} configuration-change listener drives the
 * same verification sweep immediately when it can (see
 * {@code WildFlyContainerIntegration}); the periodic sweep is the floor.
 *
 * <p>NOTE: the state-store / sweeper / {@code installContext} / {@code
 * close} machinery is duplicated from {@code NoneContainer}. Extract a
 * shared host if a third container integration lands.
 */
public final class WildFlyContainer implements AutoCloseable {

    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final StateStore stateStore;
    private final AggregateLevelControl aggregate;
    private final ScheduledExecutorService sweeper;
    private final Duration handlerInstallDelay;
    private final Clock clock;
    private final VendorDefaults vendorDefaults;
    /** Set once, on the first {@link #installContext}; {@code null} until then (nothing to install yet). */
    private volatile Instant handlerInstallNotBefore;
    private final AtomicBoolean handlerInstallAnnounced = new AtomicBoolean();
    /** The one-shot that runs phase 2 at the floor; cancelled on close so a pending delay never holds shutdown up. */
    private volatile ScheduledFuture<?> handlerInstallTask;

    /** This class only ever represents WildFly, so {@link AggregateLevelControl}'s container name is always {@code "WildFly"} -- never left null by a constructor that doesn't happen to know a version. */
    private static final String CONTAINER_NAME = "WildFly";

    public WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog) {
        this(policy, auditLog, SweepPolicy.interval());
    }

    /** Package-visible so tests can use a short sweep interval instead of the real 30s one; no known WildFly version. */
    WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval) {
        this(policy, auditLog, sweepInterval, Optional::empty);
    }

    /**
     * @param containerVersion best-effort WildFly version for {@code logctl
     *                         env} (doc/specs/environment-report.md) --
     *                         a <em>supplier</em>, re-invoked fresh on every
     *                         {@code environmentReport()} call, not resolved
     *                         once here. {@code
     *                         WildFlyContainerIntegration.version()}'s own
     *                         javadoc explains why: at the point this
     *                         constructor runs (premain time), {@code
     *                         jboss.home.dir} is not yet visible to {@code
     *                         System.getProperty} in every real launch
     *                         tried -- resolving eagerly here silently bakes
     *                         in "no version" forever. Deferring costs
     *                         nothing (it's plain file I/O, not touched from
     *                         any hot path) and self-heals once the server
     *                         has finished its own bootstrap.
     */
    WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval,
            Supplier<Optional<String>> containerVersion) {
        this(policy, auditLog, sweepInterval, containerVersion, VendorDefaults.none());
    }

    /**
     * @param vendorDefaults applied by every {@link #installContext} -- doc/specs/vendor-defaults.md
     */
    WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval,
            Supplier<Optional<String>> containerVersion, VendorDefaults vendorDefaults) {
        this(policy, auditLog, sweepInterval, containerVersion, HandlerInstallPolicy.delay(), Clock.systemUTC(),
                vendorDefaults);
    }

    /**
     * @param handlerInstallDelay how long after the first context installs to hold back the
     *                            handler-level installs; see {@link HandlerInstallPolicy}
     * @param clock               the floor's time source -- injectable so tests can move
     *                            past it without waiting
     */
    WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval,
            Supplier<Optional<String>> containerVersion, Duration handlerInstallDelay, Clock clock) {
        this(policy, auditLog, sweepInterval, containerVersion, handlerInstallDelay, clock, VendorDefaults.none());
    }

    WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval,
            Supplier<Optional<String>> containerVersion, Duration handlerInstallDelay, Clock clock,
            VendorDefaults vendorDefaults) {
        this.policy = policy;
        this.vendorDefaults = Objects.requireNonNull(vendorDefaults, "vendorDefaults");
        this.auditLog = auditLog;
        this.handlerInstallDelay = handlerInstallDelay;
        this.clock = clock;
        this.stateStore = openStateStore();
        this.aggregate = new AggregateLevelControl(CONTAINER_NAME, containerVersion,
                stateStore.location().map(Path::toString).orElse(null), this::handlerInstallAllowed, vendorDefaults);

        this.sweeper = Executors.newSingleThreadScheduledExecutor(WildFlyContainer::newDaemonThread);
        long intervalMillis = sweepInterval.toMillis();
        sweeper.scheduleAtFixedRate(this::sweepTick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** The surface a control plane (JMX) binds to. */
    public AggregateLevelControl operations() {
        return aggregate;
    }

    /**
     * Builds, wires, and registers level control for one logging context:
     * eager baseline capture, resume of this JVM's persisted state, then
     * {@link AggregateLevelControl#register}. No adapter reset wiring —
     * JBoss LogManager has no reset event; the verification sweep covers it.
     */
    public void installContext(ContextHandle handle) {
        startHandlerInstallFloor();
        LoggingAdapter adapter = handle.adapter();

        BaselineRegistry baselines = new BaselineRegistry(vendorDefaults.loggerLevels());
        for (String name : adapter.knownLoggerNames()) {
            baselines.captureIfAbsent(name, adapter);
        }

        // Handler service first, so a LoggerOverrideChangeListener closing
        // over it can be built before LevelControlService needs one --
        // doc/specs/handler-floor-control.md "AUTO handler level",
        // "Recompute trigger" (this container is the composition root that
        // wires the two services together).
        OverrideRegistry overrides = new OverrideRegistry();
        ActiveLoggerFloor activeLoggerFloor = () -> List.copyOf(overrides.all().values());
        HandlerLevelControlService handlerService = new HandlerLevelControlService(adapter,
                new HandlerBaselineRegistry(vendorDefaults.handlers()), new HandlerOverrideRegistry(),
                new DefaultHandlerGroupRegistry(vendorDefaults.defaultHandlers().orElse(List.of())),
                policy, auditLog, stateStore, principal(), "jmx", activeLoggerFloor);
        // doc/specs/handler-floor-control.md "Resume resilience and
        // baseline-key migration" (issue #29): keep the baseline/override
        // registries in step with JulLoggingAdapter's own token->friendly-name
        // promotion, the one case a HandlerRef changes after minting.
        adapter.onHandlerRenamed(handlerService::migrateHandlerRef);

        LoggerOverrideChangeListener autoRecomputeListener = handlerService::recomputeAuto;
        LevelControlService service = new LevelControlService(
                adapter, baselines, overrides, policy, auditLog, stateStore, principal(), "jmx",
                autoRecomputeListener);
        RuleService ruleService = new RuleService(adapter, policy, auditLog, stateStore, handle.stableKey(),
                principal(), "jmx");
        // doc/specs/drop-rule.md "Persistence" -- see NoneContainer's identical call.
        ruleService.registerDropSupport();
        // doc/specs/trim-rule.md "Persistence" -- same primitive, for a persisted Trim.
        ruleService.registerTrimSupport();

        // doc/specs/vendor-defaults.md "Install order" -- see NoneContainer's identical call.
        service.applyVendorDefaults(Instant.now());
        handlerService.applyVendorDefaults(Instant.now());
        ruleService.attachVendorRules(vendorDefaults.rules(), Instant.now());

        try {
            service.resumeFromStateStore(Instant.now());
            handlerService.resumeFromStateStore(Instant.now());
            // doc/specs/drop-rule.md/trim-rule.md "Persistence" -- a persisted STICKY/unexpired-FOR
            // Drop or Trim now resumes as a live rule (both factories were registered just above).
            ruleService.resumeFromStateStore(Instant.now());
            // doc/specs/handler-floor-control.md "AUTO handler level", AUTO-5.
            handlerService.recomputeAuto();
        } catch (RuntimeException e) {
            Diagnostics.warn("LogAperture: failed to resume persisted overrides, continuing without them", e);
        }

        DoctorService doctorService = new DoctorService(adapter, policy);
        EnvironmentReportService environmentReportService = new EnvironmentReportService(adapter, policy);

        // Phase 1 ends here (doc/specs/wildfly-deferred-handler-install.md "Two phases"): the
        // services below are constructed, not started. Trim rendering, top's byte counting, storm
        // detection and the rule pipeline all put a filter or formatter on a handler, and doing
        // that on the boot handlers before jboss-modules has started aborted a real launch -- so
        // they run as phase 2 (AggregateLevelControl.installHandlerLevel), after the floor.
        TopService topService = new TopService(adapter, policy);
        StormService stormService = new StormService(adapter, policy);

        aggregate.register(new ContextControl(handle, service, handlerService, doctorService, topService,
                stormService, ruleService, environmentReportService));

        // With no deferral configured (delay 0) this installs immediately, as before this change.
        installHandlerLevelNow();
    }

    /**
     * Phase 2: puts the handler-level installs in place if the floor has passed, otherwise does
     * nothing (the one-shot task, a sweep tick or the configuration listener will get there).
     * Never throws.
     */
    void installHandlerLevelNow() {
        try {
            aggregate.installHandlerLevel();
        } catch (RuntimeException e) {
            Diagnostics.warn("LogAperture: handler-level install failed, will retry on the next sweep", e);
        }
        announceHandlerLevelIfInstalled();
    }

    /**
     * The one-shot. It is timed on the monotonic clock while the floor is checked on the wall
     * clock, so a wall-clock step or slew can wake it fractionally early; if the gate is still
     * closed, try again for the time that is left instead of waiting for the next sweep tick.
     */
    private void runScheduledHandlerInstall() {
        installHandlerLevelNow();
        Instant notBefore = handlerInstallNotBefore;
        if (notBefore == null || handlerInstallAllowed()) {
            return;
        }
        long remainingMillis = Math.max(50, Duration.between(clock.instant(), notBefore).toMillis());
        try {
            handlerInstallTask = sweeper.schedule(
                    this::runScheduledHandlerInstall, remainingMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException alreadyShutDown) {
            // close() won -- nothing to do
        }
    }

    /** The floor: no handler-level install before {@code handlerInstallNotBefore}. */
    private boolean handlerInstallAllowed() {
        Instant notBefore = handlerInstallNotBefore;
        return notBefore != null && !clock.instant().isBefore(notBefore);
    }

    /**
     * Starts the floor the first time a context is installed -- "after the readiness gate
     * passes" (doc/specs/wildfly-deferred-handler-install.md D2): the gate is what invokes
     * {@code installContext}. Also schedules the one-shot that makes time-to-effect
     * deterministic (D1); the sweep tick and the configuration listener are backstops.
     */
    private synchronized void startHandlerInstallFloor() {
        if (handlerInstallNotBefore != null) {
            return;
        }
        handlerInstallNotBefore = clock.instant().plus(handlerInstallDelay);
        if (handlerInstallDelay.isZero()) {
            return;
        }
        Diagnostics.info("LogAperture: handler-level install deferred for " + handlerInstallDelay.toSeconds() + "s");
        try {
            handlerInstallTask = sweeper.schedule(
                    this::runScheduledHandlerInstall, handlerInstallDelay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException alreadyShutDown) {
            // close() won -- nothing to do
        }
    }

    /** Logs, once, that phase 2 has run -- but only when it was actually deferred. */
    private void announceHandlerLevelIfInstalled() {
        if (handlerInstallDelay.isZero() || !aggregate.isHandlerLevelInstalled()
                || !handlerInstallAnnounced.compareAndSet(false, true)) {
            return;
        }
        int handlers;
        try {
            handlers = aggregate.listHandlers().size();
        } catch (RuntimeException e) {
            handlers = -1;
        }
        Diagnostics.info("LogAperture: handler-level install complete"
                + (handlers >= 0 ? " (" + handlers + " handlers)" : ""));
    }

    /**
     * Runs the verification sweep off the sweep thread, now. Called from the
     * {@code LogManager} configuration-change listener so a management change
     * is corrected promptly rather than on the next periodic tick. Submitted
     * (not run inline) so WildFly's own configuration thread is never
     * blocked on our work.
     */
    void runVerificationSweepNow() {
        Runnable sweepNow = () -> {
            aggregate.verificationSweep(Instant.now());
            announceHandlerLevelIfInstalled();
        };
        try {
            sweeper.execute(sweepNow);
        } catch (java.util.concurrent.RejectedExecutionException alreadyShutDown) {
            // close() won -- nothing to do
        }
    }

    private void sweepTick() {
        Instant now = Instant.now();
        aggregate.sweepExpiredOverrides(now);
        aggregate.verificationSweep(now);
        announceHandlerLevelIfInstalled();
        // doc/specs/drop-rule.md "Periodic summary line".
        aggregate.reportDueDropSummaries(now);
    }

    private static StateStore openStateStore() {
        try {
            return FileStateStore.open();
        } catch (FileStateStore.InstanceLockedException e) {
            Diagnostics.warn(
                    "LogAperture: this JVM's working-directory identity is already locked by live process pid="
                            + e.holderPid() + " -- degrading to session-only persistence for this JVM's lifetime. "
                            + "Set -Dlogaperture.instanceId=<unique-id> to disambiguate.", e);
            return StateStore.noOp();
        } catch (IOException e) {
            Diagnostics.warn(
                    "LogAperture: failed to open the persistent state store, degrading to session-only "
                            + "persistence for this JVM's lifetime", e);
            return StateStore.noOp();
        }
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "logaperture-wildfly-sweep");
        thread.setDaemon(true);
        return thread;
    }

    private static String principal() {
        return System.getProperty("user.name", "unknown");
    }

    @Override
    public void close() {
        ScheduledFuture<?> pendingInstall = handlerInstallTask;
        if (pendingInstall != null) {
            pendingInstall.cancel(false);
        }
        sweeper.shutdown();
        try {
            sweeper.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (stateStore instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                // best effort -- the OS releases the lock at process exit
            }
        }
    }
}
