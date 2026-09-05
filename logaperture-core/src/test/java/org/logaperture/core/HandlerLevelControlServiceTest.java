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
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.core.spi.StateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code logctl handler <name> <level>} engine — see doc/specs/
 * handler-floor-control.md "Testing". Mirrors {@link LevelControlServiceTest}'s
 * shape: same ordering discipline, but no fan-out and no overlap
 * recomputation (a handler override's lifetime is independent, per the
 * spec's "Independent lifetime" semantics note).
 */
class HandlerLevelControlServiceTest {

    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");

    private FakeLoggingAdapter adapter;
    private HandlerBaselineRegistry baselines;
    private HandlerOverrideRegistry overrides;
    private InMemoryAuditLog auditLog;
    private StateStore stateStore;
    private HandlerLevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        baselines = new HandlerBaselineRegistry();
        overrides = new HandlerOverrideRegistry();
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = newService(CapabilityPolicy.allowAll());
    }

    private HandlerLevelControlService newService(CapabilityPolicy policy) {
        return new HandlerLevelControlService(
                adapter, baselines, overrides, policy, auditLog, stateStore, "alice", "jmx");
    }

    // --- setHandlerLevel / resetHandler round-trip ------------------------------------------------

    @Test
    void setHandlerLevel_lowersAndRecordsTheOverride() {
        HandlerLevelOverride override = service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults())
                .orElseThrow();

        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(CONSOLE, override.handlerRef());
        assertEquals(Level.TRACE, override.level());
        assertTrue(overrides.get(CONSOLE).isPresent());
    }

    @Test
    void resetHandler_restoresTheCapturedBaseline() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());

        service.resetHandler(CONSOLE);

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertTrue(overrides.get(CONSOLE).isEmpty());
        assertEquals(AuditRecord.Action.REVERSION, auditLog.records().get(auditLog.records().size() - 1).action());
    }

    @Test
    void resetAllHandlers_revertsEveryActiveHandlerOverride() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        service.setHandlerLevel(file, Level.DEBUG, SetHandlerLevelOptions.defaults());

        service.resetAllHandlers();

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(Level.INFO, adapter.handlerLevel(file).orElseThrow());
    }

    @Test
    void listHandlerOverrides_reflectsSetAndResetHandler() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        assertTrue(service.listHandlerOverrides().isEmpty());

        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        service.setHandlerLevel(file, Level.DEBUG, SetHandlerLevelOptions.defaults());
        assertEquals(2, service.listHandlerOverrides().size());

        service.resetHandler(CONSOLE);
        HandlerLevelOverride remaining = service.listHandlerOverrides().get(0);
        assertEquals(file, remaining.handlerRef());
    }

    @Test
    void resetHandler_noActiveOverride_isANoOp() {
        service.resetHandler(CONSOLE); // no-op, not an error

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setHandlerLevel_supersede_keepsTheOriginalBaselineOnReset() {
        service.setHandlerLevel(CONSOLE, Level.DEBUG, SetHandlerLevelOptions.defaults());
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());

        service.resetHandler(CONSOLE);

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(),
                "reset lands on the pre-LogAperture baseline, not the DEBUG the first call captured as 'previous'");
    }

    // --- direction / capability ---------------------------------------------------------------

    @Test
    void setHandlerLevel_lower_requiresHandlerLower() {
        HandlerLevelControlService denied = newService(c -> c != Capability.HANDLER_LOWER);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults()));
        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(), "nothing changed");
    }

    @Test
    void setHandlerLevel_raise_requiresHandlerRaise() {
        HandlerLevelControlService denied = newService(c -> c != Capability.HANDLER_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setHandlerLevel(CONSOLE, Level.WARN, SetHandlerLevelOptions.defaults()));
        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(), "nothing changed");
    }

    @Test
    void resetHandler_requiresHandlerLower() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        // Same registry/adapter as `service`, so the override set above is visible to this one too.
        HandlerLevelControlService denied = new HandlerLevelControlService(adapter, baselines, overrides,
                c -> c != Capability.HANDLER_LOWER, auditLog, stateStore, "alice", "jmx");

        assertThrows(CapabilityDeniedException.class, () -> denied.resetHandler(CONSOLE));
    }

    @Test
    void setHandlerLevel_persistedTier_requiresPersist() {
        HandlerLevelControlService denied = new HandlerLevelControlService(adapter, baselines, overrides,
                c -> c == Capability.HANDLER_LOWER, auditLog, stateStore, "alice", "jmx");

        assertThrows(CapabilityDeniedException.class, () -> denied.setHandlerLevel(
                CONSOLE, Level.TRACE, SetHandlerLevelOptions.sticky()));
    }

    // --- expiry ---------------------------------------------------------------------------------

    @Test
    void sweepExpiredOverrides_revertsAPastDeadline_leavesALiveOneAlone() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.forDuration(Duration.ofMillis(1)));
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(file, Level.DEBUG, SetHandlerLevelOptions.forDuration(Duration.ofMinutes(30)));

        service.sweepExpiredOverrides(Instant.now().plusSeconds(1));

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(), "past deadline -- reverted");
        assertEquals(Level.DEBUG, adapter.handlerLevel(file).orElseThrow(), "still live -- untouched");
    }

    // --- capability direction when the handler is unresolvable (code-review finding) --------------

    @Test
    void checkSetHandlerLevelPermitted_handlerUnresolvableInThisContext_requiresNoCapability() {
        HandlerRef notHere = new HandlerRef("NOT-HERE"); // never registered with `adapter`
        HandlerLevelControlService denied = newService(CapabilityPolicy.denyAll());

        // Must not throw for either direction -- a context that doesn't have
        // the handler at all has nothing to authorize; the actual attempt
        // will fail on its own (UnknownHandlerException), not on a wrongly
        // defaulted capability requirement.
        denied.checkSetHandlerLevelPermitted(notHere, Level.TRACE, SetHandlerLevelOptions.defaults());
        denied.checkSetHandlerLevelPermitted(notHere, Level.WARN, SetHandlerLevelOptions.defaults());
    }

    @Test
    void checkSetHandlerLevelPermitted_handlerUnresolvable_stillRequiresPersistForNonSessionTier() {
        HandlerRef notHere = new HandlerRef("NOT-HERE");
        HandlerLevelControlService noPersist = new HandlerLevelControlService(adapter, baselines, overrides,
                c -> c == Capability.HANDLER_LOWER || c == Capability.HANDLER_RAISE, auditLog, stateStore,
                "alice", "jmx");

        assertThrows(CapabilityDeniedException.class, () -> noPersist.checkSetHandlerLevelPermitted(
                notHere, Level.TRACE, SetHandlerLevelOptions.sticky()));
    }

    // --- verification sweep (doc/specs/handler-floor-control.md "Reconfiguration re-application") --

    @Test
    void verifyAndReapply_driftedHandler_reappliedAndAudited() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        adapter.setHandlerLevel(CONSOLE, Level.INFO); // something else reconfigured it out from under us

        int reapplied = service.verifyAndReapply(Instant.now());

        assertEquals(1, reapplied);
        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("verification-sweep", last.source());
        assertEquals(AuditRecord.Action.MUTATION, last.action());
    }

    @Test
    void verifyAndReapply_stillCorrect_isSkippedWithNoAuditNoise() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        int before = auditLog.records().size();

        int reapplied = service.verifyAndReapply(Instant.now());

        assertEquals(0, reapplied);
        assertEquals(before, auditLog.records().size());
    }

    @Test
    void verifyAndReapply_expiredForOverride_isLeftToTheExpirySweep() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.forDuration(Duration.ofMillis(1)));
        adapter.setHandlerLevel(CONSOLE, Level.INFO); // drifted, but also expired

        int reapplied = service.verifyAndReapply(Instant.now().plusSeconds(1));

        assertEquals(0, reapplied, "an expired FOR override is the expiry sweep's job, not this one's");
        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(), "left untouched, not re-applied");
    }

    @Test
    void verifyAndReapply_vanishedHandler_isDroppedFromTracking() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        adapter.vanishHandler(CONSOLE);

        int reapplied = service.verifyAndReapply(Instant.now());

        assertEquals(0, reapplied);
        assertTrue(overrides.get(CONSOLE).isEmpty(), "dropped, not retried forever");
    }

    // --- persistence / resume --------------------------------------------------------------------

    @Test
    void setHandlerLevel_stickyTier_persistsAndResumeReapplies() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.sticky());
        assertEquals(1, stateStore.loadAllHandlers().size());

        FakeLoggingAdapter freshAdapter = new FakeLoggingAdapter(Level.INFO);
        freshAdapter.addHandler(CONSOLE, Level.INFO);
        HandlerLevelControlService resumed = new HandlerLevelControlService(freshAdapter,
                new HandlerBaselineRegistry(), new HandlerOverrideRegistry(),
                CapabilityPolicy.allowAll(), auditLog, stateStore, "alice", "resume");

        resumed.resumeFromStateStore(Instant.now());

        assertEquals(Level.TRACE, freshAdapter.handlerLevel(CONSOLE).orElseThrow());
    }

    @Test
    void setHandlerLevel_sessionTier_isNotPersisted() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertTrue(stateStore.loadAllHandlers().isEmpty());
    }

    @Test
    void resumeFromStateStore_expiredWhileStopped_isRecordedNotApplied() {
        HandlerLevelOverride expired = new HandlerLevelOverride(CONSOLE, Level.TRACE, null,
                Instant.now().minus(Duration.ofHours(1)), "jmx", PersistenceTier.FOR,
                Instant.now().minus(Duration.ofMinutes(30)));
        ((InMemoryStateStore) stateStore).saveHandler(expired);

        service.resumeFromStateStore(Instant.now());

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(), "never (re-)applied");
        assertEquals(AuditRecord.Action.REVERSION, auditLog.records().get(0).action());
        assertTrue(overrides.get(CONSOLE).isEmpty());
    }

    // --- adoptOverride (multi-context broadcast onto a fresh context) ----------------------------

    @Test
    void adoptOverride_appliesAndTracksWithoutACapabilityCheck() {
        HandlerLevelOverride override = new HandlerLevelOverride(CONSOLE, Level.TRACE, null,
                Instant.now(), "jmx", PersistenceTier.SESSION, null);
        HandlerLevelControlService noCapabilities = newService(CapabilityPolicy.denyAll());

        noCapabilities.adoptOverride(override);

        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertFalse(auditLog.records().isEmpty());
    }

    @Test
    void adoptOverride_handlerDoesNotExistInThisContext_doesNotThrow() {
        // A per-app logging profile without this handler, say -- code-review
        // finding: this must not throw, so AggregateLevelControl.addContext's
        // rebroadcast of every OTHER still-live override onto the same new
        // context isn't aborted by the one that doesn't apply here.
        HandlerRef notHere = new HandlerRef("NOT-HERE"); // never registered with `adapter`
        HandlerLevelOverride override = new HandlerLevelOverride(notHere, Level.TRACE, null,
                Instant.now(), "jmx", PersistenceTier.SESSION, null);

        service.adoptOverride(override); // must not throw

        assertTrue(overrides.get(notHere).isEmpty(), "not tracked -- it was never actually applied");
    }

    // --- no handler levels at all (Logback / none) --------------------------------------------------

    @Test
    void setHandlerLevel_adapterHasNoHandlerLevels_isANoOpNotAnError() {
        adapter.disableHandlerLevels();

        var result = service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertTrue(result.isEmpty());
        assertTrue(overrides.get(CONSOLE).isEmpty(), "nothing tracked");
        assertTrue(auditLog.records().isEmpty(), "nothing audited");
        assertTrue(stateStore.loadAllHandlers().isEmpty(), "nothing persisted");
    }

    @Test
    void setHandlerLevel_adapterHasNoHandlerLevels_needsNoCapability() {
        adapter.disableHandlerLevels();
        HandlerLevelControlService denied = new HandlerLevelControlService(adapter, baselines, overrides,
                CapabilityPolicy.denyAll(), auditLog, stateStore, "alice", "jmx");

        assertTrue(denied.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults()).isEmpty());
    }

    // --- failure handling -------------------------------------------------------------------------

    @Test
    void setHandlerLevel_adapterThrows_recordsNoOverride() {
        adapter.throwOnSetHandlerLevel(CONSOLE);

        assertThrows(RuntimeException.class,
                () -> service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults()));

        assertTrue(overrides.get(CONSOLE).isEmpty(), "no partial state");
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void resetHandler_handlerVanishedSinceSet_doesNotThrow_dropsTrackingInstead() {
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        adapter.vanishHandler(CONSOLE);

        service.resetHandler(CONSOLE); // must not throw -- code-review finding

        assertTrue(overrides.get(CONSOLE).isEmpty(), "dropped, not retried forever");
    }

    @Test
    void resetAllHandlers_oneVanishedHandlerAmongSeveral_stillRevertsTheRest() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.defaults());
        service.setHandlerLevel(file, Level.DEBUG, SetHandlerLevelOptions.defaults());
        adapter.vanishHandler(CONSOLE); // sorted-map iteration order isn't guaranteed, but this must not
                                         // stop FILE from being reverted regardless of which comes first

        service.resetAllHandlers(); // must not throw and must not abort partway through

        assertTrue(overrides.get(CONSOLE).isEmpty());
        assertTrue(overrides.get(file).isEmpty());
    }

    @Test
    void sweepExpiredOverrides_oneVanishedHandlerAmongSeveral_stillRevertsTheRest() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(CONSOLE, Level.TRACE, SetHandlerLevelOptions.forDuration(Duration.ofMillis(1)));
        service.setHandlerLevel(file, Level.DEBUG, SetHandlerLevelOptions.forDuration(Duration.ofMillis(1)));
        adapter.vanishHandler(CONSOLE);

        service.sweepExpiredOverrides(Instant.now().plusSeconds(1)); // must not throw or abort partway through

        assertTrue(overrides.get(CONSOLE).isEmpty());
        assertTrue(overrides.get(file).isEmpty());
    }

    // --- ALL_HANDLERS (doc/specs/handler-floor-control.md, issue #13) -----------------------------

    @Test
    void setHandlerLevel_allHandlers_fansOutOverEveryRealHandler() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);

        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(Level.TRACE, adapter.handlerLevel(file).orElseThrow());
    }

    @Test
    void setHandlerLevel_allHandlers_oneTrackedOverride_butNAuditRowsNamingEachReal() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);

        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());

        // Decision #3: one tracked entry for logctl status/resume/expiry...
        assertEquals(1, overrides.all().size());
        assertTrue(overrides.get(HandlerRef.ALL_HANDLERS).isPresent());
        assertTrue(overrides.get(CONSOLE).isEmpty(), "the reals are not separately tracked");
        // ...but the audit trail stays as granular as ever, one row per real handler.
        assertEquals(2, auditLog.records().size());
        assertTrue(auditLog.records().stream().anyMatch(r -> r.loggerName().equals("CONSOLE")));
        assertTrue(auditLog.records().stream().anyMatch(r -> r.loggerName().equals("FILE")));
        assertTrue(auditLog.records().stream().noneMatch(r -> r.loggerName().equals("ALL_HANDLERS")),
                "audit rows name the real handler, never the group ref");
    }

    @Test
    void resetHandler_allHandlers_revertsEachRealHandlerToItsOwnBaseline() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.DEBUG); // a different starting level than CONSOLE's INFO
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());

        service.resetHandler(HandlerRef.ALL_HANDLERS);

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow(), "back to its own baseline");
        assertEquals(Level.DEBUG, adapter.handlerLevel(file).orElseThrow(), "back to ITS own baseline, not CONSOLE's");
        assertTrue(overrides.get(HandlerRef.ALL_HANDLERS).isEmpty());
    }

    @Test
    void checkSetHandlerLevelPermitted_allHandlers_requiresTheUnionOfWhicheverCapabilitiesTheRealsNeed() {
        HandlerRef file = new HandlerRef("FILE");
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.WARN); // target DEBUG is more verbose -- CONSOLE needs a lower
        adapter.addHandler(file, Level.TRACE); // target DEBUG is less verbose -- FILE needs a raise
        HandlerLevelControlService onlyLower = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(),
                new HandlerOverrideRegistry(), c -> c == Capability.HANDLER_LOWER, auditLog, stateStore, "alice", "jmx");
        HandlerLevelControlService onlyRaise = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(),
                new HandlerOverrideRegistry(), c -> c == Capability.HANDLER_RAISE, auditLog, stateStore, "alice", "jmx");
        HandlerLevelControlService both = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(),
                new HandlerOverrideRegistry(),
                c -> c == Capability.HANDLER_LOWER || c == Capability.HANDLER_RAISE, auditLog, stateStore, "alice", "jmx");

        assertThrows(CapabilityDeniedException.class, () -> onlyLower.checkSetHandlerLevelPermitted(
                HandlerRef.ALL_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.defaults()));
        assertThrows(CapabilityDeniedException.class, () -> onlyRaise.checkSetHandlerLevelPermitted(
                HandlerRef.ALL_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.defaults()));
        both.checkSetHandlerLevelPermitted(HandlerRef.ALL_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.defaults());
    }

    @Test
    void verifyAndReapply_allHandlers_anyDriftedRealHandler_reappliesTheWholeGroupOnce() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());
        adapter.setHandlerLevel(file, Level.INFO); // something else reconfigured just FILE out from under us

        int reapplied = service.verifyAndReapply(Instant.now());

        assertEquals(1, reapplied, "one group override re-applied, not one per real handler");
        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(Level.TRACE, adapter.handlerLevel(file).orElseThrow());
    }

    @Test
    void verifyAndReapply_allHandlers_auditsOneRowPerRealHandler_notOneForTheGroup() {
        // Code-review finding: this used to write exactly one ALL_HANDLERS-
        // keyed audit row here, unlike every other ALL_HANDLERS mutation
        // path (Decision #3).
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());
        adapter.setHandlerLevel(file, Level.INFO); // something else reconfigured just FILE out from under us
        int before = auditLog.records().size();

        service.verifyAndReapply(Instant.now());

        List<AuditRecord> newRecords = auditLog.records().subList(before, auditLog.records().size());
        assertEquals(2, newRecords.size(), "one row per real handler, not one for the group");
        assertTrue(newRecords.stream().anyMatch(r -> r.loggerName().equals("CONSOLE")));
        assertTrue(newRecords.stream().anyMatch(r -> r.loggerName().equals("FILE")));
        assertTrue(newRecords.stream().noneMatch(r -> r.loggerName().equals("ALL_HANDLERS")));
    }

    @Test
    void verifyAndReapply_allHandlers_stillCorrect_isSkippedWithNoAuditNoise() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());
        int before = auditLog.records().size();

        int reapplied = service.verifyAndReapply(Instant.now());

        assertEquals(0, reapplied);
        assertEquals(before, auditLog.records().size());
    }

    @Test
    void setHandlerLevel_allHandlers_stickyTier_persistsAndResumeReappliesEveryRealHandler() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.DEBUG);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.sticky());
        assertEquals(1, stateStore.loadAllHandlers().size());

        FakeLoggingAdapter freshAdapter = new FakeLoggingAdapter(Level.INFO);
        freshAdapter.addHandler(CONSOLE, Level.INFO);
        freshAdapter.addHandler(file, Level.DEBUG);
        HandlerLevelControlService resumed = new HandlerLevelControlService(freshAdapter,
                new HandlerBaselineRegistry(), new HandlerOverrideRegistry(),
                CapabilityPolicy.allowAll(), auditLog, stateStore, "alice", "resume");

        resumed.resumeFromStateStore(Instant.now());

        assertEquals(Level.TRACE, freshAdapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(Level.TRACE, freshAdapter.handlerLevel(file).orElseThrow());
    }

    @Test
    void resetAllHandlers_coversBothAGroupOverrideAndAnIndividualOne() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        HandlerRef other = new HandlerRef("OTHER");
        adapter.addHandler(other, Level.INFO);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());
        service.setHandlerLevel(other, Level.DEBUG, SetHandlerLevelOptions.defaults());

        service.resetAllHandlers();

        assertEquals(Level.INFO, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(Level.INFO, adapter.handlerLevel(file).orElseThrow());
        assertEquals(Level.INFO, adapter.handlerLevel(other).orElseThrow());
        assertTrue(service.listHandlerOverrides().isEmpty());
    }

    @Test
    void resetHandler_allHandlers_oneVanishedRealAmongSeveral_stillRevertsTheRest() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());
        adapter.vanishHandler(CONSOLE);

        service.resetHandler(HandlerRef.ALL_HANDLERS); // must not throw or abort partway through

        assertEquals(Level.INFO, adapter.handlerLevel(file).orElseThrow());
        assertTrue(overrides.get(HandlerRef.ALL_HANDLERS).isEmpty());
    }

    @Test
    void resumeFromStateStore_allHandlers_auditsOneRowPerRealHandler_notOneForTheGroup() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.DEBUG);
        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.sticky());

        FakeLoggingAdapter freshAdapter = new FakeLoggingAdapter(Level.INFO);
        freshAdapter.addHandler(CONSOLE, Level.INFO);
        freshAdapter.addHandler(file, Level.DEBUG);
        InMemoryAuditLog resumeAuditLog = new InMemoryAuditLog();
        HandlerLevelControlService resumed = new HandlerLevelControlService(freshAdapter,
                new HandlerBaselineRegistry(), new HandlerOverrideRegistry(),
                CapabilityPolicy.allowAll(), resumeAuditLog, stateStore, "alice", "resume");

        resumed.resumeFromStateStore(Instant.now());

        assertEquals(2, resumeAuditLog.records().size(), "one row per real handler, not one for the group");
        assertTrue(resumeAuditLog.records().stream().anyMatch(r -> r.loggerName().equals("CONSOLE")));
        assertTrue(resumeAuditLog.records().stream().anyMatch(r -> r.loggerName().equals("FILE")));
        assertTrue(resumeAuditLog.records().stream().noneMatch(r -> r.loggerName().equals("ALL_HANDLERS")));
    }

    @Test
    void adoptOverride_allHandlers_auditsOneRowPerRealHandler_notOneForTheGroup() {
        HandlerRef file = new HandlerRef("FILE");
        adapter.addHandler(file, Level.INFO);
        HandlerLevelOverride groupOverride = new HandlerLevelOverride(HandlerRef.ALL_HANDLERS, Level.TRACE, null,
                Instant.now(), "jmx", PersistenceTier.SESSION, null);

        service.adoptOverride(groupOverride);

        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertEquals(Level.TRACE, adapter.handlerLevel(file).orElseThrow());
        assertEquals(2, auditLog.records().size(), "one row per real handler, not one for the group");
        assertTrue(auditLog.records().stream().anyMatch(r -> r.loggerName().equals("CONSOLE")));
        assertTrue(auditLog.records().stream().anyMatch(r -> r.loggerName().equals("FILE")));
        assertTrue(auditLog.records().stream().noneMatch(r -> r.loggerName().equals("ALL_HANDLERS")));
    }

    @Test
    void setHandlerLevel_allHandlers_supersedesAStaleIndividualOverrideOnTheSameRealHandler() {
        // Code-review finding: CONSOLE set individually (sticky, so its
        // persisted state is exercised too), then ALL_HANDLERS also touches
        // CONSOLE -- the individual entry must not linger and disagree with
        // reality in `logctl status`.
        service.setHandlerLevel(CONSOLE, Level.DEBUG, SetHandlerLevelOptions.sticky());
        assertEquals(1, stateStore.loadAllHandlers().size());

        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertEquals(Level.TRACE, adapter.handlerLevel(CONSOLE).orElseThrow());
        assertTrue(overrides.get(CONSOLE).isEmpty(), "the stale individual override is gone");
        assertTrue(overrides.get(HandlerRef.ALL_HANDLERS).isPresent());
        assertEquals(1, service.listHandlerOverrides().size(), "exactly one override governs CONSOLE now");
        assertTrue(stateStore.loadAllHandlers().stream().noneMatch(o -> o.handlerRef().equals(CONSOLE)),
                "the stale individual override's persisted state is cleared too");
    }
}
