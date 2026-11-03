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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.bridge.DiagnosticLevel;
import org.logaperture.bridge.Diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/log4j2-adapter.md A1 and A2: a level changed natively against an override is put back,
 * the warning names it (once a minute, not within 5 s of a reconfiguration), and the baseline becomes
 * the level the native source set.
 */
class NativeChangeReassertionTest {

    private static final String APP = "app.demo";

    private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private InMemoryAuditLog auditLog;
    private LevelControlService service;

    @BeforeEach
    void setUp() {
        Diagnostics.configure(new PrintStream(diagnostics, true, StandardCharsets.UTF_8), DiagnosticLevel.WARN);
        adapter = new FakeLoggingAdapter(Level.ERROR);
        adapter.setConfiguredLevel(APP, Level.DEBUG); // what was configured before Spring ran
        baselines = new BaselineRegistry();
        auditLog = new InMemoryAuditLog();
        service = new LevelControlService(adapter, baselines, new OverrideRegistry(), CapabilityPolicy.allowAll(),
                auditLog, new InMemoryStateStore(), "alice", "jmx");
        baselines.captureIfAbsent(APP, adapter);
        service.setLogger(APP, Level.TRACE, SetLevelOptions.defaults());
    }

    @AfterEach
    void tearDown() {
        Diagnostics.resetToDefault();
    }

    private String warnings() {
        return diagnostics.toString(StandardCharsets.UTF_8);
    }

    /** Spring's {@code logging.level.app.demo=INFO}, or an Actuator POST. */
    private void nativeChange(Level level) {
        adapter.setConfiguredLevel(APP, level);
    }

    @Test
    void aNativeChange_isPutBack_andAudited() {
        nativeChange(Level.INFO);
        int before = auditLog.records().size();

        assertEquals(1, service.reassertAfterNativeChange(Instant.now()));

        assertEquals(Level.TRACE, adapter.effectiveLevel(APP));
        assertEquals(before + 1, auditLog.records().size());
        AuditRecord record = auditLog.records().get(before);
        assertEquals("verification-sweep", record.source());
        assertEquals("INFO", record.previousValue());
        assertEquals("TRACE", record.newValue());
    }

    @Test
    void theBaselineBecomesTheNativeSetting_soAResetGivesBackSpringsLevel() {
        nativeChange(Level.INFO);
        service.reassertAfterNativeChange(Instant.now());

        service.resetLogger(APP, false);

        assertEquals(Level.INFO, adapter.effectiveLevel(APP),
                "Spring's INFO, not the DEBUG from before Spring ran (A2)");
    }

    @Test
    void aNativeChangeToInherited_makesTheBaselineInherited() {
        adapter.setConfiguredLevel(APP, Level.INFO); // Actuator: configuredLevel null -> the config has no level of its own
        service.reassertAfterNativeChange(Instant.now());
        adapter.clearConfiguredLevel(APP);
        service.reassertAfterNativeChange(Instant.now());

        service.resetLogger(APP, false);

        assertEquals(java.util.Optional.empty(), adapter.configuredLevel(APP));
    }

    @Test
    void theSweepsOwnCheck_neitherRebasesNorWarns() {
        nativeChange(Level.INFO);

        assertEquals(1, service.verifyAndReapply(Instant.now()));

        assertEquals(Level.TRACE, adapter.effectiveLevel(APP));
        service.resetLogger(APP, false);
        assertEquals(Level.DEBUG, adapter.effectiveLevel(APP), "the sweep cannot tell who changed it: baseline unchanged");
        assertFalse(warnings().contains("outside LogAperture"), warnings());
    }

    @Test
    void theWarning_namesTheLoggerTheOverrideAndWhoSetIt() {
        nativeChange(Level.WARN);
        service.reassertAfterNativeChange(Instant.now());

        String text = warnings();
        assertTrue(text.contains("WARN logger '" + APP + "' was changed to WARN outside LogAperture"), text);
        assertTrue(text.contains("TRACE override"), text);
        assertTrue(text.contains("set by jmx as alice"), text);
        assertTrue(text.contains("logctl reset logger " + APP), text);
    }

    @Test
    void theWarning_isWrittenOncePerLoggerPerMinute() {
        nativeChange(Level.WARN);
        service.reassertAfterNativeChange(Instant.now());
        nativeChange(Level.INFO);
        service.reassertAfterNativeChange(Instant.now());

        assertEquals(1, warnings().split("outside LogAperture", -1).length - 1, warnings());
        // ...but each change is still audited
        assertEquals(2, auditLog.records().stream().filter(r -> "verification-sweep".equals(r.source())).count());
    }

    @Test
    void withinFiveSecondsOfAReconfiguration_itIsAuditedAndRebased_butNotWarnedAbout() {
        service.reapplyActiveOverrides(adapter); // the swap event: core's re-apply
        nativeChange(Level.INFO);                // Spring's logging.level, a few ms later (SB6)

        assertEquals(1, service.reassertAfterNativeChange(Instant.now()));

        assertFalse(warnings().contains("outside LogAperture"), warnings());
        service.resetLogger(APP, false);
        assertEquals(Level.INFO, adapter.effectiveLevel(APP), "still the baseline Spring set (A2)");
    }

    @Test
    void longAfterAReconfiguration_itWarns() {
        service.reapplyActiveOverrides(adapter);
        nativeChange(Level.INFO);

        service.reassertAfterNativeChange(Instant.now().plusSeconds(30));

        assertTrue(warnings().contains("outside LogAperture"), warnings());
    }

    @Test
    void aLoggerWithNoOverride_isLeftAlone() {
        adapter.setConfiguredLevel("other.Thing", Level.WARN);
        nativeChange(Level.TRACE); // the override's own level: nothing to put back

        assertEquals(0, service.reassertAfterNativeChange(Instant.now()));
        assertFalse(warnings().contains("outside LogAperture"), warnings());
    }
}
