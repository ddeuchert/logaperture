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
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * doc/specs/log4j2-adapter.md "Scope" 3: a swap runs the reset listener, someone else's
 * {@code updateLoggers()} runs the native-change listener, and the adapter's own never recurses.
 */
class ReconfigurationTest {

    private LoggerContext context;
    private Log4j2LoggingAdapter adapter;
    private final AtomicInteger resets = new AtomicInteger();
    private final AtomicInteger nativeChanges = new AtomicInteger();

    private static BuiltConfiguration configuration(org.apache.logging.log4j.Level appLevel) {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.add(builder.newLogger("app", appLevel));
        builder.add(builder.newRootLogger(org.apache.logging.log4j.Level.ERROR));
        return builder.build();
    }

    @BeforeEach
    void setUp() {
        context = Configurator.initialize(configuration(org.apache.logging.log4j.Level.INFO));
        adapter = new Log4j2LoggingAdapter(context, "2.20.0");
        adapter.onReset(resets::incrementAndGet);
        adapter.onNativeChange(nativeChanges::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        adapter.detach();
        context.stop();
    }

    @Test
    void aConfigurationSwap_runsTheResetListenerOnce() {
        context.setConfiguration(configuration(org.apache.logging.log4j.Level.WARN));
        assertEquals(1, resets.get());
    }

    @Test
    void anUpdateLoggersFromElsewhere_runsTheNativeChangeListener_notTheResetListener() {
        Configuration config = context.getConfiguration();
        config.getLoggerConfig("app").setLevel(org.apache.logging.log4j.Level.DEBUG); // Spring's logging.level
        context.updateLoggers();
        assertEquals(1, nativeChanges.get());
        assertEquals(0, resets.get());
    }

    @Test
    void theAdaptersOwnApplyLevel_firesNeitherListener() {
        adapter.applyLevel("app", Level.DEBUG);
        adapter.applyLevel("late.Thing", Level.TRACE);
        adapter.applyLevel("late.Thing", null);
        assertEquals(0, resets.get());
        assertEquals(0, nativeChanges.get());
    }

    @Test
    void aListenerThatAppliesLevels_doesNotRecurse() {
        Runnable reassertOnNativeChange = () -> {
            nativeChanges.incrementAndGet();
            adapter.applyLevel("app", Level.WARN); // what verifyOverrides does
        };
        adapter.onNativeChange(reassertOnNativeChange);
        context.getConfiguration().getLoggerConfig("app").setLevel(org.apache.logging.log4j.Level.DEBUG);
        context.updateLoggers();
        assertEquals(1, nativeChanges.get(), "the re-assert's own updateLoggers() must not call back");
        assertEquals(Level.WARN, adapter.effectiveLevel("app"));
    }

    @Test
    void aSwapListenerThatAppliesLevels_doesNotRecurse_andTheLevelSticks() {
        Runnable reapplyOnReset = () -> {
            resets.incrementAndGet();
            adapter.applyLevel("app", Level.DEBUG);
        };
        adapter.onReset(reapplyOnReset);
        context.setConfiguration(configuration(org.apache.logging.log4j.Level.WARN));
        assertEquals(1, resets.get());
        assertEquals(Level.DEBUG, adapter.effectiveLevel("app"), "re-applied on the new configuration");
    }

    @Test
    void aSwapForgetsTheConfigsItCreated_soAResetNeverRemovesTheNewOnes() {
        adapter.applyLevel("late.Thing", Level.TRACE);
        context.setConfiguration(configuration(org.apache.logging.log4j.Level.INFO));
        // the pre-set is gone with the old configuration; nothing of ours is left to remove
        adapter.applyLevel("late.Thing", null);
        assertEquals(java.util.Optional.empty(), adapter.configuredLevel("late.Thing"));
    }

    @Test
    void aFailingListener_neverReachesLog4j() {
        Runnable failingReapply = () -> {
            throw new IllegalStateException("boom");
        };
        adapter.onReset(failingReapply);
        context.setConfiguration(configuration(org.apache.logging.log4j.Level.WARN)); // must not throw
        assertEquals(Level.WARN, adapter.effectiveLevel("app"));
    }

    @Test
    void afterClear_orDetach_nothingFires() {
        adapter.clearResetListener();
        adapter.clearNativeChangeListener();
        context.setConfiguration(configuration(org.apache.logging.log4j.Level.WARN));
        context.updateLoggers();
        assertEquals(0, resets.get());
        assertEquals(0, nativeChanges.get());
    }

    @Test
    void onceTheContextIsStopping_nothingFires() {
        context.stop();
        context.updateLoggers();
        assertEquals(0, nativeChanges.get(), "Spring's shutdown hook calls updateLoggers() on a stopping context (SB10)");
    }

    /**
     * SB5: Log4j fires an {@code old == new} event on the <em>new</em> configuration before the swap
     * event. It is part of the swap: the swap event runs the full re-apply, so running the override check
     * on it as well would put every override back twice and audit a reload as native drift.
     */
    @Test
    void theEventBeforeTheSwapEvent_isNotANativeChange() {
        context.setConfiguration(configuration(org.apache.logging.log4j.Level.WARN));
        assertEquals(1, resets.get(), "the swap event re-applies");
        assertEquals(0, nativeChanges.get(), "the old == new event that came first is part of the swap");
        context.updateLoggers(); // a later one, from somebody else, is a native change
        assertEquals(1, nativeChanges.get());
    }

    private static BuiltConfiguration declaring(String name, org.apache.logging.log4j.Level level) {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.add(builder.newLogger(name, level));
        builder.add(builder.newRootLogger(org.apache.logging.log4j.Level.ERROR));
        return builder.build();
    }

    /**
     * A name LogAperture created, then the application's new configuration declares it, then the re-apply
     * writes the override onto the declared config. A reset gives back the level the application declared,
     * not the override's level and not "inherited".
     */
    @Test
    void aResetAfterASwapThatDeclaresTheName_restoresTheApplicationsDeclaredLevel() {
        adapter.applyLevel("x", Level.DEBUG); // created: the first configuration does not declare x
        Runnable reapplyOnReset = () -> adapter.applyLevel("x", Level.DEBUG);
        adapter.onReset(reapplyOnReset);

        context.setConfiguration(declaring("x", org.apache.logging.log4j.Level.WARN));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("x"), "the override was put back");

        adapter.applyLevel("x", null); // reset: the baseline captured earlier was "inherited"
        assertEquals(org.apache.logging.log4j.Level.WARN,
                context.getConfiguration().getLoggers().get("x").getExplicitLevel(),
                "the application's declared WARN, not the override's DEBUG");
    }

    @Test
    void theMarksAreDroppedForANameTheNewConfigurationNoLongerDeclares() {
        adapter.applyLevel("x", Level.DEBUG);
        context.setConfiguration(declaring("y", org.apache.logging.log4j.Level.WARN)); // x gone
        context.setConfiguration(declaring("x", org.apache.logging.log4j.Level.INFO)); // x declared again, later
        adapter.applyLevel("x", Level.TRACE);
        adapter.applyLevel("x", null);
        // x was not created on the configuration before this one, so the mark is gone: plain reset semantics.
        assertEquals(java.util.Optional.empty(), adapter.configuredLevel("x"));
    }
}
