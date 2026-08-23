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
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.AuditLog;
import org.logaperture.core.BaselineRegistry;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.FileStateStore;
import org.logaperture.core.LevelControlService;
import org.logaperture.core.OverrideRegistry;
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
 * baseline, built first" (doc/logaperture-spec.md §4.6). No server-specific
 * discovery needed: this class is the entire module.
 *
 * <p>Since doc/specs/persistence.md, {@link #install} does more than build
 * the service: it opens this JVM's persisted state, resumes what's still
 * live, wires Logback's own reset event back into {@link
 * LevelControlService#reapplyActiveOverrides}, and starts the expiry sweep
 * that owns "when" for {@code --for} overrides (per the spec, {@code core}
 * has no opinion about scheduling; this class is where that opinion lives).
 */
public final class NoneContainer {

    private static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(30);

    private NoneContainer() {
    }

    /**
     * Builds the Logback adapter, eagerly walks and captures baseline for
     * every currently-known logger (doc/specs/level-control.md: baseline
     * capture must happen before any override, "without this, reset is
     * undefined"), resumes this JVM's persisted state, wires reconfiguration
     * re-application, starts the expiry sweep, and returns a ready-to-use
     * {@link Installation}.
     *
     * @throws org.logaperture.adapter.logback.AdapterInstallException if
     *         SLF4J isn't bound to a Logback {@code LoggerContext}
     */
    public static Installation install(CapabilityPolicy policy, AuditLog auditLog) {
        return install(policy, auditLog, DEFAULT_SWEEP_INTERVAL);
    }

    /** Package-visible so tests can use a short sweep interval instead of waiting on the real 30s one. */
    static Installation install(CapabilityPolicy policy, AuditLog auditLog, Duration sweepInterval) {
        LoggingAdapter adapter = LogbackAdapterFactory.forCurrentContext();

        BaselineRegistry baselines = new BaselineRegistry();
        for (String name : adapter.knownLoggerNames()) {
            baselines.captureIfAbsent(name, adapter);
        }

        OverrideRegistry overrides = new OverrideRegistry();
        StateStore stateStore = openStateStore();

        LevelControlService service = new LevelControlService(
                adapter, baselines, overrides, policy, auditLog, stateStore, principal(), "jmx");

        service.resumeFromStateStore(Instant.now());

        // doc/specs/persistence.md "Reconfiguration re-application": a
        // container-specific reset source (Spring Boot, WildFly) is still
        // deferred, but Logback's own reset event (scan="true", JMX
        // JMXConfigurator, an explicit context.reset()) is independent of
        // which container is hosting it, and `none` benefits immediately.
        adapter.onReset(() -> {
            for (String name : adapter.knownLoggerNames()) {
                baselines.captureIfAbsent(name, adapter);
            }
            service.reapplyActiveOverrides(adapter);
        });

        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(NoneContainer::newDaemonThread);
        long intervalMillis = sweepInterval.toMillis();
        sweeper.scheduleAtFixedRate(
                () -> service.sweepExpiredOverrides(Instant.now()), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);

        return new Installation(service, sweeper, stateStore);
    }

    /**
     * Opens this JVM's {@link FileStateStore}, degrading to {@link
     * StateStore#noOp()} for this JVM's entire lifetime on any failure to
     * do so — fail-open, per doc/logaperture-spec.md §9. A same-working-
     * directory lock collision (doc/specs/persistence.md) is the expected
     * case; any other I/O failure opening the store is treated the same
     * way, since a JVM that can't establish its own persistence should
     * still start and serve session-only level control.
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

    /**
     * §9.7's "principal (JVM UID, since JMX auth is the JVM's own)" for
     * this slice — the JVM's own account name, matching the audit-trail
     * field this feeds.
     */
    private static String principal() {
        return System.getProperty("user.name", "unknown");
    }

    /**
     * A completed install: the service, plus the resources backing its
     * ongoing persistence (the expiry-sweep thread and, if it implements
     * {@link Closeable}, the state store's own lock). Production keeps this
     * for the JVM's lifetime and never calls {@link #close}; tests use it
     * to tear down cleanly between cases.
     */
    public static final class Installation implements AutoCloseable {

        private final LevelControlService service;
        private final ScheduledExecutorService sweeper;
        private final StateStore stateStore;

        private Installation(LevelControlService service, ScheduledExecutorService sweeper, StateStore stateStore) {
            this.service = service;
            this.sweeper = sweeper;
            this.stateStore = stateStore;
        }

        public LevelControlService service() {
            return service;
        }

        @Override
        public void close() {
            sweeper.shutdownNow();
            if (stateStore instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    // Best effort -- releasing the instance lock a little
                    // late is harmless; the OS releases it at process exit
                    // regardless.
                }
            }
        }
    }
}
