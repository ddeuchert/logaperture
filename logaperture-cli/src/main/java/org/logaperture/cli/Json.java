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

import org.logaperture.control.jmx.HandlerFloorData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;

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
        return overrideObj(data).toString();
    }

    private static Obj overrideObj(LevelOverrideData data) {
        return new Obj()
                .str("loggerName", data.getLoggerName())
                .str("level", data.getLevel())
                .bool("includeChildren", data.isIncludeChildren())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt());
    }

    /**
     * {@code setLevel}'s full JSON result: the override, plus {@code
     * warnings} — one entry per handler that will still swallow records at
     * the new level (doc/specs/handler-floor-control.md "Warning on level
     * commands"), empty on the common case of no such handler.
     */
    static String setLevelResult(SetLevelResultData result) {
        StringJoiner warnings = new StringJoiner(",", "[", "]");
        for (HandlerFloorData floor : result.getBlockingHandlers()) {
            warnings.add(new Obj()
                    .str("handlerRef", floor.getHandlerRef())
                    .str("currentLevel", floor.getCurrentLevel())
                    .toString());
        }
        return overrideObj(result.getOverride()).raw("warnings", warnings.toString()).toString();
    }

    static String handlerOverride(HandlerLevelOverrideData data) {
        return new Obj()
                .str("handlerRef", data.getHandlerRef())
                .str("level", data.getLevel())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt())
                .toString();
    }

    /** {@code logctl handler} against a framework whose handlers have no level of their own (Logback, {@code none}). */
    static String handlerNoOp(String handlerRef) {
        return new Obj()
                .str("handlerRef", handlerRef)
                .bool("changed", false)
                .toString();
    }

    static String handlerReset(String handlerRef) {
        return new Obj()
                .str("handlerRef", handlerRef)
                .bool("reset", true)
                .toString();
    }

    static String revertedCount(long count) {
        return "{\"reverted\":" + count + "}";
    }

    /**
     * {@code reset <logger>} fell through with no post-reset {@link LoggerInfoData}
     * to emit — the logger is not "Live" and holds no override, so {@code
     * listLoggers} returns nothing for it. Report what is actually known instead
     * of a bare {@code null}: the name, that no override is active, and whether
     * this call cleared one.
     */
    static String reset(String loggerName, boolean wasOverridden) {
        return new Obj()
                .str("name", loggerName)
                .bool("overrideActive", false)
                .bool("wasOverridden", wasOverridden)
                .toString();
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

        /** Embeds an already-serialized JSON value verbatim -- e.g. an array built from other {@link Obj}s. */
        Obj raw(String key, String rawJsonValue) {
            body.add(quote(key) + ":" + rawJsonValue);
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
