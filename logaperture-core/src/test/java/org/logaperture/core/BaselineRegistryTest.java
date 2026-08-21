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
import org.logaperture.api.Level;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineRegistryTest {

    @Test
    void notYetCapturedThrowsOnGet() {
        BaselineRegistry registry = new BaselineRegistry();
        assertFalse(registry.isCaptured("com.acme.Worker"));
        assertThrows(IllegalStateException.class, () -> registry.get("com.acme.Worker"));
    }

    @Test
    void capturesExplicitLevelOnce() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.setConfiguredLevel("com.acme.Worker", Level.DEBUG);
        BaselineRegistry registry = new BaselineRegistry();

        Optional<Level> first = registry.captureIfAbsent("com.acme.Worker", adapter);

        assertEquals(Optional.of(Level.DEBUG), first);
        assertTrue(registry.isCaptured("com.acme.Worker"));
    }

    @Test
    void capturesInheritedAsEmptyNotAsNotCaptured() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        // no explicit level set for "com.acme.Worker" -- it should be inherited

        Optional<Level> captured = new BaselineRegistry().captureIfAbsent("com.acme.Worker", adapter);

        assertEquals(Optional.empty(), captured); // "captured as inherited", distinct from "not captured"
    }

    @Test
    void secondCallDoesNotRetouchAdapter() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.setConfiguredLevel("com.acme.Worker", Level.DEBUG);
        BaselineRegistry registry = new BaselineRegistry();
        registry.captureIfAbsent("com.acme.Worker", adapter);

        // Change the adapter's underlying value directly (bypassing baseline) --
        // captureIfAbsent must NOT reflect this on a second call.
        adapter.setConfiguredLevel("com.acme.Worker", Level.ERROR);
        Optional<Level> second = registry.captureIfAbsent("com.acme.Worker", adapter);

        assertEquals(Optional.of(Level.DEBUG), second);
    }
}
