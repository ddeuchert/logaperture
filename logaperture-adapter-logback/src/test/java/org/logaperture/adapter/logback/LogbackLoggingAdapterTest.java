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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.BackendInfo;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void backendInfo_namesLogbackWithTheJarsVersion() {
        BackendInfo info = adapter.backendInfo();

        assertEquals("Logback", info.name());
        assertNotNull(info.version(), "the logback-classic jar manifest carries Implementation-Version");
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

    // --- onReset -- doc/specs/persistence.md "Reconfiguration re-application", issue #231 -------
    //
    // Where Logback has configuration events (the 1.5.x this module compiles against), the listener
    // runs after Joran has applied the file. reconfigure() does what scan="true" and
    // JMXConfigurator do: reset(), then Joran.

    private static final String FILE_SETS_PRIVATE_TO_INFO = """
            <configuration>
              <appender name="AFTER" class="ch.qos.logback.core.read.ListAppender"/>
              <logger name="com.acme.private" level="INFO"/>
              <root level="INFO"><appender-ref ref="AFTER"/></root>
            </configuration>
            """;

    private void reconfigure(String xml) throws JoranException {
        context.reset();
        JoranConfigurator joran = new JoranConfigurator();
        joran.setContext(context);
        joran.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void onReset_reappliedOverridesSurviveEveryReconfiguration() throws JoranException {
        reconfigure(FILE_SETS_PRIVATE_TO_INFO);
        Runnable reapply = () -> {
            adapter.applyLevel("com.acme.public", Level.DEBUG);
            adapter.applyLevel("com.acme.private", Level.DEBUG);
        };
        reapply.run();
        adapter.onReset(reapply);

        for (int i = 1; i <= 3; i++) {
            reconfigure(FILE_SETS_PRIVATE_TO_INFO);

            // public isn't in the file; private is, at INFO, and Joran must not have the last word.
            assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.public"), "reconfiguration " + i);
            assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.private"), "reconfiguration " + i);
        }
    }

    @Test
    void onReset_runsAfterJoranHasAppliedTheFile() throws JoranException {
        List<String> seenAtCallbackTime = new ArrayList<>();
        Runnable observe = () -> {
            seenAtCallbackTime.add(String.valueOf(adapter.configuredLevel("com.acme.private").orElse(null)));
            seenAtCallbackTime.add(String.valueOf(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("AFTER") != null));
        };
        adapter.onReset(observe);

        reconfigure(FILE_SETS_PRIVATE_TO_INFO);

        // The file's level and appender already exist: a re-install of appender wraps finds them.
        assertEquals(List.of("INFO", "true"), seenAtCallbackTime);
    }

    @Test
    void onReset_aBareResetWithNoConfigurationAfterItDoesNotFire() {
        List<String> fired = new ArrayList<>();
        adapter.onReset(() -> fired.add("fired"));

        context.reset();

        // A bare reset leaves no appenders, so nothing is written until something configures the
        // context again; the verification sweep covers a configuration that fires no event.
        assertEquals(List.of(), fired);
    }

    @Test
    void onReset_registeringASecondListener_replacesRatherThanAccumulates() throws JoranException {
        List<String> fired = new ArrayList<>();
        adapter.onReset(() -> fired.add("first"));
        adapter.onReset(() -> fired.add("second"));

        reconfigure(FILE_SETS_PRIVATE_TO_INFO);

        assertEquals(List.of("second"), fired);
    }

    @Test
    void clearResetListener_unregistersSoAFutureReconfigurationDoesNotFireIt() throws JoranException {
        List<String> fired = new ArrayList<>();
        adapter.onReset(() -> fired.add("fired"));

        adapter.clearResetListener();
        reconfigure(FILE_SETS_PRIVATE_TO_INFO);

        assertEquals(List.of(), fired);
    }

    @Test
    void clearResetListener_withNothingRegistered_isSafeNoOp() {
        adapter.clearResetListener(); // must not throw
    }

    // Logback without configuration events (1.2.x, 1.3.0, 1.4.0) falls back to a reset listener.

    @Test
    void resetFallback_firesOnEveryReset_notJustTheFirst() {
        LoggingAdapter fallback = new LogbackLoggingAdapter(context, false);
        List<String> fired = new ArrayList<>();
        fallback.onReset(() -> fired.add("fired"));

        context.reset();
        context.reset();
        context.reset();

        // Logback drops a listener that isn't reset-resistant at the end of the first reset.
        assertEquals(List.of("fired", "fired", "fired"), fired);
    }

    @Test
    void resetFallback_firesAfterLogbackHasAlreadyClearedLevels() {
        LoggingAdapter fallback = new LogbackLoggingAdapter(context, false);
        context.getLogger("com.acme.Worker").setLevel(ch.qos.logback.classic.Level.DEBUG);
        List<Level> observedAtCallbackTime = new ArrayList<>();
        fallback.onReset(() -> observedAtCallbackTime.add(fallback.configuredLevel("com.acme.Worker").orElse(null)));

        context.reset();

        // Already cleared by the time the listener runs -- exactly what
        // makes it safe for the listener to reapply state immediately,
        // rather than racing the reset that triggered it.
        assertEquals(1, observedAtCallbackTime.size());
        assertEquals(null, observedAtCallbackTime.get(0));
    }

    @Test
    void resetFallback_listenerCanReapplyAnOverrideThatThenSurvives() {
        LoggingAdapter fallback = new LogbackLoggingAdapter(context, false);
        fallback.applyLevel("com.acme.Worker", Level.DEBUG);
        fallback.onReset(() -> fallback.applyLevel("com.acme.Worker", Level.DEBUG));

        context.reset();

        assertEquals(Level.DEBUG, fallback.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void resetFallback_registeringASecondListener_replacesRatherThanAccumulates() {
        LoggingAdapter fallback = new LogbackLoggingAdapter(context, false);
        List<String> fired = new ArrayList<>();
        fallback.onReset(() -> fired.add("first"));
        fallback.onReset(() -> fired.add("second"));

        context.reset();
        context.reset();

        assertEquals(List.of("second", "second"), fired);
    }

    @Test
    void resetFallback_clearResetListener_unregistersSoAFutureResetDoesNotFireIt() {
        LoggingAdapter fallback = new LogbackLoggingAdapter(context, false);
        List<String> fired = new ArrayList<>();
        fallback.onReset(() -> fired.add("fired"));

        fallback.clearResetListener();
        context.reset();

        assertEquals(List.of(), fired);
    }

    @Test
    void onReset_neverRegistered_resetStillWorksNormally() {
        context.getLogger("com.acme.Worker").setLevel(ch.qos.logback.classic.Level.DEBUG);

        context.reset(); // must not throw even with no listener registered

        assertEquals(Optional.empty(), adapter.configuredLevel("com.acme.Worker"));
    }
}
