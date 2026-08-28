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

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.adapter.logback.LogbackAdapterFactory;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.spi.ContextHandle;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, real Logback, no agent and no JMX -- proves the whole
 * non-agent stack works end-to-end programmatically. Since
 * doc/specs/wildfly-support.md (Slice 1), {@link NoneContainer} is the
 * multi-context composition root; these tests drive it directly (the
 * {@link LogbackLoadDetector} readiness gate is exercised only by the
 * cross-process {@code LevelControlEndToEndIT}), installing the single
 * {@code "system"} context by hand.
 *
 * <p>Every test points {@code logaperture.home} at a fresh {@code @TempDir}
 * so no test ever touches the developer's real {@code ~/.logaperture}.
 */
class NoneContainerTest {

    @TempDir
    private Path home;

    @BeforeEach
    void pointAtTempHome() {
        System.setProperty("logaperture.home", home.toString());
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("logaperture.home");
        System.clearProperty("logaperture.instanceId");
    }

    private static NoneContainer newRoot() {
        return new NoneContainer(CapabilityPolicy.allowAll(), new InMemoryAuditLog());
    }

    private static NoneContainer newRoot(Duration sweepInterval) {
        return new NoneContainer(CapabilityPolicy.allowAll(), new InMemoryAuditLog(), sweepInterval);
    }

    /** Installs the single Logback context and returns the aggregate surface. */
    private static AggregateLevelControl install(NoneContainer root) {
        root.installContext(ContextHandle.of(
                ContextHandle.SYSTEM, "none", LogbackAdapterFactory.forCurrentContext()));
        return root.operations();
    }

    @Test
    void installContext_returnsWorkingOperationsAgainstTheRealStaticContext() {
        LoggerFactory.getLogger("org.logaperture.container.none.PreExisting"); // instantiate before install

        try (NoneContainer root = newRoot()) {
            AggregateLevelControl ops = install(root);
            assertNotNull(ops);

            List<LoggerInfo> loggers = ops.listLoggers("org.logaperture.container.none.PreExisting");
            assertEquals(1, loggers.size());
            assertEquals("system", loggers.get(0).context());
        }
    }

    @Test
    void installContext_capturesBaselineForPreExistingLoggers() {
        LoggerFactory.getLogger("org.logaperture.container.none.baseline.Probe");
        // No explicit level set on it -- baseline should be captured as inherited.

        try (NoneContainer root = newRoot()) {
            AggregateLevelControl ops = install(root);

            Optional<LoggerInfo> info = ops.listLoggers(null).stream()
                    .filter(li -> li.name().equals("org.logaperture.container.none.baseline.Probe"))
                    .findFirst();
            assertTrue(info.isPresent());

            // resetLevel on a logger that was never overridden must be a safe
            // no-op -- if baseline capture hadn't run, this would misbehave.
            ops.resetLevel("org.logaperture.container.none.baseline.Probe");
        }
    }

    @Test
    void installContext_setLevelThenResetLevel_roundTrips() {
        try (NoneContainer root = newRoot()) {
            AggregateLevelControl ops = install(root);

            ops.setLevel("org.logaperture.container.none.roundtrip.Worker", Level.DEBUG, SetLevelOptions.defaults());
            LoggerInfo afterSet = ops.listLoggers("org.logaperture.container.none.roundtrip.Worker").get(0);
            assertEquals(Level.DEBUG, afterSet.effectiveLevel());
            assertTrue(afterSet.overrideActive());

            ops.resetLevel("org.logaperture.container.none.roundtrip.Worker");
            LoggerInfo afterReset = ops.listLoggers("org.logaperture.container.none.roundtrip.Worker").get(0);
            assertTrue(!afterReset.overrideActive());
        }
    }

    // --- persistence: resume across a simulated restart -----------------------------------------

    @Test
    void stickyOverride_survivesASimulatedRestart() {
        String loggerName = "org.logaperture.container.none.resume.Sticky";

        try (NoneContainer first = newRoot()) {
            install(first).setLevel(loggerName, Level.DEBUG, SetLevelOptions.sticky());
        } // "restart": releases the instance lock

        try (NoneContainer second = newRoot()) {
            LoggerInfo info = install(second).listLoggers(loggerName).get(0);
            assertEquals(Level.DEBUG, info.effectiveLevel());
            assertTrue(info.overrideActive());
        }
    }

