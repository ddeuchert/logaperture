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
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AggregateLevelControl.ContextControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.BaselineRegistry;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.FileStateStore;
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
    private final AggregateLevelControl aggregate = new AggregateLevelControl();
    private final ScheduledExecutorService sweeper;

    public WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog) {
        this(policy, auditLog, SweepPolicy.interval());
    }

    /** Package-visible so tests can use a short sweep interval instead of the real 30s one. */
    WildFlyContainer(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval) {
        this.policy = policy;
        this.auditLog = auditLog;
        this.stateStore = openStateStore();

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
        LoggingAdapter adapter = handle.adapter();

        BaselineRegistry baselines = new BaselineRegistry();
        for (String name : adapter.knownLoggerNames()) {
            baselines.captureIfAbsent(name, adapter);
        }

        OverrideRegistry overrides = new OverrideRegistry();
        LevelControlService service = new LevelControlService(
                adapter, baselines, overrides, policy, auditLog, stateStore, principal(), "jmx");

        try {
            service.resumeFromStateStore(Instant.now());
        } catch (RuntimeException e) {
            Diagnostics.warn("LogAperture: failed to resume persisted overrides, continuing without them", e);
        }

        aggregate.register(new ContextControl(handle, service));
    }

    /**
     * Runs the verification sweep off the sweep thread, now. Called from the
     * {@code LogManager} configuration-change listener so a management change
     * is corrected promptly rather than on the next periodic tick. Submitted
     * (not run inline) so WildFly's own configuration thread is never
     * blocked on our work.
     */
    void runVerificationSweepNow() {
        try {
            sweeper.execute(() -> aggregate.verificationSweep(Instant.now()));
        } catch (java.util.concurrent.RejectedExecutionException alreadyShutDown) {
            // close() won -- nothing to do
        }
    }

    private void sweepTick() {
        Instant now = Instant.now();
        aggregate.sweepExpiredOverrides(now);
        aggregate.verificationSweep(now);
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
