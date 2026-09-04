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
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetLevelOptions;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature 2's own behavioral coverage, mirroring {@link
 * LevelControlServiceTest}'s style — see doc/specs/persistence.md
 * "Testing".
 */
class PersistenceServiceTest {

    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private OverrideRegistry overrides;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
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

    // --- tiers persist (or don't) -------------------------------------------------------------

    @Test
    void setLevel_sessionTier_neverReachesTheStateStore() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        assertTrue(stateStore.loadAll().isEmpty());
    }

    @Test
    void setLevel_forTier_persistsWithAbsoluteExpiresAt() {
        Instant before = Instant.now();

        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(30)));

        LevelOverride persisted = stateStore.loadAll().get(0);
        assertEquals(PersistenceTier.FOR, persisted.tier());
        assertTrue(persisted.expiresAt().isAfter(before.plus(Duration.ofMinutes(29))));
        assertTrue(persisted.expiresAt().isBefore(before.plus(Duration.ofMinutes(31))));
    }

    @Test
    void setLevel_stickyTier_persistsWithNoExpiresAt() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.sticky());

        LevelOverride persisted = stateStore.loadAll().get(0);
        assertEquals(PersistenceTier.STICKY, persisted.tier());
        assertEquals(null, persisted.expiresAt());
    }

    @Test
    void resetLevel_removesAPersistedOverrideFromTheStateStore() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.sticky());

        service.resetLevel("com.acme.Worker");

        assertTrue(stateStore.loadAll().isEmpty());
    }

    @Test
    void resetAll_removesEveryPersistedOverrideFromTheStateStore() {
        service.setLevel("com.acme.A", Level.DEBUG, SetLevelOptions.sticky());
        service.setLevel("com.acme.B", Level.TRACE, SetLevelOptions.forDuration(Duration.ofMinutes(5)));

        service.resetAll();

        assertTrue(stateStore.loadAll().isEmpty());
    }

    @Test
    void setLevel_sessionTier_afterAPreviousStickyOverride_clearsTheStalePersistedEntry() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.sticky());
        assertFalse(stateStore.loadAll().isEmpty());

        // A later plain (SESSION) setLevel on the same logger supersedes the
        // sticky one -- the stale disk entry must not survive to reappear
        // on the next restart (code-review finding against this PR).
        service.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.defaults());

        assertTrue(stateStore.loadAll().isEmpty());
    }

    // --- persist capability ---------------------------------------------------------------------

    @Test
    void setLevel_forTier_deniedWithoutPersistCapability_throwsBeforeMutating() {
        LevelControlService denied = newService(capability -> capability != Capability.PERSIST);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(5))));

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker"));
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(stateStore.loadAll().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_stickyTier_deniedWithoutPersistCapability_throwsBeforeMutating() {
        LevelControlService denied = newService(capability -> capability != Capability.PERSIST);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.sticky()));

        assertTrue(overrides.get("com.acme.Worker").isEmpty());
    }

    @Test
    void setLevel_sessionTier_doesNotRequirePersistCapability() {
        LevelControlService noPersist = newService(capability -> capability != Capability.PERSIST);

        noPersist.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults()); // must not throw

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
    }

    // --- resume on restart -----------------------------------------------------------------------

    @Test
    void resumeFromStateStore_stickyOverride_alwaysReapplies() {
        Instant appliedAt = Instant.now().minus(Duration.ofDays(1));
        stateStore.save(new LevelOverride(
                "com.acme.Payments", Level.WARN, true, "known-noisy", appliedAt, "jmx", PersistenceTier.STICKY, null));

        service.resumeFromStateStore(Instant.now());

        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Payments"));
        assertTrue(overrides.get("com.acme.Payments").isPresent());
        AuditRecord record = auditLog.records().get(0);
        assertEquals("resume", record.source());
        assertEquals(AuditRecord.Action.MUTATION, record.action());
    }

    @Test
    void resumeFromStateStore_unexpiredForOverride_reappliesWithReducedRemainingTime() {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(10)); // 10 minutes still remain
        stateStore.save(new LevelOverride(
                "com.acme.Worker", Level.DEBUG, false, "triage", now.minus(Duration.ofMinutes(20)), "jmx",
                PersistenceTier.FOR, expiresAt));

        service.resumeFromStateStore(now);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
        LevelOverride resumed = overrides.get("com.acme.Worker").orElseThrow();
        assertEquals(expiresAt, resumed.expiresAt()); // absolute deadline carried over unchanged

        // The general sweep (not a per-override timer) picks this up once its
        // unchanged absolute deadline arrives -- proven by sweeping "later".
        service.sweepExpiredOverrides(expiresAt.plusSeconds(1));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void resumeFromStateStore_expiredForOverride_neverAppliedButRecordedAndRemoved() {
        Instant now = Instant.now();
        Instant expiresAt = now.minus(Duration.ofMinutes(1)); // expired while this JVM was down
        stateStore.save(new LevelOverride(
                "com.acme.Worker", Level.DEBUG, false, "triage", now.minus(Duration.ofMinutes(31)), "jmx",
                PersistenceTier.FOR, expiresAt));

        service.resumeFromStateStore(now);

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker")); // never reapplied
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(stateStore.loadAll().isEmpty()); // dropped, doesn't reappear on a later resume

        AuditRecord record = auditLog.records().get(0);
        assertEquals("resume", record.source());
        assertEquals(AuditRecord.Action.REVERSION, record.action());
    }

    @Test
    void resumeFromStateStore_oneBadEntry_doesNotAbortResumingTheRest() {
        Instant appliedAt = Instant.now().minus(Duration.ofDays(1));
        stateStore.save(new LevelOverride(
                "com.acme.Bad", Level.DEBUG, false, null, appliedAt, "jmx", PersistenceTier.STICKY, null));
        stateStore.save(new LevelOverride(
                "com.acme.Good", Level.WARN, false, null, appliedAt, "jmx", PersistenceTier.STICKY, null));
        adapter.throwOnApply("com.acme.Bad"); // simulates a bad entry blowing up mid-resume

        service.resumeFromStateStore(Instant.now()); // must not throw

        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Good")); // the rest still resumed
        assertTrue(overrides.get("com.acme.Bad").isEmpty());
    }

    // --- expiry sweep ------------------------------------------------------------------------------

    @Test
    void sweepExpiredOverrides_revertsAndRemovesFromTheStateStoreWithAnExpirySweepAuditRecord() {
        Instant now = Instant.now();
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(1)));

        service.sweepExpiredOverrides(now.plus(Duration.ofMinutes(2)));

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker"));
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(stateStore.loadAll().isEmpty());

        AuditRecord record = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("expiry-sweep", record.source());
        assertEquals(AuditRecord.Action.REVERSION, record.action());
    }

    @Test
    void sweepExpiredOverrides_notYetDue_leftUntouched() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(30)));

        service.sweepExpiredOverrides(Instant.now()); // nowhere near the 30-minute deadline

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
        assertTrue(overrides.get("com.acme.Worker").isPresent());
    }

    @Test
    void sweepExpiredOverrides_ignoresStickyAndSessionOverrides() {
        service.setLevel("com.acme.Sticky", Level.DEBUG, SetLevelOptions.sticky());
        service.setLevel("com.acme.Session", Level.TRACE, SetLevelOptions.defaults());

        service.sweepExpiredOverrides(Instant.now().plus(Duration.ofDays(365)));

        assertTrue(overrides.get("com.acme.Sticky").isPresent());
        assertTrue(overrides.get("com.acme.Session").isPresent());
    }

    // --- chaos: a misbehaving StateStore ------------------------------------------------------------

    @Test
    void setLevel_stateStoreThrows_stillSucceedsInMemoryForThisSession() {
        stateStore.throwOnSave(new RuntimeException("disk full"));

        LevelOverride override = service.setLevel(
                "com.acme.Worker", Level.DEBUG, SetLevelOptions.sticky()).override(); // must not throw

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
        assertTrue(overrides.get("com.acme.Worker").isPresent());
        assertEquals(Level.DEBUG, override.level());
        assertFalse(auditLog.records().isEmpty()); // the mutation is still audited
    }
}
