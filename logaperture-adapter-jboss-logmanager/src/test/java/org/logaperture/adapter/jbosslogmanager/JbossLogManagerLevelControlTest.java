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

import org.jboss.logmanager.LogContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slice 2 exit criterion: {@code AggregateLevelControl} driving a
 * {@link JbossLogManagerAdapter} bound to a hand-built {@code LogContext}
 * performs the full Feature 1 + Feature 2 loop — list, set, reset, expiry,
 * resume — with correct level mapping. No WildFly.
 */
class JbossLogManagerLevelControlTest {

    private static final String LOGGER = "acme.svc.Worker";

    @TempDir
    private Path home;

    private LogContext context;
    private InMemoryAuditLog auditLog;

    @BeforeEach
    void setUp() {
        System.setProperty("logaperture.home", home.toString());
        context = LogContext.create();
        context.getLogger("").setLevel(java.util.logging.Level.INFO);
        auditLog = new InMemoryAuditLog();
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("logaperture.home");
        System.clearProperty("logaperture.instanceId");
    }

    private AggregateLevelControl wire(StateStore store) {
        LoggingAdapter adapter = JbossLogManagerAdapterFactory.forContext(context);
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

    @Test
    void fullLoop_listSetResetResetAll() {
        AggregateLevelControl ops = wire(StateStore.noOp());

        ops.setLevel(LOGGER, Level.DEBUG, SetLevelOptions.withReason("INC-1"));
        LoggerInfo afterSet = row(ops, LOGGER);
        assertEquals(Level.DEBUG, afterSet.effectiveLevel());
        assertEquals("system", afterSet.context());
        assertTrue(afterSet.overrideActive());
        assertEquals(java.util.logging.Level.FINE, context.getLogger(LOGGER).getLevel(), "mapped exactly to FINE");

        ops.resetLevel(LOGGER);
        assertFalse(row(ops, LOGGER).overrideActive());
        assertEquals(Level.INFO, row(ops, LOGGER).effectiveLevel());

        ops.resetAll(); // smoke -- must not throw with nothing active
    }

    @Test
    void forOverride_isRevertedByTheExpirySweep() {
        AggregateLevelControl ops = wire(StateStore.noOp());

        ops.setLevel(LOGGER, Level.TRACE, SetLevelOptions.forDuration(Duration.ofMillis(1)));
        assertEquals(Level.TRACE, row(ops, LOGGER).effectiveLevel());

        ops.sweepExpiredOverrides(Instant.now().plusSeconds(60));

        assertFalse(row(ops, LOGGER).overrideActive());
        assertEquals(Level.INFO, row(ops, LOGGER).effectiveLevel());
    }

    @Test
    void stickyOverride_resumesOverTheSameContextAfterASimulatedRestart() throws Exception {
        try (FileStateStore first = FileStateStore.open()) {
            wire(first).setLevel(LOGGER, Level.DEBUG, SetLevelOptions.sticky());
        }
        context.getLogger(LOGGER).setLevel(null); // "restart": the framework cleared the runtime level

        try (FileStateStore second = FileStateStore.open()) {
            AggregateLevelControl resumed = wire(second);
            LoggerInfo info = row(resumed, LOGGER);
            assertEquals(Level.DEBUG, info.effectiveLevel(), "the sticky override re-applied itself on resume");
            assertTrue(info.overrideActive());
        }
    }

    private static LoggerInfo row(AggregateLevelControl ops, String name) {
        return ops.listLoggers(name).stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
