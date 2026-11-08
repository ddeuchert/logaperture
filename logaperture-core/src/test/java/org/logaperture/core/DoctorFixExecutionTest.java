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
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Level;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * doc/specs/doctor-verbosity-fix.md D6: the command {@code doctor} suggests for a chatty logger is run for real,
 * against the real services, and the finding must be gone. A suggestion that names a command which then changes
 * nothing is the bug (#345), so each case executes it rather than comparing text.
 */
class DoctorFixExecutionTest {

    private static final String LOGGER = "org.hibernate";

    private FakeLoggingAdapter adapter;
    private LevelControlService service;
    private DoctorService doctor;

    /** The application's configured level at start, and the vendor defaults file's (null: none for either). */
    private void server(Level configured, Level vendor) {
        adapter = new FakeLoggingAdapter(Level.INFO);
        if (configured != null) {
            adapter.setConfiguredLevel(LOGGER, configured);
        } else {
            adapter.addKnownLogger(LOGGER);
        }
        BaselineRegistry baselines = vendor == null ? new BaselineRegistry() : new BaselineRegistry(Map.of(LOGGER, vendor));
        OverrideRegistry overrides = new OverrideRegistry();
        baselines.captureIfAbsent(LOGGER, adapter);
        service = new LevelControlService(adapter, baselines, overrides, CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), new InMemoryStateStore(), "alice", "jmx");
        service.applyVendorDefaults(Instant.now());
        doctor = new DoctorService(adapter, CapabilityPolicy.allowAll(), List::of,
                ResetEffect.resolver(overrides, baselines));
    }

    private DoctorFinding chattyFinding() {
        return doctor.diagnose().stream()
                .filter(f -> f.check().equals("logger.verbosity-left-on") && LOGGER.equals(f.subject()))
                .findFirst().orElseThrow(() -> new AssertionError("expected a verbosity finding for " + LOGGER));
    }

    /** Runs what doctor suggested, as the user would type it. */
    private void run(String suggestedFix) {
        if (suggestedFix.equals("logctl reset logger " + LOGGER)) {
            service.resetLogger(LOGGER, false);
        } else if (suggestedFix.equals("logctl set logger " + LOGGER + " INFO sticky")) {
            service.setLogger(LOGGER, Level.INFO, SetLevelOptions.sticky());
        } else {
            fail("unexpected suggestion: " + suggestedFix);
        }
    }

    private void assertFixedBy(String expectedFix) {
        DoctorFinding finding = chattyFinding();
        assertEquals(expectedFix, finding.suggestedFix());
        run(finding.suggestedFix());
        assertTrue(doctor.diagnose().stream().noneMatch(f -> f.check().equals("logger.verbosity-left-on")
                        && f.severity() == Severity.WARNING),
                "the suggested command did not clear the finding; the logger is at " + adapter.effectiveLevel(LOGGER));
    }

    private static final String RESET = "logctl reset logger " + LOGGER;
    private static final String SET_INFO = "logctl set logger " + LOGGER + " INFO sticky";

    @Test
    void anOverrideOverAnInfoBaseline_isFixedByReset() {
        server(Level.INFO, null);
        service.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.withReason("probe"));
        assertFixedBy(RESET);
    }

    @Test
    void aLevelTheConfigurationSets_isFixedByTurningItDown() {
        server(Level.DEBUG, null);
        assertFixedBy(SET_INFO);
    }

    @Test
    void anOverrideOverAChattyConfiguredLevel_isFixedByTurningItDown_notByAResetBackToDebug() {
        server(Level.DEBUG, null);
        service.setLogger(LOGGER, Level.TRACE, SetLevelOptions.withReason("probe"));
        assertFixedBy(SET_INFO);
    }

    @Test
    void aChattyVendorLevel_isFixedByTurningItDown() {
        server(Level.INFO, Level.DEBUG);
        assertFixedBy(SET_INFO);
    }

    @Test
    void aVendorLevelResetToNativeThenOverridden_isFixedByTurningItDown_notByAResetBackToTheVendorDebug() {
        server(Level.INFO, Level.DEBUG);
        service.resetLogger(LOGGER, false, true); // --to-native: the vendor DEBUG is ignored until restart
        service.setLogger(LOGGER, Level.TRACE, SetLevelOptions.withReason("probe"));
        assertFixedBy(SET_INFO);
    }

    @Test
    void aLevelTheApplicationRaisedInCode_isFixedByTurningItDown_becauseResetWouldDoNothing() {
        server(Level.INFO, null);
        adapter.setConfiguredLevel(LOGGER, Level.DEBUG); // set directly in the framework, no logctl change
        assertTrue(service.resetLogger(LOGGER, false).revertedLoggerNames().isEmpty(),
                "a reset has nothing to undo here");
        assertFixedBy(SET_INFO);
    }

    @Test
    void aLevelTheApplicationRaisedInCode_overANotChattyVendorLevel_isFixedByTurningItDown() {
        server(Level.INFO, Level.INFO);
        adapter.setConfiguredLevel(LOGGER, Level.DEBUG);
        assertFixedBy(SET_INFO);
    }
}
