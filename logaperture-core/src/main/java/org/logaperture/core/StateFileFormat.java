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
package org.logaperture.core;

import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hand-written writer/parser for {@link FileStateStore}'s on-disk schema
 * — deliberately not a general-purpose YAML library, per doc/specs/
 * persistence.md "File format": this schema is a flat list of scalar-field
 * records the agent itself fully controls on both ends, and pulling in a
 * third-party parser for a format only this code ever writes would add the
 * project's first third-party {@code core} dependency for a problem this
 * constrained solves directly. Output is valid YAML -- the constraint is on
 * the reader's generality, not the writer's correctness.
 *
 * <p>Schema version 2 (doc/specs/handler-floor-control.md "Data model")
 * adds a {@code handlerOverrides:} list alongside {@code overrides:}, same
 * per-record shape convention. A version-1 file (no {@code handlerOverrides:}
 * section at all) still parses: the section is simply absent, exactly like
 * an empty list.
 */
final class StateFileFormat {

    private static final int SCHEMA_VERSION = 2;

    private StateFileFormat() {
    }

    static String write(List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides) {
        StringBuilder out = new StringBuilder();
        out.append("schemaVersion: ").append(SCHEMA_VERSION).append('\n');

        if (overrides.isEmpty()) {
            out.append("overrides: []\n");
        } else {
            out.append("overrides:\n");
            for (LevelOverride override : overrides) {
                out.append("  - loggerName: ").append(quote(override.loggerName())).append('\n');
                out.append("    level: ").append(override.level().name()).append('\n');
                out.append("    includeChildren: ").append(override.includeChildren()).append('\n');
                out.append("    reason: ").append(override.reason() == null ? "null" : quote(override.reason())).append('\n');
                out.append("    appliedAt: ").append(override.appliedAt()).append('\n');
                out.append("    source: ").append(quote(override.source())).append('\n');
                out.append("    tier: ").append(override.tier().name()).append('\n');
                out.append("    expiresAt: ").append(override.expiresAt() == null ? "null" : override.expiresAt()).append('\n');
            }
        }

        if (handlerOverrides.isEmpty()) {
            out.append("handlerOverrides: []\n");
        } else {
            out.append("handlerOverrides:\n");
            for (HandlerLevelOverride override : handlerOverrides) {
                out.append("  - handlerRef: ").append(quote(override.handlerRef().value())).append('\n');
                out.append("    level: ").append(override.level().name()).append('\n');
                out.append("    reason: ").append(override.reason() == null ? "null" : quote(override.reason())).append('\n');
                out.append("    appliedAt: ").append(override.appliedAt()).append('\n');
                out.append("    source: ").append(quote(override.source())).append('\n');
                out.append("    tier: ").append(override.tier().name()).append('\n');
                out.append("    expiresAt: ").append(override.expiresAt() == null ? "null" : override.expiresAt()).append('\n');
            }
        }
        return out.toString();
    }

    /** Everything {@link #parse} recovered from one file: both lists. */
    record Parsed(List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides) {
    }

    /**
     * Tolerant by design, per §6.3's "human-readable and hand-editable" bar
     * -- an unrecognized line is skipped rather than rejected. A missing or
     * unsupported {@code schemaVersion} is the one thing treated as
     * corrupt, since every field below is read positionally within that
     * assumption. A version-1 file (no {@code handlerOverrides:} section)
     * parses fine, with an empty handler-override list -- this reader
     * doesn't require the section to be present.
     *
     * @throws IllegalStateException if {@code schemaVersion} is missing or unsupported
     */
    static Parsed parse(String content) {
        int schemaVersion = extractSchemaVersion(content);
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported or missing state file schemaVersion: " + schemaVersion);
        }

        List<LevelOverride> overrides = new ArrayList<>();
        List<HandlerLevelOverride> handlerOverrides = new ArrayList<>();
        Map<String, String> current = null;
        boolean inHandlerSection = false;

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("schemaVersion:")) {
                continue;
            }
            if (line.startsWith("overrides:")) {
                flush(current, inHandlerSection, overrides, handlerOverrides);
                current = null;
                inHandlerSection = false;
                continue; // "overrides:" header, or "overrides: []" for an empty list
            }
            if (line.startsWith("handlerOverrides:")) {
                flush(current, inHandlerSection, overrides, handlerOverrides);
                current = null;
                inHandlerSection = true;
                continue;
            }
            if (line.startsWith("- ")) {
                flush(current, inHandlerSection, overrides, handlerOverrides);
                current = new LinkedHashMap<>();
                line = line.substring(2);
            }
            if (current == null) {
                continue; // stray line outside any record -- tolerated, not fatal
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            current.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        flush(current, inHandlerSection, overrides, handlerOverrides);
        return new Parsed(overrides, handlerOverrides);
    }

    private static void flush(Map<String, String> current, boolean inHandlerSection,
            List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides) {
        if (current == null) {
            return;
        }
        if (inHandlerSection) {
            handlerOverrides.add(toHandlerOverride(current));
        } else {
            overrides.add(toOverride(current));
        }
    }

    private static int extractSchemaVersion(String content) {
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.startsWith("schemaVersion:")) {
                try {
                    return Integer.parseInt(line.substring("schemaVersion:".length()).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static LevelOverride toOverride(Map<String, String> fields) {
        return new LevelOverride(
                unquote(fields.get("loggerName")),
                Level.valueOf(fields.get("level")),
                Boolean.parseBoolean(fields.get("includeChildren")),
                nullable(fields.get("reason")) == null ? null : unquote(fields.get("reason")),
                Instant.parse(fields.get("appliedAt")),
                unquote(fields.get("source")),
                PersistenceTier.valueOf(fields.get("tier")),
                nullable(fields.get("expiresAt")) == null ? null : Instant.parse(fields.get("expiresAt")));
    }

    private static HandlerLevelOverride toHandlerOverride(Map<String, String> fields) {
        return new HandlerLevelOverride(
                new HandlerRef(unquote(fields.get("handlerRef"))),
                Level.valueOf(fields.get("level")),
                nullable(fields.get("reason")) == null ? null : unquote(fields.get("reason")),
                Instant.parse(fields.get("appliedAt")),
                unquote(fields.get("source")),
                PersistenceTier.valueOf(fields.get("tier")),
                nullable(fields.get("expiresAt")) == null ? null : Instant.parse(fields.get("expiresAt")));
    }

    private static String nullable(String value) {
        return (value == null || value.equals("null")) ? null : value;
    }

    /**
     * Escapes backslash and double-quote (so the value round-trips inside
     * a quoted scalar) and, critically, {@code \n}/{@code \r} (so a
     * multi-line {@code reason} can't split one logical record across
     * physical lines and corrupt this line-oriented format for every
     * record after it). Order matters: backslash first, so the backslash
     * introduced by the later replacements is never itself re-escaped.
     */
    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    /** Single left-to-right pass, the inverse of {@link #quote}. */
    private static String unquote(String value) {
        if (value == null || value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value;
        }
        String inner = value.substring(1, value.length() - 1);
        StringBuilder result = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(i + 1);
                switch (next) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    default -> result.append(c).append(next); // unrecognized escape -- keep verbatim
                }
                i++;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
