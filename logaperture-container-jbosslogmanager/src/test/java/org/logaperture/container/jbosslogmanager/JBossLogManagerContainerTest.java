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
package org.logaperture.container.jbosslogmanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditRecord;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.SweepPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.spi.ContextHandle;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, JBoss LogManager installed as the test JVM's
 * {@code java.util.logging.manager} (see the pom) — the container root
 * driven directly, against real JBoss LogManager logger nodes but with a
 * unique-prefixed namespace per test. See doc/specs/wildfly-support.md
 * Slice 3.
 */
class JBossLogManagerContainerTest {

    @TempDir
    private Path home;

    private String logger;
    private InMemoryAuditLog auditLog;
    private java.util.logging.Level originalRootLevel;

    @BeforeEach
    void setUp() {
        assertEquals("org.jboss.logmanager.LogManager", LogManager.getLogManager().getClass().getName(),
                "the pom installs JBoss LogManager for this test JVM");
        System.setProperty("logaperture.home", home.toString());
        logger = "it." + UUID.randomUUID().toString().replace("-", "") + ".Worker";
        auditLog = new InMemoryAuditLog();
        originalRootLevel = Logger.getLogger("").getLevel();
        Logger.getLogger("").setLevel(java.util.logging.Level.INFO);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").setLevel(originalRootLevel);
        Logger.getLogger(logger).setLevel(null);
        System.clearProperty("logaperture.home");
        System.clearProperty("logaperture.instanceId");
    }

    private JBossLogManagerContainer newHost() {
        return new JBossLogManagerContainer("WildFly", CapabilityPolicy.allowAll(), auditLog, Duration.ofMinutes(10));
    }

    private AggregateLevelControl install(JBossLogManagerContainer host) {
        host.installContext(ContextHandle.of(
                ContextHandle.SYSTEM, "wildfly", JulAdapterFactory.forCurrentContext()));
        return host.operations();
    }

    private LoggerInfo row(AggregateLevelControl ops) {
        return ops.listLoggers(logger).stream().filter(r -> r.name().equals(logger)).findFirst().orElseThrow();
    }

