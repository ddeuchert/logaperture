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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistedRule;
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
 * added a {@code patternRules:} list alongside {@code overrides:}/{@code
 * handlerOverrides:}, and dropped the meaningless {@code includeChildren:}
 * field from an {@code overrides} record in favor of {@code originPattern:}.
 *
 * <p>Schema version 5 (doc/specs/pattern-selection-semantics.md
 * "Persistence — state file schema", issue #49) retires both of those:
 * {@code patternRules:} is no longer written at all (there is no more
 * standing rule to persist), and {@code originPattern:} is no longer
 * written on an {@code overrides} record (there is no rule to attribute an
 * override to). A version-4 file's {@code patternRules:} section is still
 * recognized on read, so its records don't leak into the overrides list,
 * but they are dropped entirely rather than resumed from — every logger a
 * rule had already matched keeps its own independent {@code overrides:}
 * row regardless, so nothing is lost by this. An {@code originPattern:}
 * field on a legacy {@code overrides:} record is likewise read and
 * ignored, the same convention {@code includeChildren:} already set. A
 * version-1/2/3 file (no {@code patternRules:} section at all) still
 * parses, as it always has.
 *
 * <p>Schema version 6 (doc/specs/handler-floor-control.md "Default handler
 * group", issue #28) adds a {@code defaultHandlerMembers:} list alongside
 * {@code overrides:}/{@code handlerOverrides:} — {@code DEFAULT_HANDLERS}'s
 * explicit membership, when one has been assigned. Unlike the other two
 * sections this is a flat list of quoted names, not a list of multi-field
 * records: membership is a single config value, not N independent things
 * each with their own reason/tier/etc. A version-1..5 file (no {@code
 * defaultHandlerMembers:} section at all) still parses, as an empty list —
 * "nothing explicitly assigned yet", exactly {@code
 * DefaultHandlerGroupRegistry}'s own stateless-by-default starting point.
 *
 * <p>Schema version 7 (doc/specs/rule-pipeline-foundation.md "Persistence")
 * adds a {@code rules:} list alongside {@code overrides:}/{@code
 * handlerOverrides:} — one record per attached {@code LogRule}, keyed by
 * {@code id} rather than logger name (unlike an override, more than one
 * rule can share a logger). A version-1..6 file (no {@code rules:} section
 * at all) still parses, as an empty list — no rule was ever attachable
 * before this schema existed.
 */
final class StateFileFormat {

    private static final int SCHEMA_VERSION = 7;
    private static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    private StateFileFormat() {
    }

    static String write(List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides,
            List<String> defaultHandlerMembers, List<PersistedRule> rules) {
        StringBuilder out = new StringBuilder();
        out.append("schemaVersion: ").append(SCHEMA_VERSION).append('\n');

        if (overrides.isEmpty()) {
            out.append("overrides: []\n");
        } else {
            out.append("overrides:\n");
            for (LevelOverride override : overrides) {
                out.append("  - loggerName: ").append(quote(override.loggerName())).append('\n');
                out.append("    level: ").append(override.level().name()).append('\n');
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

        if (defaultHandlerMembers.isEmpty()) {
            out.append("defaultHandlerMembers: []\n");
        } else {
            out.append("defaultHandlerMembers:\n");
            for (String name : defaultHandlerMembers) {
                out.append("  - ").append(quote(name)).append('\n');
            }
        }

        if (rules.isEmpty()) {
            out.append("rules: []\n");
        } else {
            out.append("rules:\n");
            for (PersistedRule rule : rules) {
                out.append("  - id: ").append(quote(rule.id())).append('\n');
                out.append("    loggerName: ").append(quote(rule.loggerName())).append('\n');
                out.append("    action: ").append(quote(rule.action())).append('\n');
                CompiledMatchers m = rule.matchers();
                out.append("    levelAtMost: ").append(m.levelAtMost() == null ? "null" : m.levelAtMost().name()).append('\n');
                out.append("    messageContains: ").append(m.messageContains() == null ? "null" : quote(m.messageContains())).append('\n');
                out.append("    messageIgnoreCase: ").append(m.messageIgnoreCase()).append('\n');
                out.append("    throwableType: ").append(m.throwableType() == null ? "null" : quote(m.throwableType())).append('\n');
                out.append("    throwableMessageContains: ")
                        .append(m.throwableMessageContains() == null ? "null" : quote(m.throwableMessageContains())).append('\n');
                out.append("    anyCause: ").append(m.anyCause()).append('\n');
                out.append("    reason: ").append(rule.reason() == null ? "null" : quote(rule.reason())).append('\n');
                out.append("    tier: ").append(rule.tier().name()).append('\n');
                out.append("    expiresAt: ").append(rule.expiresAt() == null ? "null" : rule.expiresAt()).append('\n');
                out.append("    createdAt: ").append(rule.createdAt()).append('\n');
            }
        }
        return out.toString();
    }

    /** Everything {@link #parse} recovered from one file. */
    record Parsed(List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides,
            List<String> defaultHandlerMembers, List<PersistedRule> rules) {
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
        // A range, not an enumerated OR-chain (a code-review finding): every
        // version from 1 through the current one is contiguous and always
        // readable, so this needs no edit at the next schema bump.
        if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION || schemaVersion > SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported or missing state file schemaVersion: " + schemaVersion);
        }

        List<LevelOverride> overrides = new ArrayList<>();
        List<HandlerLevelOverride> handlerOverrides = new ArrayList<>();
        List<String> defaultHandlerMembers = new ArrayList<>();
        List<PersistedRule> rules = new ArrayList<>();
        Map<String, String> current = null;
        Section section = Section.OVERRIDES;

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("schemaVersion:")) {
                continue;
            }
            if (line.startsWith("overrides:")) {
                flush(current, section, overrides, handlerOverrides, rules);
                current = null;
                section = Section.OVERRIDES;
                continue; // "overrides:" header, or "overrides: []" for an empty list
            }
            if (line.startsWith("handlerOverrides:")) {
                flush(current, section, overrides, handlerOverrides, rules);
                current = null;
                section = Section.HANDLER_OVERRIDES;
                continue;
            }
            if (line.startsWith("defaultHandlerMembers:")) {
                flush(current, section, overrides, handlerOverrides, rules);
                current = null;
                section = Section.DEFAULT_HANDLER_MEMBERS;
                continue; // "defaultHandlerMembers:" header, or "defaultHandlerMembers: []"
            }
            if (line.startsWith("rules:")) {
                flush(current, section, overrides, handlerOverrides, rules);
                current = null;
                section = Section.RULES;
                continue; // "rules:" header, or "rules: []" for an empty list
            }
            if (line.startsWith("patternRules:")) {
                // A schema-4-only section (doc/specs/pattern-level-targeting.md,
                // now retired). Recognized here only so its records don't leak
                // into the overrides list below -- its contents are dropped
                // entirely, not resumed from (doc/specs/
                // pattern-selection-semantics.md "Persistence").
                flush(current, section, overrides, handlerOverrides, rules);
                current = null;
                section = Section.PATTERN_RULES_LEGACY;
                continue;
            }
            if (line.startsWith("- ")) {
                if (section == Section.DEFAULT_HANDLER_MEMBERS) {
                    // A flat scalar list, not a multi-field record -- one
                    // name per "- " line, added straight away rather than
                    // accumulated into `current`.
                    defaultHandlerMembers.add(unquote(line.substring(2).trim()));
                    continue;
                }
                flush(current, section, overrides, handlerOverrides, rules);
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
        flush(current, section, overrides, handlerOverrides, rules);
        return new Parsed(overrides, handlerOverrides, defaultHandlerMembers, rules);
    }

    private enum Section {
        OVERRIDES, HANDLER_OVERRIDES, PATTERN_RULES_LEGACY, DEFAULT_HANDLER_MEMBERS, RULES
    }

    private static void flush(Map<String, String> current, Section section,
            List<LevelOverride> overrides, List<HandlerLevelOverride> handlerOverrides, List<PersistedRule> rules) {
        if (current == null) {
            return;
        }
        switch (section) {
            case HANDLER_OVERRIDES -> handlerOverrides.add(toHandlerOverride(current));
            case RULES -> rules.add(toRule(current));
            case PATTERN_RULES_LEGACY, DEFAULT_HANDLER_MEMBERS -> {
                // PATTERN_RULES_LEGACY: dropped entirely -- doc/specs/
                // pattern-selection-semantics.md "Persistence — state file
                // schema". DEFAULT_HANDLER_MEMBERS: never reaches here --
                // its "- " lines are handled directly in the loop above,
                // never accumulated into `current` -- listed only so this
                // switch stays exhaustive over every Section value.
            }
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
        // "includeChildren:"/"originPattern:" are both retired, schema-<5
        // fields -- read and ignored (never rejected) by simply never being
        // looked up here, same convention as "mode:" defaulting on a
        // pre-AUTO handler override.
        return new LevelOverride(
                unquote(fields.get("loggerName")),
                Level.valueOf(fields.get("level")),
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

    private static PersistedRule toRule(Map<String, String> fields) {
        String levelAtMost = nullable(fields.get("levelAtMost"));
        CompiledMatchers matchers = new CompiledMatchers(
                levelAtMost == null ? null : Level.valueOf(levelAtMost),
                nullable(fields.get("messageContains")) == null ? null : unquote(fields.get("messageContains")),
                Boolean.parseBoolean(fields.get("messageIgnoreCase")),
                nullable(fields.get("throwableType")) == null ? null : unquote(fields.get("throwableType")),
                nullable(fields.get("throwableMessageContains")) == null ? null
                        : unquote(fields.get("throwableMessageContains")),
                Boolean.parseBoolean(fields.get("anyCause")));
        return new PersistedRule(
                unquote(fields.get("id")),
                unquote(fields.get("loggerName")),
                unquote(fields.get("action")),
                matchers,
                nullable(fields.get("reason")) == null ? null : unquote(fields.get("reason")),
                PersistenceTier.valueOf(fields.get("tier")),
                nullable(fields.get("expiresAt")) == null ? null : Instant.parse(fields.get("expiresAt")),
                Instant.parse(fields.get("createdAt")));
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
