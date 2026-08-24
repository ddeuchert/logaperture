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
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OverrideRegistry#removeIfCurrent} is the atomic compare-and-remove
 * {@link LevelControlService#applyReset} relies on to keep a stale caller
 * (the expiry sweep reading a snapshot that's since moved on) from
 * clobbering a newer override for the same logger -- a code-review finding
 * against this PR.
 */
class OverrideRegistryTest {

    private static LevelOverride override(String loggerName, Level level) {
        return new LevelOverride(loggerName, level, false, null, Instant.now(), "jmx", PersistenceTier.SESSION, null);
    }

    @Test
    void removeIfCurrent_matchingValue_removesAndReturnsTrue() {
        OverrideRegistry registry = new OverrideRegistry();
        LevelOverride current = override("com.acme.Worker", Level.DEBUG);
        registry.put(current);

        assertTrue(registry.removeIfCurrent("com.acme.Worker", current));
        assertTrue(registry.get("com.acme.Worker").isEmpty());
    }

    @Test
    void removeIfCurrent_staleValue_leavesTheNewerEntryUntouched() {
        OverrideRegistry registry = new OverrideRegistry();
        LevelOverride stale = override("com.acme.Worker", Level.DEBUG);
        registry.put(stale);
        LevelOverride replacement = override("com.acme.Worker", Level.WARN); // a "concurrent" setLevel
        registry.put(replacement);

        assertFalse(registry.removeIfCurrent("com.acme.Worker", stale));
        assertTrue(registry.get("com.acme.Worker").isPresent());
        assertTrue(registry.get("com.acme.Worker").get().equals(replacement));
    }

    @Test
    void removeIfCurrent_alreadyGone_returnsFalse() {
        OverrideRegistry registry = new OverrideRegistry();

        assertFalse(registry.removeIfCurrent("com.acme.NeverThere", override("com.acme.NeverThere", Level.DEBUG)));
    }
}
