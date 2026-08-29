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

import org.jboss.logmanager.LogContext;
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
import org.logaperture.core.spi.LoggingAdapter;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, JBoss LogManager on the test classpath, no WildFly — the
 * container root, driven directly with a hand-built system {@code
 * LogContext}. See doc/specs/wildfly-support.md Slice 3.
 */
class WildFlyContainerTest {

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

    private WildFlyContainer newHost() {
        return new WildFlyContainer(CapabilityPolicy.allowAll(), auditLog, Duration.ofMinutes(10));
    }

    private AggregateLevelControl install(WildFlyContainer host) {
        LoggingAdapter adapter = JbossLogManagerAdapterFactory.forContext(context);
        host.installContext(ContextHandle.of(ContextHandle.SYSTEM, "wildfly", adapter));
        return host.operations();
    }

    private LoggerInfo row(AggregateLevelControl ops, String name) {
        return ops.listLoggers(name).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void installContext_fullLoop_listSetResetResetAll() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);

            ops.setLevel("org.jboss.as.server", Level.DEBUG, SetLevelOptions.withReason("boot detail"));
            LoggerInfo afterSet = row(ops, "org.jboss.as.server");
            assertEquals(Level.DEBUG, afterSet.effectiveLevel());
            assertEquals("system", afterSet.context(), "the WildFly system context's stableKey");
            assertEquals(java.util.logging.Level.FINE, context.getLogger("org.jboss.as.server").getLevel());

            ops.resetLevel("org.jboss.as.server");
            assertFalse(row(ops, "org.jboss.as.server").overrideActive());
            assertEquals(Level.INFO, row(ops, "org.jboss.as.server").effectiveLevel());

            ops.resetAll();
        }
    }

    @Test
    void verificationSweep_reAppliesAnOverrideThatWasResetOutFromUnderUs() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.sticky());

            // Simulate a /subsystem=logging change / :reload wiping the runtime level.
            context.getLogger("com.acme.Worker").setLevel(null);
            assertNull(context.getLogger("com.acme.Worker").getLevel(), "the runtime level really was cleared");

            int reapplied = ops.verificationSweep(Instant.now());

            assertEquals(1, reapplied);
            assertEquals(java.util.logging.Level.FINE, context.getLogger("com.acme.Worker").getLevel());
            assertTrue(auditLog.records().stream().anyMatch(r ->
                    r.source().equals("verification-sweep")
                            && r.loggerName().equals("com.acme.Worker")
                            && r.action() == AuditRecord.Action.MUTATION));
        }
    }

    @Test
    void verificationSweep_isANoOpWhenNothingHasDrifted() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel("com.acme.Steady", Level.DEBUG, SetLevelOptions.sticky());
            int auditSizeBefore = auditLog.records().size();

            assertEquals(0, ops.verificationSweep(Instant.now()));
            assertEquals(auditSizeBefore, auditLog.records().size(), "a quiet sweep writes no audit records");
        }
    }

    @Test
    void configurationChangeListener_pathTriggersAVerificationSweep() throws Exception {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel("com.acme.Listened", Level.DEBUG, SetLevelOptions.sticky());
            context.getLogger("com.acme.Listened").setLevel(null); // drift

            host.runVerificationSweepNow(); // what the LogManager config listener calls

            // runs on the sweep thread -- poll briefly for it to land
            for (int i = 0; i < 50 && context.getLogger("com.acme.Listened").getLevel() == null; i++) {
                Thread.sleep(10);
            }
            assertEquals(java.util.logging.Level.FINE, context.getLogger("com.acme.Listened").getLevel());
        }
    }

    @Test
    void forOverride_isRevertedByTheExpirySweep() {
        try (WildFlyContainer host = newHost()) {
            AggregateLevelControl ops = install(host);
            ops.setLevel("com.acme.Timed", Level.TRACE, SetLevelOptions.forDuration(Duration.ofMillis(1)));

            ops.sweepExpiredOverrides(Instant.now().plusSeconds(60));

            assertFalse(row(ops, "com.acme.Timed").overrideActive());
        }
    }

    @Test
    void stickyOverride_resumesAfterASimulatedRestart() throws Exception {
        try (WildFlyContainer first = newHost()) {
            install(first).setLevel("com.acme.Sticky", Level.DEBUG, SetLevelOptions.sticky());
        }
        context.getLogger("com.acme.Sticky").setLevel(null); // "restart"

        try (WildFlyContainer second = newHost()) {
            LoggerInfo info = row(install(second), "com.acme.Sticky");
            assertEquals(Level.DEBUG, info.effectiveLevel());
            assertTrue(info.overrideActive());
        }
    }
}
