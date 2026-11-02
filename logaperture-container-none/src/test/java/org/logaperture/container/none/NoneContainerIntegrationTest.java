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
package org.logaperture.container.none;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import org.logaperture.core.spi.ContextHandle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/plain-jvm-jul.md #1: the backend is chosen by whether Logback is on the class path. */
class NoneContainerIntegrationTest {

    @Test
    void logbackOnTheClassPathChoosesLogback() {
        // This module's tests have logback-classic on the class path.
        assertTrue(NoneContainerIntegration.logbackOnClassPath(getClass().getClassLoader()));
    }

    @Test
    void noLogbackChoosesJul() throws Exception {
        try (URLClassLoader bare = new URLClassLoader(new URL[0], null)) {
            assertFalse(NoneContainerIntegration.logbackOnClassPath(bare));
        }
    }

    @Test
    void noLoaderChoosesJul() {
        assertFalse(NoneContainerIntegration.logbackOnClassPath(null));
    }

    /** doc/specs/class-loader-detector.md C6: nothing to install into before activate(). */
    @Test
    void adoptingAContextBeforeActivationIsAnError() {
        NoneContainerIntegration integration = new NoneContainerIntegration();
        ContextHandle untouched = new ContextHandle() {
            @Override public String stableKey() { return "fake:x"; }
            @Override public String displayName() { return "fake:x"; }
            @Override public org.logaperture.core.spi.LoggingAdapter adapter() { return null; }
        };

        assertThrows(IllegalStateException.class, () -> integration.adoptContext(untouched));
        assertThrows(IllegalStateException.class, () -> integration.releaseContext("fake:x"));
    }
}