    @Test
    void installContext_fullLoop_listSetResetResetAll() {
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);

            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.withReason("boot detail"));
            LoggerInfo afterSet = row(ops);
            assertEquals(Level.DEBUG, afterSet.effectiveLevel());
            assertEquals("system", afterSet.context());
            assertEquals(java.util.logging.Level.FINE, Logger.getLogger(logger).getLevel());

            ops.resetLogger(logger, false);
            assertFalse(row(ops).overrideActive());
            assertEquals(Level.INFO, row(ops).effectiveLevel());

            ops.resetAllLoggers(false);
        }
    }

    @Test
    void verificationSweep_reAppliesAnOverrideThatWasResetOutFromUnderUs() {
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());

            Logger.getLogger(logger).setLevel(null); // a /subsystem=logging change / :reload
            assertNull(Logger.getLogger(logger).getLevel(), "the runtime level really was cleared");

            int reapplied = ops.verificationSweep(Instant.now());

            assertEquals(1, reapplied);
            assertEquals(java.util.logging.Level.FINE, Logger.getLogger(logger).getLevel());
            assertTrue(auditLog.records().stream().anyMatch(r ->
                    r.source().equals("verification-sweep") && r.loggerName().equals(logger)
                            && r.action() == AuditRecord.Action.MUTATION));
        }
    }

    /**
     * Issue #232: a rewritten logging.properties is how a /subsystem=logging change shows itself, and
     * it re-applies at once, with a periodic sweep (10 minutes here) nowhere near due.
     */
    @Test
    void watchConfiguration_aChangeReportedByTheCheckReAppliesWithoutWaitingForTheSweep() throws Exception {
        AtomicBoolean fileRewritten = new AtomicBoolean();
        BooleanSupplier changedOnce = () -> fileRewritten.getAndSet(false);
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());
            host.watchConfiguration(changedOnce, host::runVerificationSweepNow, Duration.ofMillis(50));

            Logger.getLogger(logger).setLevel(null); // the change the subsystem commits...
            fileRewritten.set(true); // ...then the file is rewritten

            awaitTrue(() -> java.util.logging.Level.FINE.equals(Logger.getLogger(logger).getLevel()),
                    "the override was re-applied after the change");
        }
    }

    @Test
    void watchConfiguration_aCheckThatThrowsDoesNotStopLaterChecks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BooleanSupplier throwsTwice = () -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                throw new IllegalStateException("first check fails");
            }
            if (call == 2) {
                throw new NoClassDefFoundError("second check fails with an Error");
            }
            return false;
        };
        try (JBossLogManagerContainer host = newHost()) {
            host.watchConfiguration(throwsTwice, () -> { }, Duration.ofMillis(20));

            awaitTrue(() -> calls.get() >= 4, "checks kept running after the first two threw");
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void startEarlyResweep_window_repairsDriftWellBeforeThePeriodicSweep_thenStops() throws Exception {
        try (JBossLogManagerContainer host = newHost()) { // periodic sweep: 10 minutes
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());

            Logger.getLogger(logger).setLevel(null); // the framework's late setup overwrites it
            host.startEarlyResweep(new SweepPolicy.StartupWindow(Duration.ofMillis(20), Duration.ofMillis(400)),
                    null, Duration.ofMillis(20), Duration.ofMillis(400));

            awaitTrue(() -> Logger.getLogger(logger).getLevel() == java.util.logging.Level.FINE);

            Thread.sleep(700); // past the window's end
            int afterWindow = host.earlySweepsRun();
            Thread.sleep(200);
            assertEquals(afterWindow, host.earlySweepsRun(), "the window ended, no more fast sweeps");
            assertTrue(afterWindow >= 1);
        }
    }

    @Test
    void startEarlyResweep_activationSignal_sweepsOnceWhenItTurnsTrue() throws Exception {
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());
            java.util.concurrent.atomic.AtomicBoolean activated = new java.util.concurrent.atomic.AtomicBoolean();
            host.startEarlyResweep(null, activated::get, Duration.ofMillis(20), Duration.ofSeconds(5));

            Logger.getLogger(logger).setLevel(null);
            Thread.sleep(150);
            assertNull(Logger.getLogger(logger).getLevel(), "nothing swept before the framework says it is ready");
            assertEquals(0, host.earlySweepsRun());

            activated.set(true);
            awaitTrue(() -> Logger.getLogger(logger).getLevel() == java.util.logging.Level.FINE);
            Thread.sleep(200);
            assertEquals(1, host.earlySweepsRun(), "the trigger fires once, not on every poll");
        }
    }

    @Test
    void startEarlyResweep_aThrowingSignalIsTreatedAsNotYet() throws Exception {
        try (JBossLogManagerContainer host = newHost()) {
            install(host);
            host.startEarlyResweep(null, () -> {
                throw new IllegalStateException("boom");
            }, Duration.ofMillis(20), Duration.ofMillis(200));
            Thread.sleep(300);
            assertEquals(0, host.earlySweepsRun());
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within 5s");
            Thread.sleep(10);
        }
    }

    @Test
    void verificationSweep_isANoOpWhenNothingHasDrifted() {
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());
            int auditSizeBefore = auditLog.records().size();

            assertEquals(0, ops.verificationSweep(Instant.now()));
            assertEquals(auditSizeBefore, auditLog.records().size());
        }
    }

    @Test
    void configurationChangeListenerPath_triggersAVerificationSweep() throws Exception {
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());
            Logger.getLogger(logger).setLevel(null); // drift

            host.runVerificationSweepNow(); // what the LogManager config listener calls

            for (int i = 0; i < 50 && Logger.getLogger(logger).getLevel() == null; i++) {
                Thread.sleep(10);
            }
            assertEquals(java.util.logging.Level.FINE, Logger.getLogger(logger).getLevel());
        }
    }

    @Test
    void forOverride_isRevertedByTheExpirySweep() {
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLogger(logger, Level.TRACE, SetLevelOptions.forDuration(Duration.ofMillis(1)));

            ops.sweepExpiredOverrides(Instant.now().plusSeconds(60));

            assertFalse(row(ops).overrideActive());
        }
    }

    @Test
    void stickyOverride_resumesAfterASimulatedRestart() {
        try (JBossLogManagerContainer first = newHost()) {
            install(first).setLogger(logger, Level.DEBUG, SetLevelOptions.sticky());
        }
        Logger.getLogger(logger).setLevel(null); // "restart"

        try (JBossLogManagerContainer second = newHost()) {
            LoggerInfo info = row(install(second));
            assertEquals(Level.DEBUG, info.effectiveLevel());
            assertTrue(info.overrideActive());
        }
    }

    @Test
    void environmentReport_containerNameIsAlwaysWildFly_evenWithNoKnownVersion() {
        // newHost() goes through the no-version 3-arg constructor -- doc/specs/
        // environment-report.md: this class only ever represents WildFly, so the
        // container name must never be left null just because no version is known
        // (unlike backendName/backendVersion, which genuinely can be absent).
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);

            assertEquals("WildFly", ops.environmentReport().containerName());
            assertNull(ops.environmentReport().containerVersion(), "no version was supplied to this host");
        }
    }

    @Test
    void environmentReport_stateFilePath_isTheRealFullyQualifiedStateFile() {
        // doc/specs/environment-report.md "State file".
        try (JBossLogManagerContainer host = newHost()) {
            AggregateLevelControl ops = install(host);

            String stateFilePath = ops.environmentReport().stateFilePath();

            assertNotNull(stateFilePath);
            assertTrue(Path.of(stateFilePath).isAbsolute(), stateFilePath);
            assertTrue(stateFilePath.startsWith(home.resolve("instances").toString()), stateFilePath);
            assertTrue(stateFilePath.endsWith(".state.yaml"), stateFilePath);
        }
    }
}
