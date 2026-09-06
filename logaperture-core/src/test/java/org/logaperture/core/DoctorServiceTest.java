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
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code logctl doctor}'s engine — see doc/specs/doctor.md "Testing".
 * {@code CONSOLE} in these tests is deliberately never given a {@code
 * targetPath}, modeling a non-persistent handler (Decision #3); {@code
 * FILE} always is.
 */
class DoctorServiceTest {

    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef FILE = new HandlerRef("FILE");

    private FakeLoggingAdapter adapter;
    private DoctorService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        service = new DoctorService(adapter, CapabilityPolicy.allowAll());
    }

    // --- capability ---------------------------------------------------------------------------

    @Test
    void diagnose_requiresView() {
        DoctorService denied = new DoctorService(adapter, CapabilityPolicy.denyAll());
        assertThrows(CapabilityDeniedException.class, denied::diagnose);
    }

    // --- check 1: unbounded growth -------------------------------------------------------------

    @Test
    void unboundedGrowth_noSizeCap_isFlagged() {
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(null, 5, false, Path.of("/var/log/app.log")));

        List<DoctorFinding> findings = service.diagnose();

        DoctorFinding finding = findingFor(findings, "handler.unbounded-growth", Severity.WARNING);
        assertEquals("FILE", finding.subject());
        assertTrue(finding.summary().contains("no size cap"), finding.summary());
        assertTrue(finding.suggestedFix().contains("FILE"));
    }

    @Test
    void unboundedGrowth_noBackupLimit_isFlagged() {
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(10_000_000L, 0, false, Path.of("/var/log/app.log")));

        List<DoctorFinding> findings = service.diagnose();

        DoctorFinding finding = findingFor(findings, "handler.unbounded-growth", Severity.WARNING);
        // The handler here has an explicit size cap -- only the backup/retention limit is
        // missing, so the summary must not claim "no size cap" (the size cap isn't the problem).
        assertTrue(finding.summary().contains("no backup/retention limit"), finding.summary());
        assertFalse(finding.summary().contains("no size cap"), finding.summary());
    }

    @Test
    void unboundedGrowth_cappedAndBounded_isClean() {
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(10_000_000L, 5, false, Path.of("/var/log/app.log")));

        List<DoctorFinding> findings = service.diagnose();

        findingFor(findings, "handler.unbounded-growth", Severity.OK);
    }

    @Test
    void unboundedGrowth_noPersistentHandlers_checkDoesNotRun() {
        adapter.addHandler(CONSOLE, Level.INFO); // no targetPath -- not persistent

        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> f.check().equals("handler.unbounded-growth")));
    }

    // --- check 2: verbosity left on ------------------------------------------------------------

    @Test
    void verbosity_rootAtDebug_isFlagged() {
        adapter.setConfiguredLevel("ROOT", Level.DEBUG);

        List<DoctorFinding> findings = service.diagnose();

        findingFor(findings, "logger.verbosity-left-on", Severity.WARNING);
    }

    @Test
    void verbosity_chattySeedLoggerExplicitlyDebug_isFlagged() {
        adapter.setConfiguredLevel("org.hibernate", Level.DEBUG);

        List<DoctorFinding> findings = service.diagnose();

        DoctorFinding finding = findings.stream()
                .filter(f -> f.check().equals("logger.verbosity-left-on") && f.subject().equals("org.hibernate"))
                .findFirst().orElseThrow();
        assertEquals(Severity.WARNING, finding.severity());
    }

    @Test
    void verbosity_chattySeedLoggerNotPresent_isNotFlagged() {
        // org.hibernate is in the seed list but never registered with this adapter.
        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> "org.hibernate".equals(f.subject())));
    }

    @Test
    void verbosity_allInfo_isClean() {
        List<DoctorFinding> findings = service.diagnose();

        findingFor(findings, "logger.verbosity-left-on", Severity.OK);
    }

    // --- check 3: duplicate output (Decision #3) ------------------------------------------------

    @Test
    void duplicateOutput_twoPersistentHandlersSameLevel_isFlagged() {
        HandlerRef file2 = new HandlerRef("FILE2");
        adapter.addHandler(FILE, Level.INFO);
        adapter.addHandler(file2, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(1L, 1, false, Path.of("/var/log/a.log")));
        adapter.setHandlerDiagnostics(file2, new HandlerDiagnostics(1L, 1, false, Path.of("/var/log/b.log")));

        List<DoctorFinding> findings = service.diagnose();

        findingFor(findings, "handler.duplicate-output", Severity.INFO);
    }

    @Test
    void duplicateOutput_consoleExcludedEvenAtSameLevelAsFile() {
        // Decision #3: a non-persistent handler never counts, even sharing FILE's level.
        adapter.addHandler(CONSOLE, Level.INFO); // no targetPath
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(1L, 1, false, Path.of("/var/log/a.log")));

        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> f.check().equals("handler.duplicate-output")),
                "only one persistent handler exists -- the check doesn't run at all");
    }

    @Test
    void duplicateOutput_onePersistentHandler_checkDoesNotRun() {
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(1L, 1, false, Path.of("/var/log/a.log")));

        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> f.check().equals("handler.duplicate-output")));
    }

    // --- check 4: autoflush ---------------------------------------------------------------------

    @Test
    void autoflush_enabled_isFlagged() {
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(1L, 1, true, Path.of("/var/log/a.log")));

        List<DoctorFinding> findings = service.diagnose();

        findingFor(findings, "handler.autoflush", Severity.WARNING);
    }

    @Test
    void autoflush_disabled_isClean() {
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(1L, 1, false, Path.of("/var/log/a.log")));

        List<DoctorFinding> findings = service.diagnose();

        findingFor(findings, "handler.autoflush", Severity.OK);
    }

    @Test
    void autoflush_conceptNotExposedByAnyHandler_checkDoesNotRun() {
        adapter.addHandler(CONSOLE, Level.INFO); // HandlerDiagnostics.EMPTY -- autoFlush is null

        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> f.check().equals("handler.autoflush")));
    }

    @Test
    void autoflush_adapterHasNoHandlerLevels_checkDoesNotRun() {
        adapter.disableHandlerLevels();

        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> f.check().equals("handler.autoflush")));
    }

    // --- check 5: disk headroom (real filesystem) -----------------------------------------------

    @Test
    void diskHeadroom_stableFile_reportsCleanWithNoEscalation(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("app.log");
        Files.writeString(logFile, "hello");
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(null, 5, false, logFile));

        List<DoctorFinding> findings = service.diagnose();

        DoctorFinding disk = findingFor(findings, "disk.headroom", Severity.OK);
        assertTrue(disk.summary().contains("stable"), disk.summary());
        // No writes observed during the sample -- unbounded-growth must NOT escalate.
        DoctorFinding growth = findingFor(findings, "handler.unbounded-growth", Severity.WARNING);
        assertEquals(Severity.WARNING, growth.severity());
    }

    @Test
    void diskHeadroom_noPersistentHandlers_checkDoesNotRun() {
        adapter.addHandler(CONSOLE, Level.INFO);

        List<DoctorFinding> findings = service.diagnose();

        assertTrue(findings.stream().noneMatch(f -> f.check().equals("disk.headroom")));
    }

    // --- Decision #6: cross-check escalation (tested directly, real disk sampling isn't controllable) --

    @Test
    void escalateUnboundedGrowth_matchingLowHeadroomPath_escalatesToCritical(@TempDir Path tempDir) {
        Path logFile = tempDir.resolve("app.log");
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(null, 5, false, logFile));
        String storeKey = DoctorService.fileStoreKey(logFile);

        DoctorFinding growth = new DoctorFinding("handler.unbounded-growth", Severity.WARNING, "FILE",
                "FILE has no size cap.", null, "configure rotation");
        DoctorFinding lowHeadroom = new DoctorFinding("disk.headroom", Severity.CRITICAL, storeKey,
                "almost full.", null, null);

        List<DoctorFinding> escalated = service.escalateUnboundedGrowth(List.of(growth), List.of(lowHeadroom));

        assertEquals(Severity.CRITICAL, escalated.get(0).severity());
    }

    @Test
    void escalateUnboundedGrowth_noLowHeadroomFindings_leavesSeverityAlone() {
        DoctorFinding growth = new DoctorFinding("handler.unbounded-growth", Severity.WARNING, "FILE",
                "FILE has no size cap.", null, "configure rotation");

        List<DoctorFinding> escalated = service.escalateUnboundedGrowth(List.of(growth), List.of());

        assertEquals(Severity.WARNING, escalated.get(0).severity());
    }

    @Test
    void escalateUnboundedGrowth_unrelatedPath_doesNotEscalate(@TempDir Path tempDir) {
        Path logFile = tempDir.resolve("app.log");
        adapter.addHandler(FILE, Level.INFO);
        adapter.setHandlerDiagnostics(FILE, new HandlerDiagnostics(null, 5, false, logFile));

        DoctorFinding growth = new DoctorFinding("handler.unbounded-growth", Severity.WARNING, "FILE",
                "FILE has no size cap.", null, "configure rotation");
        DoctorFinding lowHeadroomElsewhere = new DoctorFinding("disk.headroom", Severity.CRITICAL,
                "some-other-store-key", "almost full.", null, null);

        List<DoctorFinding> escalated = service.escalateUnboundedGrowth(List.of(growth), List.of(lowHeadroomElsewhere));

        assertEquals(Severity.WARNING, escalated.get(0).severity(), "different store -- not the same volume");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static DoctorFinding findingFor(List<DoctorFinding> findings, String check, Severity severity) {
        Optional<DoctorFinding> match = findings.stream()
                .filter(f -> f.check().equals(check) && f.severity() == severity)
                .findFirst();
        assertTrue(match.isPresent(), () -> "expected a " + severity + " finding for '" + check + "' in " + findings);
        return match.get();
    }
}
