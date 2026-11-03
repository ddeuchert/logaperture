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

import org.junit.jupiter.api.Test;
import org.logaperture.core.loader.BridgedFramework;
import org.logaperture.core.loader.FrameworkWatch;
import org.logaperture.core.loader.KnownFrameworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The watch in {@code core} only names things as strings; this keeps the names true. */
class WatchMatchesAdapterTest {

    private static final FrameworkWatch WATCH = KnownFrameworks.LOG4J2;

    @Test
    void theEntryClassExists_andIsABridgedFramework() throws Exception {
        Class<?> entry = Class.forName(WATCH.entryClass());
        assertTrue(BridgedFramework.class.isAssignableFrom(entry));
        assertEquals(Log4j2Framework.class, entry);
        entry.getDeclaredConstructor().newInstance(); // the detector builds it with no arguments
    }

    @Test
    void theMarkerIsLog4jsContextClass() throws Exception {
        assertEquals(org.apache.logging.log4j.core.LoggerContext.class, Class.forName(WATCH.markerClass()));
    }

    @Test
    void theOwnerSuppliesEveryLog4jPackage() {
        assertTrue(WATCH.ownerPackages().contains("org.apache.logging.log4j."));
        assertTrue(WATCH.markerClass().startsWith(WATCH.ownerPackages().get(0)));
    }
}
