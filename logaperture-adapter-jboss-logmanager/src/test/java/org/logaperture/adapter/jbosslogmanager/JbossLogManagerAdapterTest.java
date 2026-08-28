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
package org.logaperture.adapter.jbosslogmanager;

import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.bridge.DiagnosticLevel;
import org.logaperture.bridge.Diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.ConsoleHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, JBoss LogManager on the test classpath, no WildFly — see
 * doc/specs/wildfly-support.md, Slice 2's "Testing".
 */
class JbossLogManagerAdapterTest {

    private LogContext context;
    private JbossLogManagerAdapter adapter;

    @BeforeEach
    void setUp() {
        context = LogContext.create();
        context.getLogger("").setLevel(java.util.logging.Level.INFO); // a known root baseline
        adapter = new JbossLogManagerAdapter(context);
    }

    @AfterEach
    void resetDiagnostics() {
        Diagnostics.resetToDefault();
    }

    // --- level mapping ---------------------------------------------------------------------------

    @Test
    void levelMapping_roundTripsForAllSevenLevels() {
        for (Level level : Level.values()) {
            adapter.applyLevel("map.RoundTrip", level);
            assertEquals(level, adapter.configuredLevel("map.RoundTrip").orElseThrow(),
                    "write is exact for " + level);
        }
    }

    @Test
    void finerReadsBackAsTrace_butResetRestoresTheExactOriginal() {
        context.getLogger("map.Finer").setLevel(java.util.logging.Level.FINER);

        assertEquals(Level.TRACE, adapter.configuredLevel("map.Finer").orElseThrow(),
                "FINER has no LogAperture equivalent — displays as TRACE");

        adapter.applyLevel("map.Finer", Level.DEBUG);                 // an override
        adapter.applyLevel("map.Finer", Level.TRACE);                 // core's reset: baseline read back as TRACE

        assertSame(java.util.logging.Level.FINER, context.getLogger("map.Finer").getLevel(),
                "reset restores the exact captured java.util.logging.Level, not FINEST");
    }

    @Test
    void configReadsBackAsInfo_butResetRestoresTheExactOriginal() {
        context.getLogger("map.Config").setLevel(java.util.logging.Level.CONFIG);

        assertEquals(Level.INFO, adapter.configuredLevel("map.Config").orElseThrow());

        adapter.applyLevel("map.Config", Level.DEBUG);
        adapter.applyLevel("map.Config", Level.INFO);

        assertSame(java.util.logging.Level.CONFIG, context.getLogger("map.Config").getLevel());
    }

    // --- knownLoggerNames / configuredLevel / effectiveLevel / applyLevel ----------------------

    @Test
    void knownLoggerNames_materialisesTheContextEnumeration() {
        context.getLogger("a.b.C");
        context.getLogger("a.b.D");

        List<String> names = adapter.knownLoggerNames();

        assertTrue(names.contains("a.b.C"));
        assertTrue(names.contains("a.b.D"));
    }

    @Test
    void configuredLevel_isExplicitVsInherited() {
        context.getLogger("cfg.Explicit").setLevel(java.util.logging.Level.WARNING);
        context.getLogger("cfg.Inherited"); // created, no explicit level

        assertEquals(Level.WARN, adapter.configuredLevel("cfg.Explicit").orElseThrow());
        assertTrue(adapter.configuredLevel("cfg.Inherited").isEmpty());
    }

    @Test
    void configuredLevel_createsTheLoggerAsASideEffect() {
        assertFalse(adapter.knownLoggerNames().contains("cfg.NotYet"));

        adapter.configuredLevel("cfg.NotYet");

        assertTrue(adapter.knownLoggerNames().contains("cfg.NotYet"), "asking by name creates it, like Logback");
    }

    @Test
    void applyLevel_onANotYetCreatedLogger_preSetsIt() {
        adapter.applyLevel("apply.PreSet", Level.DEBUG);

        assertEquals(Level.DEBUG, adapter.configuredLevel("apply.PreSet").orElseThrow());
    }

    @Test
    void effectiveLevel_resolvesUpTheHierarchy() {
        context.getLogger("eff.parent").setLevel(java.util.logging.Level.FINE);
        context.getLogger("eff.parent.child"); // inherits

        assertEquals(Level.DEBUG, adapter.effectiveLevel("eff.parent.child"));
    }

    @Test
    void applyLevel_null_clearsBackToInherited() {
        context.getLogger("apply.Clear").setLevel(java.util.logging.Level.FINE);

        adapter.applyLevel("apply.Clear", null);

        assertTrue(adapter.configuredLevel("apply.Clear").isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel("apply.Clear"), "now inherits the root's INFO");
    }

    // --- isolation between contexts ------------------------------------------------------------

    @Test
    void twoIndependentContexts_areIsolated() {
        LogContext other = LogContext.create();
        other.getLogger("").setLevel(java.util.logging.Level.INFO);
        JbossLogManagerAdapter otherAdapter = new JbossLogManagerAdapter(other);

        adapter.applyLevel("iso.Worker", Level.TRACE);

        assertEquals(Level.TRACE, adapter.effectiveLevel("iso.Worker"));
        assertEquals(Level.INFO, otherAdapter.effectiveLevel("iso.Worker"),
                "a level set in one LogContext is invisible in the other");
    }

    // --- handler-level thresholds -----------------------------------------------------------------

    @Test
    void handlerFloor_belowATarget_isReported() {
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(java.util.logging.Level.INFO);
        context.getLogger("").addHandler(console);

        List<HandlerFloor> below = adapter.handlerFloorsBelow("floor.Worker", Level.DEBUG);
        assertEquals(1, below.size());
        assertEquals("ConsoleHandler", below.get(0).handlerName());
        assertEquals(Level.INFO, below.get(0).floor());

        assertTrue(adapter.handlerFloorsBelow("floor.Worker", Level.WARN).isEmpty(),
                "an INFO handler is not a floor for a WARN target");
    }

    @Test
    void applyLevel_raiseBelowAHandlerFloor_emitsExactlyOneDiagnostic() {
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(java.util.logging.Level.INFO);
        context.getLogger("").addHandler(console);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.DEBUG);

        adapter.applyLevel("floor.Raised", Level.DEBUG);

        String output = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1, output.lines().filter(l -> l.contains("level floor")).count());
        assertTrue(output.contains("ConsoleHandler"));
        assertTrue(output.contains("floor.Raised set to DEBUG"));
    }

    @Test
    void applyLevel_notARaise_emitsNoHandlerFloorDiagnostic() {
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(java.util.logging.Level.INFO);
        context.getLogger("").addHandler(console);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.DEBUG);

        adapter.applyLevel("floor.Lowered", Level.WARN); // INFO -> WARN is not more verbose

        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("level floor"));
    }

    // --- re-appliability ----------------------------------------------------------------------

    @Test
    void applyLevel_isIdempotentAndSurvivesAnExternalReset() {
        adapter.applyLevel("reapply.Worker", Level.DEBUG);
        adapter.applyLevel("reapply.Worker", Level.DEBUG); // no-op-equivalent
        assertEquals(Level.DEBUG, adapter.effectiveLevel("reapply.Worker"));

        context.getLogger("reapply.Worker").setLevel(null); // something else reset it

        assertEquals(Level.INFO, adapter.effectiveLevel("reapply.Worker"));
        adapter.applyLevel("reapply.Worker", Level.DEBUG); // still works
        assertEquals(Level.DEBUG, adapter.effectiveLevel("reapply.Worker"));
    }
}
