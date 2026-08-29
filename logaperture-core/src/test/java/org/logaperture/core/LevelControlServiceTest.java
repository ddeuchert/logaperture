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
package org.logaperture.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.spi.StateStore;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelControlServiceTest {

    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private OverrideRegistry overrides;
    private InMemoryAuditLog auditLog;
    private StateStore stateStore;
    private LevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        baselines = new BaselineRegistry();
        overrides = new OverrideRegistry();
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = newService(CapabilityPolicy.allowAll());
    }

    private LevelControlService newService(CapabilityPolicy policy) {
        return new LevelControlService(adapter, baselines, overrides, policy, auditLog, stateStore, "alice", "jmx");
    }

    // --- setLevel / resetLevel / resetAll round-trip -----------------------------------------

    @Test
    void setLevel_onExistingLogger_appliesAndRecordsOverride() {
        adapter.addKnownLogger("com.acme.Worker");

        LevelOverride override = service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.withReason("INC-1"));

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
        assertEquals("com.acme.Worker", override.loggerName());
        assertEquals(Level.DEBUG, override.level());
        assertEquals("INC-1", override.reason());
        assertTrue(overrides.get("com.acme.Worker").isPresent());
    }

    @Test
    void setLevel_onNotYetExistingLogger_stillWorks() {
        // "com.acme.Later" was never registered with the adapter beforehand.
        LevelOverride override = service.setLevel("com.acme.Later", Level.TRACE, SetLevelOptions.defaults());

        assertEquals(Level.TRACE, adapter.effectiveLevel("com.acme.Later"));
        assertEquals(Level.TRACE, override.level());
    }

    @Test
    void setLevel_calledTwice_replacesRatherThanDuplicates() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());
        service.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.defaults());

        assertEquals(Level.WARN, overrides.get("com.acme.Worker").orElseThrow().level());
        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void resetLevel_noOverride_isNoOpNotError() {
        service.resetLevel("com.acme.NeverTouched"); // must not throw
    }

    @Test
    void resetLevel_revertsToBaselineAndRemovesOverride() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.WARN);
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.resetLevel("com.acme.Worker");

        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Worker"));
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
    }

    @Test
    void resetLevel_baselineWasInherited_clearsToInherited() {
        // No explicit configured level for this logger -- baseline is "inherited".
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.resetLevel("com.acme.Worker");

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker")); // falls back to ROOT
    }

    @Test
    void resetAll_revertsEveryActiveOverride() {
        service.setLevel("com.acme.A", Level.DEBUG, SetLevelOptions.defaults());
        service.setLevel("com.acme.B", Level.TRACE, SetLevelOptions.defaults());

        service.resetAll();

        assertTrue(overrides.all().isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.A"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.B"));
    }

    // --- includeChildren / hierarchy fan-out --------------------------------------------------

    @Test
    void setLevel_includeChildren_appliesToEveryDescendantIndependently() {
        adapter.addKnownLogger("com.acme.http");
        adapter.addKnownLogger("com.acme.http.client");
        adapter.addKnownLogger("com.other");

        service.setLevel("com.acme", Level.DEBUG, new SetLevelOptions(true, "reason", null, PersistenceTier.SESSION));

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme"));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.http"));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.http.client"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.other")); // untouched

        assertTrue(overrides.get("com.acme").isPresent());
        assertTrue(overrides.get("com.acme.http").isPresent());
        assertTrue(overrides.get("com.acme.http.client").isPresent());
        assertTrue(overrides.get("com.other").isEmpty());
    }

    @Test
    void setLevel_includeChildrenFalse_doesNotTouchDescendants() {
        adapter.addKnownLogger("com.acme.http");

        service.setLevel("com.acme", Level.DEBUG, SetLevelOptions.defaults());

        assertTrue(overrides.get("com.acme.http").isEmpty());
    }

    // --- capability checks ---------------------------------------------------------------------

    @Test
    void listLoggers_deniesWhenViewNotGranted() {
        LevelControlService denied = newService(CapabilityPolicy.denyAll());

        assertThrows(CapabilityDeniedException.class, () -> denied.listLoggers(null));
    }

    @Test
    void setLevel_raisingDenied_throwsBeforeMutating() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.INFO);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults()));

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker")); // unchanged
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_loweringDenied_throwsBeforeMutating() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.DEBUG);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_LOWER);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.defaults()));

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void setLevel_includeChildren_denialOnLaterTargetPreventsEarlierTargetFromBeingMutated() {
        // Parent already at DEBUG (its own baseline) -- moving it to DEBUG again
        // is "lower or equal", which the policy below allows.
        adapter.setConfiguredLevel("com.acme", Level.DEBUG);
        // Child has its OWN explicit baseline at ERROR (not inherited from the
        // parent) -- moving it to DEBUG is a genuine raise, which the policy
        // below denies. (A child with no baseline of its own would inherit the
        // parent's DEBUG here, making this indistinguishable from the parent's
        // own direction -- the explicit child baseline is what creates the
        // asymmetry this test needs.)
        adapter.setConfiguredLevel("com.acme.http", Level.ERROR);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme", Level.DEBUG, new SetLevelOptions(true, null, null, PersistenceTier.SESSION)));

        // The parent passed its own check but must NOT have been mutated --
        // the whole batch is pre-checked before anything is applied.
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme")); // its baseline, unchanged
        assertEquals(Level.ERROR, adapter.effectiveLevel("com.acme.http")); // untouched
        assertTrue(overrides.all().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    // --- audit -----------------------------------------------------------------------------------

    @Test
    void setLevel_writesMutationAuditRecordWithCorrectValues() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.INFO);

        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.withReason("INC-1"));

        assertEquals(1, auditLog.records().size());
        AuditRecord record = auditLog.records().get(0);
        assertEquals(AuditRecord.Action.MUTATION, record.action());
        assertEquals("alice", record.principal());
        assertEquals("jmx", record.source());
        assertEquals("com.acme.Worker", record.loggerName());
        assertEquals("INFO", record.previousValue());
        assertEquals("DEBUG", record.newValue());
        assertEquals("INC-1", record.reason());
    }

    @Test
    void resetLevel_writesReversionAuditRecord() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.WARN);
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.resetLevel("com.acme.Worker");

        AuditRecord record = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, record.action());
        assertEquals("DEBUG", record.previousValue());
        assertEquals("WARN", record.newValue());
    }

    // --- chaos: adapter throws mid-operation -----------------------------------------------------

    @Test
    void setLevel_adapterThrows_leavesNoRegistryOrAuditTrace() {
        adapter.throwOnApply("com.acme.Worker");

        assertThrows(RuntimeException.class,
                () -> service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults()));

        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_includeChildren_adapterThrowsMidFanout_earlierTargetsStayCommitted() {
        adapter.addKnownLogger("com.acme.a");
        adapter.addKnownLogger("com.acme.b");
        // parent "com.acme" applies first, then children in the order LoggerHierarchy returns
        // them -- descendantsOf preserves input iteration order, and knownLoggerNames() here
        // returns insertion order, so "com.acme.a" is processed before "com.acme.b".
        adapter.throwOnApply("com.acme.a");

        assertThrows(RuntimeException.class,
                () -> service.setLevel("com.acme", Level.DEBUG, new SetLevelOptions(true, null, null, PersistenceTier.SESSION)));

        // The parent, processed before the throwing child, is applied and recorded.
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme"));
        assertTrue(overrides.get("com.acme").isPresent());
        assertEquals(1, auditLog.records().size());

        // The throwing child was never committed; nothing after it was attempted either.
        assertTrue(overrides.get("com.acme.a").isEmpty());
        assertTrue(overrides.get("com.acme.b").isEmpty());
    }

    // --- listLoggers -------------------------------------------------------------------------------

    @Test
    void listLoggers_reflectsOverrideStateAndFilter() {
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Thing");
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.withReason("why"));

        List<LoggerInfo> all = service.listLoggers("com.acme");

        assertEquals(1, all.size());
        LoggerInfo info = all.get(0);
        assertEquals("com.acme.Worker", info.name());
        assertTrue(info.overrideActive());
        assertEquals("jmx", info.overrideSource());
        assertEquals("why", info.overrideReason());
        assertEquals(Level.DEBUG, info.effectiveLevel());
    }

    @Test
    void listLoggers_noOverride_reportsInactiveOverrideFields() {
        adapter.addKnownLogger("com.acme.Quiet");

        Optional<LoggerInfo> info = service.listLoggers(null).stream()
                .filter(li -> li.name().equals("com.acme.Quiet"))
                .findFirst();

        assertTrue(info.isPresent());
        assertFalse(info.get().overrideActive());
    }

    // --- multi-context: activeOverrides / adoptOverride -------------------------------------------

    @Test
    void activeOverrides_returnsEveryTrackedOverride() {
        adapter.addKnownLogger("com.acme.A");
        adapter.addKnownLogger("com.acme.B");
        service.setLevel("com.acme.A", Level.DEBUG, SetLevelOptions.sticky());
        service.setLevel("com.acme.B", Level.TRACE, SetLevelOptions.defaults());

        List<LevelOverride> active = service.activeOverrides();

        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(o -> o.loggerName().equals("com.acme.A") && o.level() == Level.DEBUG));
        assertTrue(active.stream().anyMatch(o -> o.loggerName().equals("com.acme.B") && o.level() == Level.TRACE));
    }

    @Test
    void adoptOverride_appliesToAdapterAndRegistry_andRecordsAResume() {
        LevelOverride fromAnotherContext = new LevelOverride(
                "com.acme.Shared", Level.DEBUG, false, "why", java.time.Instant.now(), "jmx",
                PersistenceTier.STICKY, null);

        service.adoptOverride(fromAnotherContext);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Shared"));
        assertTrue(overrides.get("com.acme.Shared").isPresent());
        assertTrue(auditLog.records().stream()
                .anyMatch(r -> r.source().equals("resume") && r.loggerName().equals("com.acme.Shared")
                        && r.action() == AuditRecord.Action.MUTATION));
    }

    // --- verification sweep -----------------------------------------------------------------------

    @Test
    void verifyAndReapply_reAppliesAnOverrideThatDrifted_andAuditsIt() {
        adapter.addKnownLogger("com.acme.Drift");
        service.setLevel("com.acme.Drift", Level.DEBUG, SetLevelOptions.sticky());
        adapter.applyLevel("com.acme.Drift", Level.INFO); // something reset it out from under us

        int reapplied = service.verifyAndReapply(java.time.Instant.now());

        assertEquals(1, reapplied);
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Drift"));
        assertTrue(auditLog.records().stream().anyMatch(r ->
                r.source().equals("verification-sweep") && r.loggerName().equals("com.acme.Drift")
                        && r.action() == AuditRecord.Action.MUTATION));
    }

    @Test
    void verifyAndReapply_isANoOpWhenEveryOverrideIsStillInForce() {
        adapter.addKnownLogger("com.acme.Steady");
        service.setLevel("com.acme.Steady", Level.DEBUG, SetLevelOptions.sticky());
        int auditBefore = auditLog.records().size();

        assertEquals(0, service.verifyAndReapply(java.time.Instant.now()));
        assertEquals(auditBefore, auditLog.records().size());
    }

    @Test
    void verifyAndReapply_doesNotResurrectAnOverrideResetConcurrently() {
        adapter.addKnownLogger("com.acme.Raced");
        service.setLevel("com.acme.Raced", Level.DEBUG, SetLevelOptions.sticky());

        // A control-plane resetLevel lands while the sweep is reading this logger.
        adapter.runOnEffectiveLevel("com.acme.Raced", () -> service.resetLevel("com.acme.Raced"));

        int reapplied = service.verifyAndReapply(java.time.Instant.now());

        assertEquals(0, reapplied);
        assertTrue(overrides.get("com.acme.Raced").isEmpty(), "the reset stands");
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Raced"),
                "the override was not resurrected onto the adapter");
        assertTrue(auditLog.records().stream().noneMatch(r -> r.source().equals("verification-sweep")),
                "no phantom verification-sweep audit for an override that was reset");
    }

    @Test
    void verifyAndReapply_honoursAnOverrideReplacedConcurrently() {
        adapter.addKnownLogger("com.acme.Swapped");
        service.setLevel("com.acme.Swapped", Level.DEBUG, SetLevelOptions.sticky());

        adapter.runOnEffectiveLevel("com.acme.Swapped",
                () -> service.setLevel("com.acme.Swapped", Level.TRACE, SetLevelOptions.defaults()));

        int reapplied = service.verifyAndReapply(java.time.Instant.now());

        assertEquals(0, reapplied);
        assertEquals(Level.TRACE, overrides.get("com.acme.Swapped").orElseThrow().level());
        assertEquals(Level.TRACE, adapter.effectiveLevel("com.acme.Swapped"),
                "the newer setLevel wins; the sweep did not shadow it with the stale level");
    }

    @Test
    void verifyAndReapply_leavesExpiredForOverridesForTheExpirySweep() {
        adapter.addKnownLogger("com.acme.Expired");
        service.setLevel("com.acme.Expired", Level.DEBUG,
                SetLevelOptions.forDuration(java.time.Duration.ofMillis(1)));
        adapter.applyLevel("com.acme.Expired", Level.INFO); // drifted

        int reapplied = service.verifyAndReapply(java.time.Instant.now().plusSeconds(60));

        assertEquals(0, reapplied, "an already-expired FOR override is the expiry sweep's job, not this one");
    }

    @Test
    void adoptOverride_doesNotWriteToTheStateStore() {
        LevelOverride fromAnotherContext = new LevelOverride(
                "com.acme.Shared", Level.DEBUG, false, null, java.time.Instant.now(), "jmx",
                PersistenceTier.STICKY, null);

        service.adoptOverride(fromAnotherContext);

        // The originating context already persisted it to the shared store;
        // adopting it in a second context must not write again.
        assertTrue(stateStore.loadAll().isEmpty());
    }
}
