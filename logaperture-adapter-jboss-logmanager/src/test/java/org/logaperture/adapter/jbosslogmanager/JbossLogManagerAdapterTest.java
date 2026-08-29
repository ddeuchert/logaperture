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
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, against the JVM's own {@code java.util.logging.LogManager} —
 * see doc/specs/wildfly-support.md, Slice 2's "Testing". No {@code
 * org.jboss.logmanager} on the classpath: the adapter is pure JUL, and its
 * mechanics are identical whichever {@code LogManager} is installed. The
 * real-WildFly path (JBoss LogManager as the manager) is covered by
 * {@code logaperture-it}'s {@code WildFlyContainerIT}.
 *
 * <p>The global {@code LogManager} is shared, so each test uses a
 * unique-prefixed logger namespace and the root logger's level is captured
 * and restored.
 */
class JbossLogManagerAdapterTest {

    private JbossLogManagerAdapter adapter;
    private String prefix;
    private java.util.logging.Level originalRootLevel;

    @BeforeEach
    void setUp() {
        adapter = new JbossLogManagerAdapter();
        prefix = "it." + UUID.randomUUID().toString().replace("-", "") + ".";
        originalRootLevel = Logger.getLogger("").getLevel();
        if (originalRootLevel == null) {
            Logger.getLogger("").setLevel(java.util.logging.Level.INFO);
            originalRootLevel = java.util.logging.Level.INFO;
        }
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").setLevel(originalRootLevel);
        Diagnostics.resetToDefault();
        // each handler-floor test removes its own ConsoleHandler in a finally block
    }

    private String name(String suffix) {
        return prefix + suffix;
    }

    // --- level mapping ---------------------------------------------------------------------------

    @Test
    void levelMapping_roundTripsForAllSevenLevels() {
        for (Level level : Level.values()) {
            adapter.applyLevel(name("RoundTrip"), level);
            assertEquals(level, adapter.configuredLevel(name("RoundTrip")).orElseThrow(),
                    "write is exact for " + level);
        }
    }

    @Test
    void finerReadsBackAsTrace_butResetRestoresTheExactOriginal() {
        Logger.getLogger(name("Finer")).setLevel(java.util.logging.Level.FINER);

        assertEquals(Level.TRACE, adapter.configuredLevel(name("Finer")).orElseThrow(),
                "FINER has no LogAperture equivalent — displays as TRACE");

        adapter.applyLevel(name("Finer"), Level.DEBUG);
        adapter.applyLevel(name("Finer"), Level.TRACE); // core's reset: baseline read back as TRACE

        assertSame(java.util.logging.Level.FINER, Logger.getLogger(name("Finer")).getLevel(),
                "reset restores the exact captured java.util.logging.Level, not FINEST");
    }

    @Test
    void configReadsBackAsInfo_butResetRestoresTheExactOriginal() {
        Logger.getLogger(name("Config")).setLevel(java.util.logging.Level.CONFIG);

        assertEquals(Level.INFO, adapter.configuredLevel(name("Config")).orElseThrow());

        adapter.applyLevel(name("Config"), Level.DEBUG);
        adapter.applyLevel(name("Config"), Level.INFO);

        assertSame(java.util.logging.Level.CONFIG, Logger.getLogger(name("Config")).getLevel());
    }

    // --- knownLoggerNames / configuredLevel / effectiveLevel / applyLevel ----------------------

    @Test
    void knownLoggerNames_includesLoggersThatExistAndTheRoot() {
        Logger.getLogger(name("a.b.C"));

        List<String> names = adapter.knownLoggerNames();

        assertTrue(names.contains(name("a.b.C")));
        assertTrue(names.contains("ROOT"));
        assertFalse(names.contains(""), "the empty-string root name is not surfaced");
    }

    @Test
    void configuredLevel_isExplicitVsInherited() {
        Logger.getLogger(name("Explicit")).setLevel(java.util.logging.Level.WARNING);
        Logger.getLogger(name("Inherited")); // created, no explicit level

        assertEquals(Level.WARN, adapter.configuredLevel(name("Explicit")).orElseThrow());
        assertTrue(adapter.configuredLevel(name("Inherited")).isEmpty());
    }

    @Test
    void configuredLevel_createsTheLoggerAsASideEffect() {
        assertFalse(adapter.knownLoggerNames().contains(name("NotYet")));

        adapter.configuredLevel(name("NotYet"));

        assertTrue(adapter.knownLoggerNames().contains(name("NotYet")), "asking by name creates it");
    }

    @Test
    void applyLevel_onANotYetCreatedLogger_preSetsIt() {
        adapter.applyLevel(name("PreSet"), Level.DEBUG);

        assertEquals(Level.DEBUG, adapter.configuredLevel(name("PreSet")).orElseThrow());
    }

