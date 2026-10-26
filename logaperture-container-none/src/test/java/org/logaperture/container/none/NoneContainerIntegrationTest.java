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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
