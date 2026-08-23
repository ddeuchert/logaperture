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
package org.logaperture.adapter.logback;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against a real, freshly-constructed {@code LoggerContext} (never tied to
 * SLF4J's static binding, so each test is fully isolated). Reproduces the
 * scenarios doc/spikes/m0-adapter-grid.md point 1 validated by hand.
 */
class LogbackLoggingAdapterTest {

    private LoggerContext context;
    private LoggingAdapter adapter;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        adapter = new LogbackLoggingAdapter(context);
    }

    @Test
    void knownLoggerNames_growsAsLoggersAreInstantiated() {
        assertTrue(adapter.knownLoggerNames().contains("ROOT"));
        assertEquals(1, adapter.knownLoggerNames().size()); // ROOT only, nothing else touched yet

        context.getLogger("com.acme.Worker"); // instantiate, like SLF4J's LoggerFactory.getLogger would

        assertTrue(adapter.knownLoggerNames().contains("com.acme.Worker"));
    }

    @Test
    void configuredLevel_explicit_isPresent() {
        context.getLogger("com.acme.Worker").setLevel(ch.qos.logback.classic.Level.DEBUG);

        assertEquals(Optional.of(Level.DEBUG), adapter.configuredLevel("com.acme.Worker"));
    }

    @Test
    void configuredLevel_inherited_isEmpty() {
        // Never explicitly set -- inherits from ROOT.
        assertEquals(Optional.empty(), adapter.configuredLevel("com.acme.Worker"));
    }

    @Test
    void configuredLevel_sideEffectCreatesTheLoggerLikeLogbackDoes() {
        adapter.configuredLevel("com.acme.NeverSeen");

        assertTrue(adapter.knownLoggerNames().contains("com.acme.NeverSeen"));
    }

    @Test
    void applyLevel_onExistingLogger_takesEffect() {
        context.getLogger("com.acme.Worker");

        adapter.applyLevel("com.acme.Worker", Level.DEBUG);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void applyLevel_onNotYetInstantiatedLogger_stillTakesEffect() {
        // Pre-set a level on a name that doesn't exist yet -- Logback accepts
        // this natively (doc/spikes/m0-adapter-grid.md point 1's "Known" state).
        adapter.applyLevel("com.acme.NotYetCreated", Level.TRACE);

        assertEquals(Level.TRACE, adapter.effectiveLevel("com.acme.NotYetCreated"));
    }

    @Test
    void applyLevel_null_clearsToInherited() {
        context.getLogger("com.acme.Worker").setLevel(ch.qos.logback.classic.Level.DEBUG);

        adapter.applyLevel("com.acme.Worker", null);

        assertEquals(Optional.empty(), adapter.configuredLevel("com.acme.Worker"));
    }

    @Test
    void effectiveLevel_resolvesThroughHierarchy() {
        context.getLogger("com.acme").setLevel(ch.qos.logback.classic.Level.WARN);

        // "com.acme.http" has no explicit level of its own -- inherits from "com.acme".
        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.http"));
    }

    @Test
    void effectiveLevel_ownExplicitLevelOverridesInheritance() {
        context.getLogger("com.acme").setLevel(ch.qos.logback.classic.Level.WARN);
        context.getLogger("com.acme.http").setLevel(ch.qos.logback.classic.Level.DEBUG);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.http"));
    }

    // --- onReset -- doc/specs/persistence.md "Reconfiguration re-application" ------------------

    @Test
    void onReset_firesAfterLogbackHasAlreadyClearedLevels() {
        context.getLogger("com.acme.Worker").setLevel(ch.qos.logback.classic.Level.DEBUG);
        List<Level> observedAtCallbackTime = new ArrayList<>();
        adapter.onReset(() -> observedAtCallbackTime.add(adapter.configuredLevel("com.acme.Worker").orElse(null)));

        context.reset();

        // Already cleared by the time the listener runs -- exactly what
        // makes it safe for the listener to reapply state immediately,
        // rather than racing the reset that triggered it.
        assertEquals(1, observedAtCallbackTime.size());
        assertEquals(null, observedAtCallbackTime.get(0));
    }

    @Test
    void onReset_listenerCanReapplyAnOverrideThatThenSurvives() {
        adapter.applyLevel("com.acme.Worker", Level.DEBUG);
        adapter.onReset(() -> adapter.applyLevel("com.acme.Worker", Level.DEBUG));

        context.reset();

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void onReset_neverRegistered_resetStillWorksNormally() {
        context.getLogger("com.acme.Worker").setLevel(ch.qos.logback.classic.Level.DEBUG);

        context.reset(); // must not throw even with no listener registered

        assertEquals(Optional.empty(), adapter.configuredLevel("com.acme.Worker"));
    }
}
