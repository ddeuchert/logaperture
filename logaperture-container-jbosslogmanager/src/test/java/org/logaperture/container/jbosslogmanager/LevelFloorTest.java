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
package org.logaperture.container.jbosslogmanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.LevelFloorInfo;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/quarkus-support.md, "Min-level honesty": a logger whose level is more verbose than the
 * backend's floor reads as if it logs and does not. JBoss LogManager fixes the floor per logger
 * when the logger is created, so each test creates its own logger after setting the floor.
 */
class LevelFloorTest {

    private final LoggingAdapter adapter = JulAdapterFactory.forCurrentContext();
    private String name;

    @BeforeEach
    void setUp() {
        name = "floor." + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(TestFloorInitializer.PROPERTY);
        System.clearProperty("logging.initial-configurator.min-level");
    }

    private void createUnderFloor(String floorLevel) {
        System.setProperty(TestFloorInitializer.PROPERTY, floorLevel);
        Logger.getLogger(name); // the floor is fixed here, at creation
    }

    @Test
    void noFloor_nothingToReport() {
        Logger.getLogger(name);
        adapter.applyLevel(name, Level.TRACE);
        assertEquals(Optional.empty(), adapter.levelFloor(name));
    }

    @Test
    void levelAtOrAboveTheFloor_nothingToReport() {
        createUnderFloor("FINE");
        adapter.applyLevel(name, Level.DEBUG);
        assertEquals(Optional.empty(), adapter.levelFloor(name));
    }

    @Test
    void levelBelowTheFloor_reportsWhatTheBackendActuallyEmits() {
        createUnderFloor("FINE");
        adapter.applyLevel(name, Level.TRACE);

        LevelFloorInfo floor = adapter.levelFloor(name).orElseThrow();
        assertEquals(Level.DEBUG, floor.lowest());
    }

    @Test
    void quarkusBuildProperty_isNamedInTheHint() {
        System.setProperty("logging.initial-configurator.min-level", "500");
        createUnderFloor("FINE");
        adapter.applyLevel(name, Level.TRACE);

        String hint = adapter.levelFloor(name).orElseThrow().hint();
        assertTrue(hint.contains("quarkus.log.min-level=DEBUG"), hint);
        assertTrue(hint.contains("TRACE"), hint);
    }

    @Test
    void withoutTheQuarkusProperty_thereIsNoHint() {
        createUnderFloor("FINE");
        adapter.applyLevel(name, Level.TRACE);
        assertEquals(null, adapter.levelFloor(name).orElseThrow().hint());
    }

    @Test
    void anOffLogger_isNotReportedAsBelowAFloor() {
        createUnderFloor("FINE");
        adapter.applyLevel(name, Level.OFF);
        assertEquals(Optional.empty(), adapter.levelFloor(name));
    }
}
