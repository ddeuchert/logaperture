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

import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.StormReport;
import org.logaperture.api.StormStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storm detection on and off -- see doc/specs/storm-detection-toggle.md "Testing". */
class StormDetectionToggleTest {

    private static final long THRESHOLD = 3;

    private final List<AuditRecord> audit = new ArrayList<>();
    private final FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);

    private StormDetectionSwitch detectionSwitch(boolean startedEnabled, CapabilityPolicy policy) {
        return new StormDetectionSwitch(startedEnabled, policy, audit::add, "alice");
    }

    private static StormDetector detector(StormDetectionSwitch detectionSwitch, Duration quiet) {
        return new StormDetector(THRESHOLD, Duration.ofSeconds(10), quiet, 4_000, 100, 8 * 1024,
                1_024, detectionSwitch);
    }

    @Test
    void startsInTheAgentArgumentsPosition_neverChanged() {
        StormDetectionSwitch.State off = detectionSwitch(false, CapabilityPolicy.allowAll()).state();
        StormDetectionSwitch.State on = detectionSwitch(true, CapabilityPolicy.allowAll()).state();

        assertEquals(new StormDetectionSwitch.State(false, false, null), off);
        assertEquals(new StormDetectionSwitch.State(true, true, null), on);
    }

    @Test
    void set_changesThePosition_andWritesOneAuditRecord() {
        StormDetectionSwitch detectionSwitch = detectionSwitch(false, CapabilityPolicy.allowAll());

        StormDetectionSwitch.Change change = detectionSwitch.set(true, "INC-4411");

        assertTrue(change.enabled());
        assertFalse(change.previous());
        assertTrue(change.changed());
        assertNotNull(change.changedAt());
        assertTrue(detectionSwitch.isEnabled());
        assertEquals(change.changedAt(), detectionSwitch.state().changedAt());
        assertEquals(1, audit.size());
        AuditRecord record = audit.get(0);
        assertEquals(AuditRecord.Target.SWITCH, record.target());
        assertEquals("storm-detection", record.loggerName());
        assertEquals("off", record.previousValue());
        assertEquals("on", record.newValue());
        assertEquals("INC-4411", record.reason());
        assertEquals("alice", record.principal());
        assertEquals(AuditRecord.Action.MUTATION, record.action());
    }

    @Test
    void set_toThePositionItAlreadyHas_changesNothing_andWritesNoAuditRecord() {
        StormDetectionSwitch detectionSwitch = detectionSwitch(true, CapabilityPolicy.allowAll());

        StormDetectionSwitch.Change change = detectionSwitch.set(true, null);

        assertFalse(change.changed());
        assertNull(change.changedAt());
        assertTrue(audit.isEmpty());
    }

    @Test
    void set_needsTheDiagnosticsCapability_andState_needsView() {
        CapabilityPolicy allButDiagnostics = capability -> capability != Capability.DIAGNOSTICS;
        StormDetectionSwitch detectionSwitch = detectionSwitch(false, allButDiagnostics);

        assertThrows(CapabilityDeniedException.class, () -> detectionSwitch.set(true, null));
        assertFalse(detectionSwitch.isEnabled());
        assertTrue(audit.isEmpty());
        assertThrows(CapabilityDeniedException.class,
                () -> detectionSwitch(false, CapabilityPolicy.denyAll()).state());
    }

    @Test
    void disabled_theDetectorIsInactive_andRecordsNothing() {
        StormDetectionSwitch detectionSwitch = detectionSwitch(false, CapabilityPolicy.allowAll());
        StormDetector detector = detector(detectionSwitch, Duration.ofSeconds(60));
        StormService service = new StormService(adapter, CapabilityPolicy.allowAll(), detector, detectionSwitch);
        service.startDetection();

        feed(detector, "a.Logger", 10);

        assertFalse(detector.isActive());
        assertEquals(1, adapter.installStormDetectionCallCount(), "installed in both positions (T5)");
        StormReport report = service.activeStorms(0);
        assertEquals(0, report.trackedCount());
        assertNull(report.measurementStartedAt(), "no window until it's enabled");
    }

    @Test
    void enabling_startsANewWindow_inEveryContextSharingTheSwitch() {
        StormDetectionSwitch detectionSwitch = detectionSwitch(true, CapabilityPolicy.allowAll());
        StormDetector first = detector(detectionSwitch, Duration.ofSeconds(60));
        StormDetector second = detector(detectionSwitch, Duration.ofSeconds(60));
        StormService one = new StormService(adapter, CapabilityPolicy.allowAll(), first, detectionSwitch);
        StormService two = new StormService(adapter, CapabilityPolicy.allowAll(), second, detectionSwitch);
        one.startDetection();
        two.startDetection();
        feed(first, "a.Logger", 5);
        feed(second, "b.Logger", 5);
        Instant firstWindow = one.activeStorms(0).measurementStartedAt();

        detectionSwitch.set(false, null);
        detectionSwitch.set(true, null);

        assertEquals(0, one.activeStorms(0).trackedCount());
        assertEquals(0, two.activeStorms(0).trackedCount());
        assertFalse(one.activeStorms(0).measurementStartedAt().isBefore(firstWindow));
        feed(first, "a.Logger", 5);
        assertEquals(1, one.activeStorms(0).trackedCount(), "records again once enabled");
    }

    @Test
    void disabling_freezesWhatWasTracked_anOngoingStormStaysOngoing() throws InterruptedException {
        StormDetectionSwitch detectionSwitch = detectionSwitch(true, CapabilityPolicy.allowAll());
        StormDetector detector = detector(detectionSwitch, Duration.ofMillis(1));
        StormService service = new StormService(adapter, CapabilityPolicy.allowAll(), detector, detectionSwitch);
        service.startDetection();
        feed(detector, "a.Logger", 5);

        detectionSwitch.set(false, null);
        Thread.sleep(20); // well past the 1 ms quiet period: enabled, a report would end the storm

        StormReport report = service.activeStorms(0);
        assertEquals(1, report.trackedCount());
        assertEquals(StormStatus.ONGOING, report.storms().get(0).status());
        assertEquals(1, report.ongoingCount());
    }

    // --- slice 2: for and sticky (doc/specs/storm-detection-toggle.md "Slice 2") ----------------------

    private final InMemoryStateStore store = new InMemoryStateStore();

    private StormDetectionSwitch savingSwitch(boolean startedEnabled, CapabilityPolicy policy) {
        return new StormDetectionSwitch(startedEnabled, policy, audit::add, "alice", store);
    }

    @Test
    void sticky_isSaved_andASessionSettingAfterItRemovesTheSavedEntry() {
        StormDetectionSwitch detectionSwitch = savingSwitch(false, CapabilityPolicy.allowAll());

        StormDetectionSwitch.Change sticky = detectionSwitch.set(true, "keep watching", PersistenceTier.STICKY, null);

        assertEquals(PersistenceTier.STICKY, sticky.tier());
        StormDetectionSetting saved = store.loadStormDetection().orElseThrow();
        assertTrue(saved.enabled());
        assertEquals(PersistenceTier.STICKY, saved.tier());
        assertEquals("keep watching", saved.reason());

        detectionSwitch.set(true, null); // same position, session tier: a change, and the entry goes (T13)

        assertTrue(store.loadStormDetection().isEmpty());
        assertEquals(PersistenceTier.SESSION, detectionSwitch.state().tier());
        assertEquals(2, audit.size());
    }

    @Test
    void sameStickyAgain_isANoOp_butForAlwaysSetsANewDeadline() {
        StormDetectionSwitch detectionSwitch = savingSwitch(false, CapabilityPolicy.allowAll());
        detectionSwitch.set(true, null, PersistenceTier.STICKY, null);

        assertFalse(detectionSwitch.set(true, null, PersistenceTier.STICKY, null).changed());
        assertTrue(detectionSwitch.set(true, null, PersistenceTier.FOR, Duration.ofMinutes(30)).changed());
        assertTrue(detectionSwitch.set(true, null, PersistenceTier.FOR, Duration.ofMinutes(30)).changed());
    }

    @Test
    void forAndSticky_needPersistToo() {
        CapabilityPolicy noPersist = capability -> capability != Capability.PERSIST;
        StormDetectionSwitch detectionSwitch = savingSwitch(false, noPersist);

        assertThrows(CapabilityDeniedException.class,
                () -> detectionSwitch.set(true, null, PersistenceTier.STICKY, null));
        assertThrows(CapabilityDeniedException.class,
                () -> detectionSwitch.set(true, null, PersistenceTier.FOR, Duration.ofMinutes(5)));
        assertTrue(detectionSwitch.set(true, null).changed(), "session needs no persist");
    }

    @Test
    void forDuration_switchesToTheOtherPosition_atTheDeadline_asASessionSetting() {
        StormDetectionSwitch detectionSwitch = savingSwitch(true, CapabilityPolicy.allowAll());
        StormDetectionSwitch.Change change =
                detectionSwitch.set(false, null, PersistenceTier.FOR, Duration.ofMinutes(5));
        audit.clear();

        detectionSwitch.sweepExpired(change.expiresAt().minusSeconds(1));
        assertFalse(detectionSwitch.isEnabled(), "not yet");

        detectionSwitch.sweepExpired(change.expiresAt());

        assertTrue(detectionSwitch.isEnabled(), "disable for 5m: enabled again after");
        assertEquals(PersistenceTier.SESSION, detectionSwitch.state().tier());
        assertNull(detectionSwitch.state().expiresAt());
        assertTrue(store.loadStormDetection().isEmpty());
        assertEquals(1, audit.size());
        assertEquals("expiry-sweep", audit.get(0).source());
        assertEquals(AuditRecord.Action.REVERSION, audit.get(0).action());
        assertEquals("off", audit.get(0).previousValue());
        assertEquals("on", audit.get(0).newValue());
    }

    @Test
    void forDuration_flipToEnabled_startsANewWindow() {
        StormDetectionSwitch detectionSwitch = savingSwitch(true, CapabilityPolicy.allowAll());
        StormDetector detector = detector(detectionSwitch, Duration.ofSeconds(60));
        StormService service = new StormService(adapter, CapabilityPolicy.allowAll(), detector, detectionSwitch);
        service.startDetection();
        feed(detector, "a.Logger", 5);
        StormDetectionSwitch.Change change =
                detectionSwitch.set(false, null, PersistenceTier.FOR, Duration.ofMinutes(5));

        detectionSwitch.sweepExpired(change.expiresAt());

        assertEquals(0, service.activeStorms(0).trackedCount());
    }

    @Test
    void aNewSettingBeforeTheDeadline_cancelsTheFlip() {
        StormDetectionSwitch detectionSwitch = savingSwitch(false, CapabilityPolicy.allowAll());
        StormDetectionSwitch.Change timed = detectionSwitch.set(true, null, PersistenceTier.FOR, Duration.ofMinutes(5));
        detectionSwitch.set(true, null);

        detectionSwitch.sweepExpired(timed.expiresAt().plusSeconds(60));

        assertTrue(detectionSwitch.isEnabled());
    }

    @Test
    void resume_aStickySetting_winsOverTheAgentArgument() {
        store.saveStormDetection(new StormDetectionSetting(true, PersistenceTier.STICKY, null, "keep watching",
                Instant.parse("2026-10-08T02:00:00Z"), "jmx"));
        StormDetectionSwitch detectionSwitch = savingSwitch(false, CapabilityPolicy.allowAll());

        detectionSwitch.resume(Instant.parse("2026-10-09T00:00:00Z"));

        StormDetectionSwitch.State state = detectionSwitch.state();
        assertTrue(state.enabled());
        assertFalse(state.startedEnabled());
        assertEquals(PersistenceTier.STICKY, state.tier());
        assertEquals(Instant.parse("2026-10-08T02:00:00Z"), state.changedAt());
        assertEquals(1, audit.size());
        assertEquals("resume", audit.get(0).source());
        assertEquals("keep watching", audit.get(0).reason());
    }

    @Test
    void resume_aForSettingWithTimeLeft_isApplied_andFlipsAtTheOriginalDeadline() {
        Instant deadline = Instant.parse("2026-10-08T02:30:00Z");
        store.saveStormDetection(new StormDetectionSetting(false, PersistenceTier.FOR, deadline, null,
                Instant.parse("2026-10-08T02:00:00Z"), "jmx"));
        StormDetectionSwitch detectionSwitch = savingSwitch(true, CapabilityPolicy.allowAll());

        detectionSwitch.resume(Instant.parse("2026-10-08T02:10:00Z"));
        assertFalse(detectionSwitch.isEnabled());
        assertEquals(deadline, detectionSwitch.state().expiresAt());

        detectionSwitch.sweepExpired(deadline);
        assertTrue(detectionSwitch.isEnabled());
    }

    @Test
    void resume_aForSettingThatExpiredWhileStopped_isNotApplied_isAudited_andRemoved() {
        store.saveStormDetection(new StormDetectionSetting(true, PersistenceTier.FOR,
                Instant.parse("2026-10-08T02:30:00Z"), null, Instant.parse("2026-10-08T02:00:00Z"), "jmx"));
        StormDetectionSwitch detectionSwitch = savingSwitch(false, CapabilityPolicy.allowAll());

        detectionSwitch.resume(Instant.parse("2026-10-08T09:00:00Z"));

        assertFalse(detectionSwitch.isEnabled(), "the agent argument decides");
        assertNull(detectionSwitch.state().changedAt());
        assertTrue(store.loadStormDetection().isEmpty());
        assertEquals(1, audit.size());
        assertEquals(AuditRecord.Action.REVERSION, audit.get(0).action());
        assertEquals("resume", audit.get(0).source());
    }

    @Test
    void resume_withNothingSaved_changesNothing() {
        StormDetectionSwitch detectionSwitch = savingSwitch(true, CapabilityPolicy.allowAll());

        detectionSwitch.resume(Instant.now());

        assertEquals(new StormDetectionSwitch.State(true, true, null), detectionSwitch.state());
        assertTrue(audit.isEmpty());
    }

    private static void feed(StormDetector detector, String logger, int times) {
        for (int i = 0; i < times; i++) {
            detector.observe(new StormObservation(logger, Level.ERROR, null, "boom " + logger, false, null,
                    () -> "rendered", Instant.now()));
        }
    }
}
