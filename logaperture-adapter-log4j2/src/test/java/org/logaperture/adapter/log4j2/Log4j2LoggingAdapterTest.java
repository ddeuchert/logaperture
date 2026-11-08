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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Log4j2LoggingAdapterTest {

    private LoggerContext context;
    private Log4j2LoggingAdapter adapter;

    @BeforeEach
    void setUp() {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.add(builder.newLogger("app", org.apache.logging.log4j.Level.INFO));
        builder.add(builder.newLogger("com.vendor", org.apache.logging.log4j.Level.WARN));
        builder.add(builder.newLogger("critical", org.apache.logging.log4j.Level.FATAL));
        builder.add(builder.newRootLogger(org.apache.logging.log4j.Level.ERROR));
        context = Configurator.initialize(builder.build());
        context.getLogger("app.svc.Orders"); // instantiated, no LoggerConfig of its own
        adapter = new Log4j2LoggingAdapter(context, "2.20.0");
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    @Test
    void knownNames_areTheInstantiatedLoggersAndTheDeclaredConfigs() {
        assertTrue(adapter.knownLoggerNames().containsAll(
                java.util.List.of("ROOT", "app", "com.vendor", "app.svc.Orders")), adapter.knownLoggerNames().toString());
    }

    @Test
    void configuredLevel_isEmptyForANameWithNoConfigOfItsOwn() {
        assertEquals(Optional.of(Level.INFO), adapter.configuredLevel("app"));
        assertEquals(Optional.empty(), adapter.configuredLevel("app.svc.Orders"));
        assertEquals(Optional.of(Level.ERROR), adapter.configuredLevel("ROOT"));
    }

    @Test
    void effectiveLevel_followsTheNearestAncestor() {
        assertEquals(Level.INFO, adapter.effectiveLevel("app.svc.Orders"));
        assertEquals(Level.ERROR, adapter.effectiveLevel("somewhere.else"));
    }

    @Test
    void applyLevel_onADeclaredConfig_changesIt_andNullGivesItBackItsInheritance() {
        adapter.applyLevel("com.vendor", Level.DEBUG);
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.vendor.Thing"));
        adapter.applyLevel("com.vendor", null);
        assertEquals(Optional.empty(), adapter.configuredLevel("com.vendor"));
        assertEquals(Level.ERROR, adapter.effectiveLevel("com.vendor.Thing"), "inherits the root again");
        assertTrue(adapter.knownLoggerNames().contains("com.vendor"), "the LoggerConfig was not created by us");
    }

    @Test
    void applyLevel_onAnUnknownName_createsAConfig_andNullRemovesIt() {
        adapter.applyLevel("late.Thing", Level.TRACE);
        assertEquals(Level.TRACE, adapter.effectiveLevel("late.Thing"));
        assertEquals(Optional.of(Level.TRACE), adapter.configuredLevel("late.Thing"));
        adapter.applyLevel("late.Thing", null);
        assertEquals(Optional.empty(), adapter.configuredLevel("late.Thing"));
        assertFalse(context.getConfiguration().getLoggers().containsKey("late.Thing"),
                "reset removes the LoggerConfig it created");
    }

    @Test
    void applyLevel_takesEffectOnALoggerThatAlreadyExists() {
        org.apache.logging.log4j.core.Logger logger = context.getLogger("app.svc.Orders");
        adapter.applyLevel("app", Level.DEBUG);
        assertTrue(logger.isDebugEnabled());
        adapter.applyLevel("app", Level.INFO);
        assertFalse(logger.isDebugEnabled());
    }

    @Test
    void root_canBeSet() {
        adapter.applyLevel("ROOT", Level.WARN);
        assertEquals(Level.WARN, adapter.effectiveLevel("elsewhere"));
    }

    @Test
    void backendInfo_namesLog4j() {
        assertEquals("Log4j 2", adapter.backendInfo().name());
        assertEquals("2.20.0", adapter.backendInfo().version());
    }

    @Test
    void aDeclaredFatal_readsAsError_andADeliberateErrorIsWrittenAsError() {
        assertEquals(Optional.of(Level.ERROR), adapter.configuredLevel("critical"), "read as the nearest level");
        adapter.applyLevel("critical", Level.ERROR);
        assertEquals(org.apache.logging.log4j.Level.ERROR,
                context.getConfiguration().getLoggers().get("critical").getExplicitLevel(),
                "a deliberate 'error' must not be written as FATAL, which would drop the ERROR events asked for");
    }

    @Test
    void aDeclaredFatal_carriesATokenThatRestoresItExactly_aPlainLevelCarriesNone() {
        Object token = adapter.nativeLevelToken("critical");
        assertEquals(org.apache.logging.log4j.Level.FATAL, token);
        assertNull(adapter.nativeLevelToken("ROOT"), "an ERROR/WARN root round-trips: no token");
        adapter.applyLevel("critical", Level.DEBUG);
        adapter.restoreLevel("critical", Level.ERROR, token);
        assertEquals(org.apache.logging.log4j.Level.FATAL,
                context.getConfiguration().getLoggers().get("critical").getExplicitLevel());
    }

    @Test
    void aTokenRestore_endsTheOverridesBookkeeping_likeAClear() {
        adapter.applyLevel("late.Thing", Level.TRACE); // LogAperture creates the LoggerConfig
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.add(builder.newLogger("late.Thing", org.apache.logging.log4j.Level.FATAL));
        builder.add(builder.newRootLogger(org.apache.logging.log4j.Level.ERROR));
        context.setConfiguration(builder.build()); // the application declares it at FATAL now
        adapter.applyLevel("late.Thing", Level.DEBUG); // remembers the declared FATAL
        adapter.restoreLevel("late.Thing", Level.ERROR, org.apache.logging.log4j.Level.FATAL);
        // the application later declares WARN itself; the next override and clear must not bring back the old FATAL
        context.getConfiguration().getLoggers().get("late.Thing").setLevel(org.apache.logging.log4j.Level.WARN);
        adapter.applyLevel("late.Thing", Level.DEBUG);
        adapter.applyLevel("late.Thing", null);
        assertNull(context.getConfiguration().getLoggers().get("late.Thing").getExplicitLevel(),
                "a stale declaredBeforeOverride would have put the earlier FATAL back");
    }

    @Test
    void aTokenThatNoLongerMatchesTheLevel_fallsBackToTheLevel() {
        adapter.restoreLevel("critical", Level.WARN, org.apache.logging.log4j.Level.FATAL);
        assertEquals(org.apache.logging.log4j.Level.WARN,
                context.getConfiguration().getLoggers().get("critical").getExplicitLevel());
    }

    @Test
    void aCustomLevel_isRestoredExactly() {
        org.apache.logging.log4j.Level notice = org.apache.logging.log4j.Level.forName("NOTICE", 350);
        context.getConfiguration().getLoggers().get("critical").setLevel(notice);
        Level read = adapter.configuredLevel("critical").orElseThrow(); // NOTICE (350) reads as INFO
        Object token = adapter.nativeLevelToken("critical");
        assertEquals(notice, token);
        adapter.applyLevel("critical", Level.TRACE);
        adapter.restoreLevel("critical", read, token);
        assertEquals(notice, context.getConfiguration().getLoggers().get("critical").getExplicitLevel());
    }

    @Test
    void clearing_neverRemovesAConfigTheApplicationDeclaredAfterAReconfiguration() {
        adapter.applyLevel("late.Thing", Level.TRACE); // LogAperture creates the LoggerConfig
        // A reconfiguration: the new configuration declares the same name itself.
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.add(builder.newLogger("late.Thing", org.apache.logging.log4j.Level.WARN));
        builder.add(builder.newRootLogger(org.apache.logging.log4j.Level.ERROR));
        context.setConfiguration(builder.build());
        adapter.applyLevel("late.Thing", null);
        assertTrue(context.getConfiguration().getLoggers().containsKey("late.Thing"),
                "the application's own declared config is not ours to remove");
        assertEquals(org.apache.logging.log4j.Level.WARN,
                context.getConfiguration().getLoggers().get("late.Thing").getExplicitLevel());
    }

    @Test
    void concurrentPreSetsOfOneNew_leaveOneConfigAndTheLastLevel() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> adapter.applyLevel("race.Thing", Level.DEBUG)));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Optional.of(Level.DEBUG), adapter.configuredLevel("race.Thing"));
        adapter.applyLevel("race.Thing", null);
        assertFalse(context.getConfiguration().getLoggers().containsKey("race.Thing"));
    }
}
