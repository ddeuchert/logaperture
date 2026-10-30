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
package org.logaperture.container.quarkus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkusLogConfigTest {

    @Test
    void parse_readsNamesTypesAndOrderedLists() {
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.file.\"AUDIT\".path", "audit.log",
                "quarkus.log.handler.file.\"AUDIT\".enable", "true",
                "quarkus.log.handler.console.\"SIDE\".format", "%m%n",
                "quarkus.log.category.\"org.example\".handlers", "AUDIT, SIDE",
                "quarkus.log.handlers", "SIDE"));

        assertEquals("file", config.typeOf("AUDIT"));
        assertEquals("console", config.typeOf("SIDE"));
        assertEquals(List.of("AUDIT", "SIDE"), config.categories().get("org.example"));
        assertEquals(List.of("SIDE"), config.root());
    }

    @Test
    void isBuilt_namedConsoleIsOnByDefaultButNamedFileNeedsEnable() {
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.file.\"OFF\".path", "a.log",
                "quarkus.log.handler.file.\"ON\".enable", "true",
                "quarkus.log.handler.console.\"C\".format", "x",
                "quarkus.log.handler.console.\"NOC\".enable", "false"));

        assertFalse(config.isBuilt("OFF"));
        assertTrue(config.isBuilt("ON"));
        assertTrue(config.isBuilt("C"));
        assertFalse(config.isBuilt("NOC"));
        assertFalse(config.isBuilt("unknown"));
    }

    @Test
    void builtAmong_dropsNamesQuarkusDoesNotBuild() {
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.file.\"OFF\".path", "a.log",
                "quarkus.log.handler.console.\"C\".format", "x"));

        assertEquals(List.of("C"), config.builtAmong(List.of("OFF", "C", "missing")));
    }

    @Test
    void aNameConfiguredUnderTwoTypesIsAmbiguous() {
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.file.\"X\".enable", "true",
                "quarkus.log.handler.console.\"X\".format", "x"));

        assertNull(config.typeOf("X"));
        assertFalse(config.isBuilt("X"));
    }

    @Test
    void unquotedSingleSegmentNamesAndCategoriesAreRead() {
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.console.AUDIT.format", "x",
                "quarkus.log.category.app.handlers", "AUDIT"));

        assertEquals("console", config.typeOf("AUDIT"));
        assertEquals(List.of("AUDIT"), config.categories().get("app"));
    }

    @Test
    void anEnvironmentVariableHandlerNameMatchesTheListedNameIgnoringCase() {
        // QUARKUS_LOG_HANDLER_FILE__AUDIT__ENABLE reaches us as the handler "audit"; the list says AUDIT
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.file.\"audit\".enable", "true",
                "quarkus.log.category.\"org.example\".handlers", "AUDIT"));

        assertEquals("file", config.typeOf("AUDIT"));
        assertTrue(config.isBuilt("AUDIT"));
        assertEquals(List.of("AUDIT"), config.categories().get("org.example"));
    }

    @Test
    void attributesOf_keepsWhatIsWrittenForTheCheckedProperties_foldingTheName() {
        QuarkusLogConfig config = QuarkusLogConfig.parse(Map.of(
                "quarkus.log.handler.file.\"AUDIT\".path", " audit.log ",
                "quarkus.log.handler.file.audit.level", "DEBUG",
                "quarkus.log.handler.syslog.\"S\".endpoint", "logs:514",
                "quarkus.log.handler.console.\"C\".format", "%m%n"));

        assertEquals(Map.of("path", "audit.log"), config.attributesOf("AUDIT"));
        assertEquals(Map.of("endpoint", "logs:514"), config.attributesOf("s"));
        assertEquals(Map.of("format", "%m%n"), config.attributesOf("C"));
        assertEquals(Map.of(), config.attributesOf("unknown"));
    }
}
