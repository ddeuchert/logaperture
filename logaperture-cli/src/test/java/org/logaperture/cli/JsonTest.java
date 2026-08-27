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
package org.logaperture.cli;

import org.junit.jupiter.api.Test;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void loggerObjectCarriesEveryFieldWithNullsAsJsonNull() {
        LoggerInfoData row = new LoggerInfoData("com.acme", null, "INFO", false, null, null, null, null);
        assertEquals(
                "{\"name\":\"com.acme\",\"configuredLevel\":null,\"effectiveLevel\":\"INFO\","
                        + "\"overrideActive\":false,\"overrideSource\":null,\"overrideReason\":null,"
                        + "\"tier\":null,\"expiresAt\":null}",
                Json.logger(row));
    }

    @Test
    void overrideObjectKeyOrderMatchesTheSpec() {
        LevelOverrideData data = new LevelOverrideData(
                "com.acme", "DEBUG", true, "INC-1", "2026-08-25T00:00:00Z", "jmx", "FOR", "2026-08-25T04:00:00Z");
        assertEquals(
                "{\"loggerName\":\"com.acme\",\"level\":\"DEBUG\",\"includeChildren\":true,\"reason\":\"INC-1\","
                        + "\"appliedAt\":\"2026-08-25T00:00:00Z\",\"source\":\"jmx\",\"tier\":\"FOR\","
                        + "\"expiresAt\":\"2026-08-25T04:00:00Z\"}",
                Json.override(data));
    }

    @Test
    void stringsAreEscaped() {
        LoggerInfoData row =
                new LoggerInfoData("a", "INFO", "INFO", true, "jmx", "line1\nline2 \"quoted\" \\ tab\t", "STICKY", null);
        String json = Json.logger(row);
        assertTrue(json.contains("\"line1\\nline2 \\\"quoted\\\" \\\\ tab\\t\""), json);
    }

    @Test
    void arrayWrapsElements() {
        LoggerInfoData a = new LoggerInfoData("a", "INFO", "INFO", false, null, null, null, null);
        LoggerInfoData b = new LoggerInfoData("b", "INFO", "WARN", false, null, null, null, null);
        String json = Json.loggers(List.of(a, b));
        assertTrue(json.startsWith("[{"), json);
        assertTrue(json.endsWith("}]"), json);
        assertTrue(json.contains("},{"), json);
    }

    @Test
    void revertedCountIsABareNumber() {
        assertEquals("{\"reverted\":3}", Json.revertedCount(3));
    }
}