    @Test
    void expiredForOverride_doesNotReappearAfterASimulatedRestart() {
        String loggerName = "org.logaperture.container.none.resume.ExpiredFor";

        try (NoneContainer first = newRoot()) {
            install(first).setLevel(loggerName, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMillis(1)));
        }
        try {
            Thread.sleep(20); // let the 1ms expiry pass while this "JVM" is "down"
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try (NoneContainer second = newRoot()) {
            LoggerInfo info = install(second).listLoggers(loggerName).get(0);
            assertTrue(!info.overrideActive());
        }
    }

    // --- expiry sweep -------------------------------------------------------------------------------

    @Test
    void expirySweep_revertsAForOverrideOnSchedule() throws InterruptedException {
        String loggerName = "org.logaperture.container.none.sweep.Worker";

        try (NoneContainer root = newRoot(Duration.ofMillis(20))) {
            AggregateLevelControl ops = install(root);
            ops.setLevel(loggerName, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMillis(1)));

            LoggerInfo revertedInfo = pollUntilReverted(ops, loggerName);
            assertTrue(!revertedInfo.overrideActive());
        }
    }

    private static LoggerInfo pollUntilReverted(AggregateLevelControl ops, String loggerName) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) { // up to ~2s
            LoggerInfo info = ops.listLoggers(loggerName).get(0);
            if (!info.overrideActive()) {
                return info;
            }
            Thread.sleep(20);
        }
        return ops.listLoggers(loggerName).get(0);
    }

    // --- reconfiguration re-application ---------------------------------------------------------------

    @Test
    void logbackReset_reappliesActiveOverridesWithoutDuplication() {
        String loggerName = "org.logaperture.container.none.reset.Worker";

        try (NoneContainer root = newRoot()) {
            AggregateLevelControl ops = install(root);
            ops.setLevel(loggerName, Level.DEBUG, SetLevelOptions.sticky());

            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            assertTrue(factory instanceof LoggerContext, "test JVM must be bound to a real Logback LoggerContext");
            LoggerContext context = (LoggerContext) factory;

            context.reset(); // simulates the framework discarding installed levels on its own
            assertEquals(Level.DEBUG, ops.listLoggers(loggerName).get(0).effectiveLevel());

            context.reset(); // a second reset must not double-apply or otherwise misbehave
            assertEquals(Level.DEBUG, ops.listLoggers(loggerName).get(0).effectiveLevel());
        }
    }

    /**
     * A prior code-review finding: each install against the shared static
     * {@code LoggerContext} used to register a reset listener without ever
     * removing it on teardown, so a closed root's (stale) {@code
     * reapplyActiveOverrides} kept firing on every later {@code
     * context.reset()}.
     */
    @Test
    void closeThenReinstall_closedRootsListenerDoesNotFireAnymore() {
        String firstLogger = "org.logaperture.container.none.reset.FirstInstallWorker";
        String secondLogger = "org.logaperture.container.none.reset.SecondInstallWorker";

        NoneContainer first = newRoot();
        // SESSION deliberately -- never persisted, so any reappearance in
        // `second` can only come from the stale listener under test. TRACE
        // is likewise deliberate: distinct from whatever level Logback's own
        // reset() leaves ROOT at, so a stale reapply is unambiguous.
        install(first).setLevel(firstLogger, Level.TRACE, SetLevelOptions.defaults());
        first.close();

        try (NoneContainer second = newRoot()) {
            AggregateLevelControl ops = install(second);
            ops.setLevel(secondLogger, Level.DEBUG, SetLevelOptions.sticky());

            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            ((LoggerContext) factory).reset();

            // The second root's own override survives its own reset...
            assertEquals(Level.DEBUG, ops.listLoggers(secondLogger).get(0).effectiveLevel());
            // ...but the first (closed) root's listener must not have fired
            // and reapplied its stale TRACE override onto the shared context.
            assertTrue(ops.listLoggers(firstLogger).get(0).effectiveLevel() != Level.TRACE);
        }
    }
}
