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

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkusConfigReaderTest {

    /**
     * A configuration as SmallRye's: the set properties are listed; a mapping default (a named
     * handler's {@code enabled}) is answered but not listed.
     */
    private static Config config(Map<String, String> listed, Map<String, String> defaults) {
        return new Config() {
            @Override
            public Iterable<String> getPropertyNames() {
                return listed.keySet();
            }

            @Override
            public <T> Optional<T> getOptionalValue(String name, Class<T> type) {
                String value = listed.containsKey(name) ? listed.get(name) : defaults.get(name);
                return Optional.ofNullable(value).map(type::cast);
            }
        };
    }

    @AfterEach
    void clear() {
        ConfigProvider.current = null;
    }

    @Test
    void read_asksForTheEnabledOfEveryNamedHandlerAndNothingElseUnlisted() {
        Map<String, String> listed = new HashMap<>(Map.of(
                "quarkus.log.handler.file.\"AUDIT\".path", "audit.log",
                "quarkus.log.handler.syslog.\"SYS\".app-name", "x",
                "quarkus.log.handler.socket.sock.endpoint", "localhost:4560", // from an environment variable
                "quarkus.log.category.\"org.example\".handlers", "AUDIT,SYS"));
        Map<String, String> defaults = Map.of(
                "quarkus.log.handler.file.\"AUDIT\".enabled", "false",
                "quarkus.log.handler.syslog.\"SYS\".enabled", "true", // a version whose default differs
                "quarkus.log.handler.socket.sock.enabled", "false",
                "quarkus.log.handler.file.\"OTHER\".enabled", "false");
        ConfigProvider.current = config(listed, defaults);

        Map<String, String> read = QuarkusConfigReader.read(getClass().getClassLoader()).orElseThrow();

        assertEquals("false", read.get("quarkus.log.handler.file.\"AUDIT\".enabled"));
        assertEquals("true", read.get("quarkus.log.handler.syslog.\"SYS\".enabled"));
        assertEquals("false", read.get("quarkus.log.handler.socket.sock.enabled"));
        assertFalse(read.containsKey("quarkus.log.handler.file.\"OTHER\".enabled"), "only configured names");
        assertTrue(QuarkusLogConfig.parse(read).isBuilt("SYS"), "the running version's default decides");
    }

    @Test
    void read_keepsAnEnabledThatWasSet() {
        ConfigProvider.current = config(
                Map.of("quarkus.log.handler.file.\"AUDIT\".enabled", "true"),
                Map.of("quarkus.log.handler.file.\"AUDIT\".enabled", "false"));

        Map<String, String> read = QuarkusConfigReader.read(getClass().getClassLoader()).orElseThrow();

        assertEquals("true", read.get("quarkus.log.handler.file.\"AUDIT\".enabled"));
    }
}