    @Test
    void effectiveLevel_resolvesUpTheHierarchy() {
        Logger.getLogger(name("parent")).setLevel(java.util.logging.Level.FINE);
        Logger.getLogger(name("parent.child")); // inherits

        assertEquals(Level.DEBUG, adapter.effectiveLevel(name("parent.child")));
    }

    @Test
    void applyLevel_null_clearsBackToInherited() {
        Logger.getLogger(name("Clear")).setLevel(java.util.logging.Level.FINE);
        Logger.getLogger("").setLevel(java.util.logging.Level.INFO);

        adapter.applyLevel(name("Clear"), null);

        assertTrue(adapter.configuredLevel(name("Clear")).isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel(name("Clear")));
    }

    // --- root logger ---------------------------------------------------------------------------

    @Test
    void rootLogger_isAddressedAsRoot_andResolvesToTheRealRoot() {
        adapter.applyLevel("ROOT", Level.WARN);

        assertSame(java.util.logging.Level.WARNING, Logger.getLogger("").getLevel());
        assertEquals(Level.WARN, adapter.effectiveLevel(name("some.inheriting.Child")));
    }

    // --- handler-level thresholds -----------------------------------------------------------------

    @Test
    void handlerFloor_belowATarget_isReported() {
        // JBoss LogManager's root may already carry handlers -- measure the delta our own adds.
        long debugFloorsBefore = countConsoleFloors(adapter.handlerFloorsBelow(name("floor.Worker"), Level.DEBUG));
        long warnFloorsBefore = countConsoleFloors(adapter.handlerFloorsBelow(name("floor.Worker"), Level.WARN));

        ConsoleHandler console = testConsoleAtInfo();
        Logger.getLogger("").addHandler(console);
        try {
            assertEquals(debugFloorsBefore + 1,
                    countConsoleFloors(adapter.handlerFloorsBelow(name("floor.Worker"), Level.DEBUG)),
                    "our INFO console handler is a floor for a DEBUG target");
            assertEquals(warnFloorsBefore,
                    countConsoleFloors(adapter.handlerFloorsBelow(name("floor.Worker"), Level.WARN)),
                    "an INFO handler is not a floor for a WARN target");
            assertTrue(adapter.handlerFloorsBelow(name("floor.Worker"), null).isEmpty(),
                    "a null target is not a raise");
        } finally {
            Logger.getLogger("").removeHandler(console);
        }
    }

    @Test
    void applyLevel_raiseBelowAHandlerFloor_emitsExactlyOneDiagnostic() {
        ConsoleHandler console = testConsoleAtInfo();
        Logger.getLogger("").addHandler(console);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.DEBUG);
        try {
            adapter.applyLevel(name("floor.Raised"), Level.DEBUG);

            String output = captured.toString(StandardCharsets.UTF_8);
            assertEquals(1, output.lines().filter(l -> l.contains("level floor")).count());
            assertTrue(output.contains("ConsoleHandler"));
            assertTrue(output.contains("set to DEBUG"));
        } finally {
            Logger.getLogger("").removeHandler(console);
        }
    }

    @Test
    void applyLevel_notARaise_emitsNoHandlerFloorDiagnostic() {
        ConsoleHandler console = testConsoleAtInfo();
        Logger.getLogger("").addHandler(console);
        Logger.getLogger("").setLevel(java.util.logging.Level.INFO);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.DEBUG);
        try {
            adapter.applyLevel(name("floor.Lowered"), Level.WARN); // INFO -> WARN is not more verbose

            assertFalse(captured.toString(StandardCharsets.UTF_8).contains("level floor"));
        } finally {
            Logger.getLogger("").removeHandler(console);
        }
    }

    // --- re-appliability ----------------------------------------------------------------------

    @Test
    void applyLevel_isIdempotentAndSurvivesAnExternalReset() {
        adapter.applyLevel(name("reapply.Worker"), Level.DEBUG);
        adapter.applyLevel(name("reapply.Worker"), Level.DEBUG);
        assertEquals(Level.DEBUG, adapter.effectiveLevel(name("reapply.Worker")));

        Logger.getLogger(name("reapply.Worker")).setLevel(null); // something else reset it
        Logger.getLogger("").setLevel(java.util.logging.Level.INFO);
        assertEquals(Level.INFO, adapter.effectiveLevel(name("reapply.Worker")));

        adapter.applyLevel(name("reapply.Worker"), Level.DEBUG); // still works
        assertEquals(Level.DEBUG, adapter.effectiveLevel(name("reapply.Worker")));
    }

    private static ConsoleHandler testConsoleAtInfo() {
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(java.util.logging.Level.INFO);
        return console;
    }

    private static long countConsoleFloors(List<HandlerFloor> floors) {
        return floors.stream().filter(f -> f.handlerName().equals("ConsoleHandler")).count();
    }
}
