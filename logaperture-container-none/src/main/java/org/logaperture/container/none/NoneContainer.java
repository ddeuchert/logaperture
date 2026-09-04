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

import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AggregateLevelControl.ContextControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.BaselineRegistry;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.FileStateStore;
import org.logaperture.core.HandlerBaselineRegistry;
import org.logaperture.core.HandlerLevelControlService;
import org.logaperture.core.HandlerOverrideRegistry;
import org.logaperture.core.LevelControlService;
import org.logaperture.core.OverrideRegistry;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Composition root for the plain {@code java -jar} container — "the
 * baseline, built first" (doc/logaperture-spec.md §4.6). Since
 * doc/specs/wildfly-support.md (Slice 1) it is multi-context aware: it owns
 * one {@link AggregateLevelControl}, the shared {@link StateStore}, and the
 * single expiry-sweep thread, and installs level control <em>per context</em>
 * ({@link #installContext}). For {@code none} there is only ever one context
 * ({@code "system"}); the machinery is shared with the container
 * integrations to come.
 *
 * <p>Per-context install does what it always did: capture baseline for every
 * known logger, resume this JVM's persisted state, wire the framework's own
 * reset event back into {@link LevelControlService#reapplyActiveOverrides},
 * and register the resulting service with the aggregate. Scheduling of the
 * expiry sweep lives here, not in {@code core} (per the spec, {@code core}
 * has no opinion about "when").
 */
public final class NoneContainer implements AutoCloseable {

    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final StateStore stateStore;
    private final AggregateLevelControl aggregate = new AggregateLevelControl();
    private final ScheduledExecutorService sweeper;

    public NoneContainer(CapabilityPolicy policy, AuditLog auditLog) {
        this(policy, auditLog, SweepPolicy.interval());
    }

    /** Package-visible so tests can use a short sweep interval instead of waiting on the real 30s one. */
    NoneContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval) {
        this.policy = policy;
        this.auditLog = auditLog;
        this.stateStore = openStateStore();

        this.sweeper = Executors.newSingleThreadScheduledExecutor(NoneContainer::newDaemonThread);
        long intervalMillis = sweepInterval.toMillis();
        sweeper.scheduleAtFixedRate(this::sweepTick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * One periodic tick: expire timed overrides, then re-apply any that a
     * framework reconfiguration overwrote (§15.5). Logback fires its own
     * reset event so the verification sweep is belt-and-suspenders here, but
     * §15.5 makes it a core invariant, not a per-container special case.
     */
    private void sweepTick() {
        Instant now = Instant.now();
        aggregate.sweepExpiredOverrides(now);
        aggregate.verificationSweep(now);
    }

    /** The surface a control plane (JMX) binds to. */
    public AggregateLevelControl operations() {
        return aggregate;
    }

    /**
     * Builds, wires, and registers level control for one logging context:
     * eager baseline capture, resume of this JVM's persisted state,
     * reconfiguration re-application wiring, then {@link
     * AggregateLevelControl#register}.
     */
    public void installContext(ContextHandle handle) {
        LoggingAdapter adapter = handle.adapter();

        BaselineRegistry baselines = new BaselineRegistry();
        for (String name : adapter.knownLoggerNames()) {
            baselines.captureIfAbsent(name, adapter);
        }

        OverrideRegistry overrides = new OverrideRegistry();
        LevelControlService service = new LevelControlService(
                adapter, baselines, overrides, policy, auditLog, stateStore, principal(), "jmx");

        HandlerBaselineRegistry handlerBaselines = new HandlerBaselineRegistry();
        HandlerOverrideRegistry handlerOverrides = new HandlerOverrideRegistry();
        HandlerLevelControlService handlerService = new HandlerLevelControlService(
                adapter, handlerBaselines, handlerOverrides, policy, auditLog, stateStore, principal(), "jmx");

        try {
            // Per-entry failures are already isolated inside
            // resumeFromStateStore; this outer guard is defense in depth
            // against a StateStore whose loadAll() itself throws -- fail-open
            // (doc/logaperture-spec.md §9).
            service.resumeFromStateStore(Instant.now());
            handlerService.resumeFromStateStore(Instant.now());
        } catch (RuntimeException e) {
            Diagnostics.warn("LogAperture: failed to resume persisted overrides, continuing without them", e);
        }

        // doc/specs/persistence.md "Reconfiguration re-application": Logback's
        // own reset event (scan="true", JMXConfigurator, an explicit
        // context.reset()) is independent of which container hosts it.
        // doc/specs/handler-floor-control.md "Reconfiguration re-application"
        // extends the same hook to handler overrides.
        Runnable reapplyOnReset = () -> {
            for (String name : adapter.knownLoggerNames()) {
                baselines.captureIfAbsent(name, adapter);
            }
            service.reapplyActiveOverrides(adapter);
            handlerService.reapplyActiveOverrides(adapter);
        };
        adapter.onReset(reapplyOnReset);

        aggregate.register(new ContextControl(handle, service, handlerService));
    }

    /**
     * Opens this JVM's {@link FileStateStore}, degrading to {@link
     * StateStore#noOp()} for this JVM's entire lifetime on any failure to
     * do so — fail-open, per doc/logaperture-spec.md §9.
     */
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
        Thread thread = new Thread(task, "logaperture-expiry-sweep");
        thread.setDaemon(true); // never blocks JVM shutdown -- production never explicitly stops this
        return thread;
    }

    /** §9.7's principal for this slice — the JVM's own account name, matching the audit-trail field this feeds. */
    private static String principal() {
        return System.getProperty("user.name", "unknown");
    }

    /**
     * Stops the expiry sweep, unregisters every context's reset listener,
     * and releases the state store's lock. Production keeps a {@code
     * NoneContainer} for the JVM's lifetime and never calls this; tests use
     * it to tear down cleanly between cases.
     */
    @Override
    public void close() {
        // Plain shutdown(), not shutdownNow(): an in-flight sweep is left to
        // finish its current persist() undisturbed rather than interrupted
        // mid-write.
        sweeper.shutdown();
        try {
            sweeper.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (ContextControl context : aggregate.contexts()) {
            context.handle().adapter().clearResetListener();
        }

        if (stateStore instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Best effort -- the OS releases the lock at process exit regardless.
            }
        }
    }
}
