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
package org.logaperture.adapter.jul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
class JulLoggingAdapterTest {

    private JulLoggingAdapter adapter;
    private String prefix;
    private java.util.logging.Level originalRootLevel;

    @BeforeEach
    void setUp() {
        adapter = new JulLoggingAdapter();
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

    // handler-floor *warning* rendering moved to core (doc/specs/
    // handler-floor-control.md "Warning on level commands") -- this
    // adapter's job is only to report handlerFloorsBelow() correctly
    // (covered above) and to let setHandlerLevel/handlerLevel act on a
    // handler, covered below.

    @Test
    void setHandlerLevel_lowersTheHandlerAndReturnsItsPriorLevel() {
        ConsoleHandler console = testConsoleAtInfo();
        Logger.getLogger("").addHandler(console);
        try {
            // The JVM's default root config may already carry its own
            // ConsoleHandler -- find the ref for the one this test added,
            // rather than assuming ours is the only floor (same reasoning
            // as handlerFloor_belowATarget_isReported's before/after delta).
            HandlerRef ref = adapter.handlerFloorsBelow(name("handler.Target"), Level.TRACE).stream()
                    .map(HandlerFloor::handlerRef)
                    .filter(candidate -> candidate.equals(HandlerRef.anonymous(console)))
                    .findFirst()
                    .orElseThrow();

            Optional<Level> previous = adapter.setHandlerLevel(ref, Level.TRACE);

            assertEquals(Optional.of(Level.INFO), previous);
            assertEquals(Optional.of(Level.TRACE), adapter.handlerLevel(ref));
            assertSame(java.util.logging.Level.FINEST, console.getLevel());
        } finally {
            Logger.getLogger("").removeHandler(console);
        }
    }

    @Test
    void setHandlerLevel_unknownRef_throws() {
        assertThrows(org.logaperture.core.spi.UnknownHandlerException.class,
                () -> adapter.setHandlerLevel(new HandlerRef("NeverSeen"), Level.TRACE));
    }

    @Test
    void knownHandlers_includesAHandlerAttachedToAKnownLogger() {
        ConsoleHandler console = testConsoleAtInfo();
        Logger.getLogger(name("handler.Known")).addHandler(console);
        try {
            List<HandlerRef> refs = adapter.knownHandlers();
            assertTrue(refs.stream().anyMatch(ref -> ref.value().startsWith("ConsoleHandler@")));
        } finally {
            Logger.getLogger(name("handler.Known")).removeHandler(console);
        }
    }

    @Test
    void handlerRef_fallsBackToAnIdentityToken_noJBossLogManagerOnThisClasspath() {
        ConsoleHandler console = testConsoleAtInfo();
        Logger.getLogger("").addHandler(console);
        try {
            HandlerRef ref = adapter.handlerFloorsBelow(name("handler.Anon"), Level.TRACE).get(0).handlerRef();

            assertTrue(ref.value().startsWith("ConsoleHandler@"),
                    "no org.jboss.logmanager on this test's classpath -- always the identity fallback");
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
        return floors.stream().filter(f -> f.handlerRef().value().startsWith("ConsoleHandler@")).count();
    }
}
