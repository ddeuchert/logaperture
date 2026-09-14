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

import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PatternRule;
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
 *
 * <p>Schema version 3 (doc/specs/handler-floor-control.md "AUTO handler
 * level", issue #20) adds a {@code mode:} field to each {@code
 * handlerOverrides} record. A version-2 record (no {@code mode:} line) still
 * parses, defaulting to {@link HandlerLevelMode#FIXED} — every override
 * written before this schema bump was, by construction, a fixed one.
 *
 * <p>Schema version 4 (doc/specs/pattern-level-targeting.md "State file")
 * adds a {@code patternRules:} list alongside {@code overrides:}/{@code
 * handlerOverrides:}, and drops the meaningless {@code includeChildren:}
 * field from an {@code overrides} record in favor of {@code originPattern:}.
 * A version-1/2/3 file (no {@code patternRules:} section) still parses,
 * with an empty pattern-rule list; a legacy {@code includeChildren:} field
 * on an override record is read and ignored, never rejected, and {@code
 * originPattern:} defaults to {@code null} when absent.
 */
final class StateFileFormat {

    private static final int SCHEMA_VERSION = 4;

    private StateFileFormat() {
    }

    static String write(List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides,
            List<PatternRule> patternRules) {
        StringBuilder out = new StringBuilder();
        out.append("schemaVersion: ").append(SCHEMA_VERSION).append('\n');

        if (overrides.isEmpty()) {
            out.append("overrides: []\n");
        } else {
            out.append("overrides:\n");
            for (LevelOverride override : overrides) {
                out.append("  - loggerName: ").append(quote(override.loggerName())).append('\n');
                out.append("    level: ").append(override.level().name()).append('\n');
                out.append("    originPattern: ")
                        .append(override.originPattern() == null ? "null" : quote(override.originPattern()))
                        .append('\n');
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
                out.append("    mode: ").append(override.mode().name()).append('\n');
                out.append("    reason: ").append(override.reason() == null ? "null" : quote(override.reason())).append('\n');
                out.append("    appliedAt: ").append(override.appliedAt()).append('\n');
                out.append("    source: ").append(quote(override.source())).append('\n');
                out.append("    tier: ").append(override.tier().name()).append('\n');
                out.append("    expiresAt: ").append(override.expiresAt() == null ? "null" : override.expiresAt()).append('\n');
            }
        }

        if (patternRules.isEmpty()) {
            out.append("patternRules: []\n");
        } else {
            out.append("patternRules:\n");
            for (PatternRule rule : patternRules) {
                out.append("  - pattern: ").append(quote(rule.pattern())).append('\n');
                out.append("    level: ").append(rule.level().name()).append('\n');
                out.append("    reason: ").append(rule.reason() == null ? "null" : quote(rule.reason())).append('\n');
                out.append("    appliedAt: ").append(rule.appliedAt()).append('\n');
                out.append("    source: ").append(quote(rule.source())).append('\n');
                out.append("    tier: ").append(rule.tier().name()).append('\n');
                out.append("    expiresAt: ").append(rule.expiresAt() == null ? "null" : rule.expiresAt()).append('\n');
            }
        }
        return out.toString();
    }

    /** Everything {@link #parse} recovered from one file: all three lists. */
    record Parsed(List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides,
            List<PatternRule> patternRules) {
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
        if (schemaVersion != 1 && schemaVersion != 2 && schemaVersion != 3 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported or missing state file schemaVersion: " + schemaVersion);
        }

        List<LevelOverride> overrides = new ArrayList<>();
        List<HandlerLevelOverride> handlerOverrides = new ArrayList<>();
        List<PatternRule> patternRules = new ArrayList<>();
        Map<String, String> current = null;
        Section section = Section.OVERRIDES;

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("schemaVersion:")) {
                continue;
            }
            if (line.startsWith("overrides:")) {
                flush(current, section, overrides, handlerOverrides, patternRules);
                current = null;
                section = Section.OVERRIDES;
                continue; // "overrides:" header, or "overrides: []" for an empty list
            }
            if (line.startsWith("handlerOverrides:")) {
                flush(current, section, overrides, handlerOverrides, patternRules);
                current = null;
                section = Section.HANDLER_OVERRIDES;
                continue;
            }
            if (line.startsWith("patternRules:")) {
                flush(current, section, overrides, handlerOverrides, patternRules);
                current = null;
                section = Section.PATTERN_RULES;
                continue; // absent entirely in a pre-schema-4 file -- patternRules stays empty
            }
            if (line.startsWith("- ")) {
                flush(current, section, overrides, handlerOverrides, patternRules);
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
        flush(current, section, overrides, handlerOverrides, patternRules);
        return new Parsed(overrides, handlerOverrides, patternRules);
    }

    private enum Section {
        OVERRIDES, HANDLER_OVERRIDES, PATTERN_RULES
    }

    private static void flush(Map<String, String> current, Section section,
            List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides,
            List<PatternRule> patternRules) {
        if (current == null) {
            return;
        }
        switch (section) {
            case HANDLER_OVERRIDES -> handlerOverrides.add(toHandlerOverride(current));
            case PATTERN_RULES -> patternRules.add(toPatternRule(current));
            default -> overrides.add(toOverride(current));
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
        // "includeChildren:" is a schema-version-<4 field, read and ignored
        // (never rejected) -- "originPattern:" replaces it and defaults to
        // null when absent, same convention as "mode:" for a handler override.
        String originPattern = fields.get("originPattern");
        return new LevelOverride(
                unquote(fields.get("loggerName")),
                Level.valueOf(fields.get("level")),
                nullable(originPattern) == null ? null : unquote(originPattern),
                nullable(fields.get("reason")) == null ? null : unquote(fields.get("reason")),
                Instant.parse(fields.get("appliedAt")),
                unquote(fields.get("source")),
                PersistenceTier.valueOf(fields.get("tier")),
                nullable(fields.get("expiresAt")) == null ? null : Instant.parse(fields.get("expiresAt")));
    }

    private static HandlerLevelOverride toHandlerOverride(Map<String, String> fields) {
        // A schema-version-2 record has no "mode:" line at all -- every
        // override written before AUTO existed was, by construction, fixed.
        String mode = fields.get("mode");
        return new HandlerLevelOverride(
                new HandlerRef(unquote(fields.get("handlerRef"))),
                Level.valueOf(fields.get("level")),
                mode == null ? HandlerLevelMode.FIXED : HandlerLevelMode.valueOf(mode),
                nullable(fields.get("reason")) == null ? null : unquote(fields.get("reason")),
                Instant.parse(fields.get("appliedAt")),
                unquote(fields.get("source")),
                PersistenceTier.valueOf(fields.get("tier")),
                nullable(fields.get("expiresAt")) == null ? null : Instant.parse(fields.get("expiresAt")));
    }

    private static PatternRule toPatternRule(Map<String, String> fields) {
        return new PatternRule(
                unquote(fields.get("pattern")),
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
