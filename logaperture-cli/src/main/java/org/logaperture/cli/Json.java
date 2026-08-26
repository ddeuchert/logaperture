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

import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;

import java.util.List;
import java.util.StringJoiner;

/**
 * A hand-written JSON writer for the {@code --json} output. The shapes are
 * tiny and fully controlled on both ends, so pulling in a JSON library
 * would add this graph's first third-party dependency for nothing — same
 * reasoning as doc/specs/persistence.md's hand-written state-file reader.
 * Object key order matches the {@code LoggerInfoData} / {@code
 * LevelOverrideData} getter order named in doc/specs/cli-transport.md.
 */
final class Json {

    private Json() {
    }

    static String loggers(List<LoggerInfoData> rows) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (LoggerInfoData row : rows) {
            array.add(logger(row));
        }
        return array.toString();
    }

    static String logger(LoggerInfoData row) {
        return new Obj()
                .str("name", row.getName())
                .str("configuredLevel", row.getConfiguredLevel())
                .str("effectiveLevel", row.getEffectiveLevel())
                .bool("overrideActive", row.isOverrideActive())
                .str("overrideSource", row.getOverrideSource())
                .str("overrideReason", row.getOverrideReason())
                .str("tier", row.getTier())
                .str("expiresAt", row.getExpiresAt())
                .toString();
    }

    static String override(LevelOverrideData data) {
        return new Obj()
                .str("loggerName", data.getLoggerName())
                .str("level", data.getLevel())
                .bool("includeChildren", data.isIncludeChildren())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt())
                .toString();
    }

    static String revertedCount(long count) {
        return "{\"reverted\":" + count + "}";
    }

    private static final class Obj {
        private final StringJoiner body = new StringJoiner(",", "{", "}");

        Obj str(String key, String value) {
            body.add(quote(key) + ":" + (value == null ? "null" : quote(value)));
            return this;
        }

        Obj bool(String key, boolean value) {
            body.add(quote(key) + ":" + value);
            return this;
        }

        @Override
        public String toString() {
            return body.toString();
        }
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
