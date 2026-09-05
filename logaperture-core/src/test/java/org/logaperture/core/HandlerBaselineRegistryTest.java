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

import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unlike {@link BaselineRegistryTest}'s logger counterpart, an empty result
 * here must never be cached -- a code-review finding (and the root cause of
 * a real bug: a handler unresolvable at first capture, e.g. during a
 * multi-context precheck, would otherwise lock in "no baseline" forever, and
 * a later {@code resetHandler} would hand the adapter a bare {@code null},
 * which {@code Handler#setLevel} throws on).
 */
class HandlerBaselineRegistryTest {

    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");

    @Test
    void notYetCapturedThrowsOnGet() {
        HandlerBaselineRegistry registry = new HandlerBaselineRegistry();
        assertFalse(registry.isCaptured(CONSOLE));
        assertThrows(IllegalStateException.class, () -> registry.get(CONSOLE));
    }

    @Test
    void capturesARealLevelOnce() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        HandlerBaselineRegistry registry = new HandlerBaselineRegistry();

        Optional<Level> first = registry.captureIfAbsent(CONSOLE, adapter);

        assertEquals(Optional.of(Level.INFO), first);
        assertTrue(registry.isCaptured(CONSOLE));
    }

    @Test
    void anUnresolvableHandler_returnsEmptyButIsNotCached() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO); // CONSOLE never registered
        HandlerBaselineRegistry registry = new HandlerBaselineRegistry();

        Optional<Level> captured = registry.captureIfAbsent(CONSOLE, adapter);

        assertEquals(Optional.empty(), captured);
        assertFalse(registry.isCaptured(CONSOLE), "an unresolvable handler must not lock in \"no baseline\" forever");
        assertThrows(IllegalStateException.class, () -> registry.get(CONSOLE));
    }

    @Test
    void aHandlerThatBecomesResolvableLater_getsARealBaselineOnASubsequentCall() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO); // CONSOLE not registered yet
        HandlerBaselineRegistry registry = new HandlerBaselineRegistry();
        registry.captureIfAbsent(CONSOLE, adapter); // first attempt: unresolvable, returns empty

        adapter.addHandler(CONSOLE, Level.WARN); // the handler now exists (e.g. a later redeploy)
        Optional<Level> second = registry.captureIfAbsent(CONSOLE, adapter);

        assertEquals(Optional.of(Level.WARN), second);
        assertTrue(registry.isCaptured(CONSOLE));
    }

    @Test
    void secondCallDoesNotRetouchAdapter() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        HandlerBaselineRegistry registry = new HandlerBaselineRegistry();
        registry.captureIfAbsent(CONSOLE, adapter);

        // Change the adapter's underlying value directly (bypassing baseline) --
        // captureIfAbsent must NOT reflect this on a second call.
        adapter.setHandlerLevel(CONSOLE, Level.ERROR);
        Optional<Level> second = registry.captureIfAbsent(CONSOLE, adapter);

        assertEquals(Optional.of(Level.INFO), second);
    }
}
