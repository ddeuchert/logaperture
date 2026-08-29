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
package org.logaperture.adapter.jbosslogmanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AggregateLevelControl.ContextControl;
import org.logaperture.core.BaselineRegistry;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.FileStateStore;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.LevelControlService;
import org.logaperture.core.OverrideRegistry;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slice 2 exit criterion: {@code AggregateLevelControl} driving a
 * {@link JbossLogManagerAdapter} through the full Feature 1 + Feature 2
 * loop — list, set, reset, expiry, resume — against the JVM's own
 * {@code java.util.logging.LogManager}, with correct level mapping.
 */
class JbossLogManagerLevelControlTest {

    @TempDir
    private Path home;

    private String logger;
    private InMemoryAuditLog auditLog;
    private java.util.logging.Level originalRootLevel;

    @BeforeEach
    void setUp() {
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

    private AggregateLevelControl wire(StateStore store) {
        LoggingAdapter adapter = JbossLogManagerAdapterFactory.forCurrentContext();
        BaselineRegistry baselines = new BaselineRegistry();
        for (String name : adapter.knownLoggerNames()) {
            baselines.captureIfAbsent(name, adapter);
        }
        LevelControlService service = new LevelControlService(
                adapter, baselines, new OverrideRegistry(), CapabilityPolicy.allowAll(),
                auditLog, store, "alice", "jmx");
        service.resumeFromStateStore(Instant.now());

        AggregateLevelControl aggregate = new AggregateLevelControl();
        aggregate.register(new ContextControl(
                ContextHandle.of(ContextHandle.SYSTEM, "system", adapter), service));
        return aggregate;
    }

    private LoggerInfo row(AggregateLevelControl ops) {
        return ops.listLoggers(logger).stream().filter(r -> r.name().equals(logger)).findFirst().orElseThrow();
    }

    @Test
    void fullLoop_listSetResetResetAll() {
        AggregateLevelControl ops = wire(StateStore.noOp());

        ops.setLevel(logger, Level.DEBUG, SetLevelOptions.withReason("INC-1"));
        LoggerInfo afterSet = row(ops);
        assertEquals(Level.DEBUG, afterSet.effectiveLevel());
        assertEquals("system", afterSet.context());
        assertEquals(java.util.logging.Level.FINE, Logger.getLogger(logger).getLevel(), "mapped exactly to FINE");

        ops.resetLevel(logger);
        assertFalse(row(ops).overrideActive());
        assertEquals(Level.INFO, row(ops).effectiveLevel());

        ops.resetAll(); // smoke
    }

    @Test
    void forOverride_isRevertedByTheExpirySweep() {
        AggregateLevelControl ops = wire(StateStore.noOp());

        ops.setLevel(logger, Level.TRACE, SetLevelOptions.forDuration(Duration.ofMillis(1)));
        assertEquals(Level.TRACE, row(ops).effectiveLevel());

        ops.sweepExpiredOverrides(Instant.now().plusSeconds(60));

        assertFalse(row(ops).overrideActive());
        assertEquals(Level.INFO, row(ops).effectiveLevel());
    }

    @Test
    void stickyOverride_resumesAfterASimulatedRestart() throws Exception {
        try (FileStateStore first = FileStateStore.open()) {
            wire(first).setLevel(logger, Level.DEBUG, SetLevelOptions.sticky());
        }
        Logger.getLogger(logger).setLevel(null); // "restart": the framework cleared the runtime level

        try (FileStateStore second = FileStateStore.open()) {
            LoggerInfo info = row(wire(second));
            assertEquals(Level.DEBUG, info.effectiveLevel(), "the sticky override re-applied itself on resume");
            assertTrue(info.overrideActive());
        }
    }

    @Test
    void verificationSweep_reAppliesAnOverrideResetOutFromUnderIt() {
        AggregateLevelControl ops = wire(StateStore.noOp());
        ops.setLevel(logger, Level.DEBUG, SetLevelOptions.sticky());

        Logger.getLogger(logger).setLevel(null); // a /subsystem=logging change / :reload

        int reapplied = ops.verificationSweep(Instant.now());

        assertEquals(1, reapplied);
        assertEquals(java.util.logging.Level.FINE, Logger.getLogger(logger).getLevel());
    }
}
