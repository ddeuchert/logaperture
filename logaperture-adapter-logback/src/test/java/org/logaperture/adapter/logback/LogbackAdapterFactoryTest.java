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

import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackAdapterFactoryTest {

    @Test
    void forCurrentContext_bindsToTheRealSlf4jLoggerContext() {
        // This module's test classpath has logback-classic on it, so SLF4J
        // resolves to a real LoggerContext -- proves the happy path against
        // the actual static binding, not just a throwaway context.
        LoggingAdapter adapter = LogbackAdapterFactory.forCurrentContext();

        assertNotNull(adapter);
        assertTrue(adapter.knownLoggerNames().contains("ROOT"));

        adapter.applyLevel("org.logaperture.adapter.logback.factorytest.Probe", Level.DEBUG);
        assertTrue(adapter.knownLoggerNames().contains("org.logaperture.adapter.logback.factorytest.Probe"));
    }
}
