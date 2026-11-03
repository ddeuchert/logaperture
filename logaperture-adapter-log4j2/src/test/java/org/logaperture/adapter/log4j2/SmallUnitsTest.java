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

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallUnitsTest {

    @Test
    void everyApiLevelRoundTrips() {
        for (org.logaperture.api.Level level : org.logaperture.api.Level.values()) {
            assertEquals(level, LevelMapper.toApi(LevelMapper.toLog4j(level)));
        }
    }

    @Test
    void fatalReadsBackAsError_andACustomLevelAsTheNearest() {
        assertEquals(org.logaperture.api.Level.ERROR, LevelMapper.toApi(Level.FATAL));
        assertEquals(org.logaperture.api.Level.INFO, LevelMapper.toApi(Level.forName("NOTICE", Level.INFO.intLevel() - 50)));
        assertNull(LevelMapper.toApi(null));
    }

    @Test
    void versions_below220AreTooOld_otherwiseNot() {
        assertTrue(Log4j2Version.isTooOld("2.19.0"));
        assertTrue(Log4j2Version.isTooOld("2.17.2"));
        assertTrue(Log4j2Version.isTooOld("1.2.17"));
        assertFalse(Log4j2Version.isTooOld("2.20.0"));
        assertFalse(Log4j2Version.isTooOld("2.25.5"));
        assertFalse(Log4j2Version.isTooOld("2.26.0-SNAPSHOT"));
        assertFalse(Log4j2Version.isTooOld("3.0.0"));
    }

    @Test
    void anUnknownVersionIsNotRefused() {
        assertFalse(Log4j2Version.isTooOld(null));
        assertFalse(Log4j2Version.isTooOld("weird"));
    }

    @Test
    void contextNames_useAWebContextPath_elseACounter() {
        assertEquals("/appA", ContextNames.of("/appA", 1));
        assertEquals("/appA", ContextNames.of("/appA", 3));
        assertEquals("log4j2", ContextNames.of("AppClassLoader@1b2c3d", 1));
        assertEquals("log4j2", ContextNames.of(null, 1));
        assertEquals("log4j2-2", ContextNames.of("AppClassLoader@1b2c3d", 2));
        assertEquals("log4j2", ContextNames.of("/", 1), "a bare slash is not a context path");
    }

    @Test
    void aVersionIsBelievedOnlyWhenTheManifestTitleNamesLog4j() {
        assertEquals("2.24.3", Log4j2Version.believed("Apache Log4j Core", "2.24.3"));
        // A shaded or assembled jar: the manifest is the application's own, whatever its version says.
        assertNull(Log4j2Version.believed("my-shop", "1.4.0"));
        assertNull(Log4j2Version.believed(null, "2.5"));
        assertNull(Log4j2Version.believed("Apache Log4j Core", null));
        assertFalse(Log4j2Version.isTooOld(Log4j2Version.believed("my-shop", "1.4.0")),
                "an application's version is unknown, not 'too old'");
    }

    /** Pinned to the version in the jar's own pom.properties, a source independent of the manifest the code reads. */
    @Test
    void theRealLog4jOnTheTestClassPathIsBelieved_andIsTheVersionItsJarSays() throws Exception {
        java.util.Properties pom = new java.util.Properties();
        try (java.io.InputStream in = org.apache.logging.log4j.core.LoggerContext.class.getResourceAsStream(
                "/META-INF/maven/org.apache.logging.log4j/log4j-core/pom.properties")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "log4j-core ships its pom.properties");
            pom.load(in);
        }
        assertEquals(pom.getProperty("version"), Log4j2Version.of(org.apache.logging.log4j.core.LoggerContext.class));
    }

    @Test
    void aRepeatedWebPathGetsADistinctName() {
        assertEquals(java.util.List.of("/appA", "/appA#2", "log4j2"),
                ContextNames.allOf(java.util.Arrays.asList("/appA", "/appA", "AppClassLoader@1")));
        assertEquals(java.util.List.of("log4j2", "log4j2-2"),
                ContextNames.allOf(java.util.Arrays.asList("x@1", "y@2")));
    }
}
