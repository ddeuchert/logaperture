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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "The baseline layer" -- "Loggers". */
class VendorLoggerDefaultsTest {

    private static final String HIBERNATE = "org.hibernate.SQL";
    private static final String NOT_YET_CREATED = "com.acme.support";

    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private OverrideRegistry overrides;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private LevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.setConfiguredLevel(HIBERNATE, Level.DEBUG); // the application's own, noisy setting
        baselines = new BaselineRegistry(Map.of(HIBERNATE, Level.WARN, NOT_YET_CREATED, Level.TRACE));
        overrides = new OverrideRegistry();
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = new LevelControlService(adapter, baselines, overrides, CapabilityPolicy.allowAll(), auditLog,
                stateStore, "alice", "jmx");
    }

    @Test
    void applyVendorDefaults_setsEachLogger_andKeepsTheNativeBaselineSeparate() {
        service.applyVendorDefaults(Instant.now());

        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE));
        assertEquals(Level.TRACE, adapter.effectiveLevel(NOT_YET_CREATED), "applied eagerly (Decision M1)");
        assertEquals(Optional.of(Level.DEBUG), baselines.nativeLevel(HIBERNATE),
                "native captured before the vendor level was applied");
        assertEquals(Optional.empty(), baselines.nativeLevel(NOT_YET_CREATED));
        assertEquals(Optional.of(Level.WARN), baselines.get(HIBERNATE), "the effective baseline is the vendor's");

        List<AuditRecord> records = auditLog.records();
        assertEquals(2, records.size());
        AuditRecord first = records.stream().filter(r -> r.loggerName().equals(HIBERNATE)).findFirst().orElseThrow();
        assertEquals("vendor-defaults", first.source());
        assertEquals("DEBUG", first.previousValue());
        assertEquals("WARN", first.newValue());
    }

    @Test
    void resetLogger_landsOnTheVendorLevel_notTheNativeOne() {
        service.applyVendorDefaults(Instant.now());
        service.setLogger(HIBERNATE, Level.TRACE, SetLevelOptions.defaults());
        assertEquals(Level.TRACE, adapter.effectiveLevel(HIBERNATE), "an operator override wins");

        service.resetLogger(HIBERNATE, false);

        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE));
        assertEquals("WARN", auditLog.records().get(auditLog.records().size() - 1).newValue());
    }

    @Test
    void resetAllLoggers_andExpiry_landOnTheVendorLevel() {
        service.applyVendorDefaults(Instant.now());
        service.setLogger(HIBERNATE, Level.TRACE, SetLevelOptions.forDuration(Duration.ofMinutes(1)));
        service.setLogger(NOT_YET_CREATED, Level.ERROR, SetLevelOptions.defaults());

        service.sweepExpiredOverrides(Instant.now().plus(Duration.ofMinutes(2)));
        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE), "expiry lands on the vendor level");

        service.resetAllLoggers(false);
        assertEquals(Level.TRACE, adapter.effectiveLevel(NOT_YET_CREATED));
    }

    @Test
    void resetLogger_withNoOverride_isStillANoOp() {
        service.applyVendorDefaults(Instant.now());

        assertTrue(service.resetLogger(HIBERNATE, false).revertedLoggerNames().isEmpty());
        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE));
    }

    @Test
    void persistedOverrides_resumeOnTopOfTheVendorLayer() {
        stateStore.save(new LevelOverride(HIBERNATE, Level.ERROR, "customer's sticky", Instant.now(), "jmx",
                PersistenceTier.STICKY, null));

        service.applyVendorDefaults(Instant.now());
        service.resumeFromStateStore(Instant.now());

        assertEquals(Level.ERROR, adapter.effectiveLevel(HIBERNATE), "persisted user state beats the file (#9)");
        service.resetLogger(HIBERNATE, true);
        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE));
    }

    @Test
    void reapplyActiveOverrides_restoresTheVendorLayerUnderOverrides() {
        service.applyVendorDefaults(Instant.now());
        service.setLogger(NOT_YET_CREATED, Level.ERROR, SetLevelOptions.defaults());
        // A framework reset puts the application's own configuration back.
        adapter.applyLevel(HIBERNATE, Level.DEBUG);
        adapter.applyLevel(NOT_YET_CREATED, null);

        service.reapplyActiveOverrides(adapter);

        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE));
        assertEquals(Level.ERROR, adapter.effectiveLevel(NOT_YET_CREATED), "the override, not the vendor level");
    }

    @Test
    void verificationSweep_reappliesADriftedVendorLevel_andIsQuietOtherwise() {
        service.applyVendorDefaults(Instant.now());
        int auditsAfterApply = auditLog.records().size();

        assertEquals(0, service.verifyAndReapply(Instant.now()), "nothing drifted");
        assertEquals(auditsAfterApply, auditLog.records().size());

        adapter.applyLevel(HIBERNATE, Level.DEBUG); // e.g. a WildFly :reload re-read standalone.xml
        assertEquals(1, service.verifyAndReapply(Instant.now()));
        assertEquals(Level.WARN, adapter.effectiveLevel(HIBERNATE));
        AuditRecord reapplied = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("verification-sweep", reapplied.source());
        assertEquals("DEBUG", reapplied.previousValue());
    }

    @Test
    void verificationSweep_leavesAVendorLoggerWithAnOverrideToTheOverrideHalf() {
        service.applyVendorDefaults(Instant.now());
        service.setLogger(HIBERNATE, Level.TRACE, SetLevelOptions.defaults());

        assertEquals(0, service.verifyAndReapply(Instant.now()));
        assertEquals(Level.TRACE, adapter.effectiveLevel(HIBERNATE));
    }

    @Test
    void listLoggers_showsNativeAsConfigured_andTheVendorLevelSeparately() {
        service.applyVendorDefaults(Instant.now());

        LoggerInfo hibernate = find(service.listLoggers(null), HIBERNATE);
        assertEquals(Level.DEBUG, hibernate.configuredLevel());
        assertEquals(Level.WARN, hibernate.vendorDefaultLevel());
        assertEquals(Level.WARN, hibernate.effectiveLevel());

        assertNull(find(service.listLoggers(null), "ROOT").vendorDefaultLevel());
    }

    @Test
    void applyVendorDefaults_oneFailingLogger_doesNotStopTheRest() {
        adapter.throwOnApply(HIBERNATE);

        service.applyVendorDefaults(Instant.now());

        assertEquals(Level.TRACE, adapter.effectiveLevel(NOT_YET_CREATED));
        assertEquals(1, auditLog.records().size());
    }

    private static LoggerInfo find(List<LoggerInfo> rows, String name) {
        return rows.stream().filter(row -> row.name().equals(name)).findFirst().orElseThrow();
    }
}
