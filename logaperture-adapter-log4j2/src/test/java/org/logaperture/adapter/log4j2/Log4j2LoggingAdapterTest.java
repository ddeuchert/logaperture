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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
