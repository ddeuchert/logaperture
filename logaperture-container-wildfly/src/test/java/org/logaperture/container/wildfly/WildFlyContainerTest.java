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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.adapter.jbosslogmanager.JbossLogManagerAdapterFactory;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditRecord;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.spi.ContextHandle;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, JBoss LogManager installed as the test JVM's
 * {@code java.util.logging.manager} (see the pom) — the container root
 * driven directly, against real JBoss LogManager logger nodes but with a
 * unique-prefixed namespace per test. See doc/specs/wildfly-support.md
 * Slice 3.
 */
class WildFlyContainerTest {

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

    private WildFlyContainer newHost() {
        return new WildFlyContainer(CapabilityPolicy.allowAll(), auditLog, Duration.ofMinutes(10));
    }

    private AggregateLevelControl install(WildFlyContainer host) {
        host.installContext(ContextHandle.of(
                ContextHandle.SYSTEM, "wildfly", JbossLogManagerAdapterFactory.forCurrentContext()));
        return host.operations();
    }

    private LoggerInfo row(AggregateLevelControl ops) {
        return ops.listLoggers(logger).stream().filter(r -> r.name().equals(logger)).findFirst().orElseThrow();
    }

    @Test
    void installContext_fullLoop_listSetResetResetAll() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);

            ops.setLevel(logger, Level.DEBUG, SetLevelOptions.withReason("boot detail"));
            LoggerInfo afterSet = row(ops);
            assertEquals(Level.DEBUG, afterSet.effectiveLevel());
            assertEquals("system", afterSet.context());
            assertEquals(java.util.logging.Level.FINE, Logger.getLogger(logger).getLevel());

            ops.resetLevel(logger);
            assertFalse(row(ops).overrideActive());
            assertEquals(Level.INFO, row(ops).effectiveLevel());

            ops.resetAll();
        }
    }

    @Test
    void verificationSweep_reAppliesAnOverrideThatWasResetOutFromUnderUs() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel(logger, Level.DEBUG, SetLevelOptions.sticky());

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

    @Test
    void verificationSweep_isANoOpWhenNothingHasDrifted() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel(logger, Level.DEBUG, SetLevelOptions.sticky());
            int auditSizeBefore = auditLog.records().size();

            assertEquals(0, ops.verificationSweep(Instant.now()));
            assertEquals(auditSizeBefore, auditLog.records().size());
        }
    }

    @Test
    void configurationChangeListenerPath_triggersAVerificationSweep() throws Exception {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel(logger, Level.DEBUG, SetLevelOptions.sticky());
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
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel(logger, Level.TRACE, SetLevelOptions.forDuration(Duration.ofMillis(1)));

            ops.sweepExpiredOverrides(Instant.now().plusSeconds(60));

            assertFalse(row(ops).overrideActive());
        }
    }

    @Test
    void stickyOverride_resumesAfterASimulatedRestart() {
        try (WildFlyContainer first = newHost()) {
            install(first).setLevel(logger, Level.DEBUG, SetLevelOptions.sticky());
        }
        Logger.getLogger(logger).setLevel(null); // "restart"

        try (WildFlyContainer second = newHost()) {
            LoggerInfo info = row(install(second));
            assertEquals(Level.DEBUG, info.effectiveLevel());
            assertTrue(info.overrideActive());
        }
    }
}
