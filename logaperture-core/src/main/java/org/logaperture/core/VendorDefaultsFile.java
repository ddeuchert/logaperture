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
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.RuleExpression;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.VendorYaml.ListNode;
import org.logaperture.core.VendorYaml.MapNode;
import org.logaperture.core.VendorYaml.Node;
import org.logaperture.core.VendorYaml.ScalarNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and validates the vendor defaults file — doc/specs/vendor-defaults.md "The file". All
 * or nothing: every error is collected (Decision M6), and any error at all yields a {@link
 * VendorDefaults.Status#REJECTED rejected} result with no entries. Never throws; a missing or
 * unreadable file is a rejection like any other.
 *
 * <p>Must not touch {@code java.util.logging}: it runs on the {@code premain} thread, before
 * WildFly's LogManager exists (logaperture-spec.md §15.6).
 */
public final class VendorDefaultsFile {

    static final int SCHEMA_VERSION = 1;

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("schemaVersion", "loggers", "handlers", "defaultHandlers", "rules");
    private static final Set<String> LOGGER_FIELDS = Set.of("name", "level", "reason");
    private static final Set<String> HANDLER_FIELDS = Set.of("name", "level", "reason");
    private static final Set<String> COMMON_RULE_FIELDS = Set.of("id", "action", "logger", "below",
            "messageContains", "messageContainsIgnoreCase", "throwable", "throwableMessageContains", "anyCause",
            "reason");
    private static final Set<String> DROP_FIELDS = Set.of("sampleFull");
    private static final Set<String> TRIM_FIELDS = Set.of("frames", "collapseCauses");
    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhd])");

    private VendorDefaultsFile() {
    }

    /** Reads {@code path} (already resolved to absolute) and validates it, with no protected categories. */
    public static VendorDefaults load(Path path) {
        return load(path, ProtectedCategories.none());
    }

    /**
     * Reads {@code path} and validates it. A rule on a {@code protectedCategories} logger is a
     * validation error -- the whole file is rejected -- rather than a per-entry failure at
     * attach time, so the file stays all-or-nothing.
     */
    public static VendorDefaults load(Path path, ProtectedCategories protectedCategories) {
        String content;
        try {
            if (!Files.isRegularFile(path)) {
                return VendorDefaults.rejected(path, List.of("file not found: " + path));
            }
            byte[] bytes = Files.readAllBytes(path);
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return VendorDefaults.rejected(path, List.of("the file is not valid UTF-8"));
        } catch (IOException | SecurityException e) {
            return VendorDefaults.rejected(path, List.of("cannot read the file: " + e.getMessage()));
        }
        return parse(content, path, isWritableByThisAccount(path), protectedCategories);
    }

    /**
     * Validates already-read {@code content}. Package-visible for tests, which pass {@code
     * writable} directly rather than depending on the test machine's file permissions.
     */
    static VendorDefaults parse(String content, Path path, boolean writable) {
        return parse(content, path, writable, ProtectedCategories.none());
    }

    static VendorDefaults parse(String content, Path path, boolean writable,
            ProtectedCategories protectedCategories) {
        MapNode root;
        try {
            root = VendorYaml.parse(content);
        } catch (VendorYaml.SyntaxException e) {
            return VendorDefaults.rejected(path, List.of("line " + e.line() + ": " + e.getMessage()));
        }
        Validation v = new Validation(protectedCategories);
        v.run(root);
        if (!v.errors.isEmpty()) {
            return VendorDefaults.rejected(path, v.errors);
        }
        return VendorDefaults.loaded(path, writable, v.loggers, v.handlers, v.defaultHandlers, v.rules);
    }

    /** Values made only of these characters are written unquoted; everything else is double-quoted. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._$/@%+-]+");

    /**
     * Renders {@code export} in this class's format -- doc/specs/vendor-defaults-export.md, the
     * inverse of {@link #parse}. Output is deterministic (fixed field order, entries in the order
     * given), so equal input gives byte-identical text. Sections with nothing in them are left
     * out; defaults ({@code anyCause: false}, {@code collapseCauses: false}) are not written, but
     * every rule's {@code below} is, since it is the rule's safety bound.
     */
    public static String write(VendorDefaultsExport export) {
        StringBuilder out = new StringBuilder();
        for (String comment : export.headerComments()) {
            out.append("# ").append(comment).append('\n');
        }
        for (String comment : export.skippedComments()) {
            out.append("# ").append(comment).append('\n');
        }
        out.append("schemaVersion: ").append(SCHEMA_VERSION).append('\n');
        if (!export.loggers().isEmpty()) {
            out.append("loggers:\n");
            for (VendorDefaults.LoggerDefault logger : export.loggers()) {
                out.append("  - name: ").append(value(logger.name())).append('\n');
                out.append("    level: ").append(logger.level().name()).append('\n');
                field(out, "reason", logger.reason());
            }
        }
        if (!export.handlers().isEmpty()) {
            out.append("handlers:\n");
            for (VendorDefaults.HandlerDefault handler : export.handlers()) {
                out.append("  - name: ").append(value(handler.ref().value())).append('\n');
                out.append("    level: ")
                        .append(handler.mode() == HandlerLevelMode.AUTO ? "AUTO" : handler.level().name())
                        .append('\n');
                field(out, "reason", handler.reason());
            }
        }
        if (export.defaultHandlers() != null) {
            out.append("defaultHandlers:\n");
            for (HandlerRef ref : export.defaultHandlers()) {
                out.append("  - ").append(value(ref.value())).append('\n');
            }
        }
        if (!export.rules().isEmpty()) {
            out.append("rules:\n");
            for (VendorDefaults.RuleDefault rule : export.rules()) {
                String comment = export.ruleComments().get(rule.id());
                if (comment != null) {
                    out.append("  # ").append(comment).append('\n');
                }
                writeRule(out, rule);
            }
        }
        return out.toString();
    }

    private static void writeRule(StringBuilder out, VendorDefaults.RuleDefault rule) {
        CompiledMatchers m = rule.matchers();
        out.append("  - id: ").append(rule.id().substring(VendorDefaults.RULE_ID_PREFIX.length())).append('\n');
        out.append("    action: ").append(rule.action()).append('\n');
        out.append("    logger: ").append(value(rule.loggerName())).append('\n');
        field(out, m.messageIgnoreCase() ? "messageContainsIgnoreCase" : "messageContains", m.messageContains());
        field(out, "throwable", m.throwableType());
        field(out, "throwableMessageContains", m.throwableMessageContains());
        if (m.anyCause()) {
            out.append("    anyCause: true\n");
        }
        if (m.levelAtMost() != null) {
            // No 'below' line for an unbounded rule (possible only through JMX, never logctl): the
            // file can't say "no bound", and reloads it with the ERROR keep-floor -- the safer side.
            out.append("    below: ").append(RuleExpression.belowFor(m.levelAtMost())).append('\n');
        }
        if ("drop".equals(rule.action())) {
            out.append("    sampleFull: ").append(rule.sampleFull().enabled()
                    ? RuleExpression.duration(rule.sampleFull().every().toMillis()) : "false").append('\n');
        } else if ("trim".equals(rule.action())) {
            out.append("    frames: ").append(rule.frames()).append('\n');
            if (rule.collapseCauses()) {
                out.append("    collapseCauses: true\n");
            }
        }
        field(out, "reason", rule.reason());
    }

    private static void field(StringBuilder out, String key, String value) {
        if (value != null) {
            out.append("    ").append(key).append(": ").append(value(value)).append('\n');
        }
    }

    /**
     * A text value as {@link VendorYaml} reads it back: plain when it is made only of safe
     * characters (and isn't {@code true}/{@code false}, which the parser reads as booleans),
     * otherwise double-quoted with the escapes that parser supports.
     */
    static String value(String text) {
        if (PLAIN.matcher(text).matches() && !text.equals("true") && !text.equals("false")
                && !text.startsWith("-")) {
            return text;
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    /**
     * Whether the account this JVM runs as can write the file, or its directory (which would let
     * it replace the file) -- doc/specs/vendor-defaults.md "Writable-file warning".
     */
    static boolean isWritableByThisAccount(Path path) {
        try {
            Path parent = path.getParent();
            return Files.isWritable(path) || (parent != null && Files.isWritable(parent));
        } catch (SecurityException e) {
            return false;
        }
    }

    /** One pass over the parsed tree, collecting every error rather than stopping at the first. */
    private static final class Validation {
        final List<String> errors = new ArrayList<>();
        final List<VendorDefaults.LoggerDefault> loggers = new ArrayList<>();
        final List<VendorDefaults.HandlerDefault> handlers = new ArrayList<>();
        List<HandlerRef> defaultHandlers;
        final List<VendorDefaults.RuleDefault> rules = new ArrayList<>();
        private final Set<String> seenLoggerNames = new HashSet<>();
        private final Set<String> seenHandlerNames = new HashSet<>();
        private final Set<String> seenRuleIds = new HashSet<>();
        private final ProtectedCategories protectedCategories;

        Validation(ProtectedCategories protectedCategories) {
            this.protectedCategories = protectedCategories;
        }

        void run(MapNode root) {
            for (String key : root.entries().keySet()) {
                if (key.equals("recipes")) {
                    error(root.keyLines().get(key), "'recipes' is not supported yet -- library recipes "
                            + "(issue #92) will add it");
                } else if (!TOP_LEVEL_KEYS.contains(key)) {
                    error(root.keyLines().get(key), "unknown key '" + key + "' (expected one of "
                            + "schemaVersion, loggers, handlers, defaultHandlers, rules)");
                }
            }
            Node version = root.entries().get("schemaVersion");
            if (version == null) {
                error(root.line(), "schemaVersion is missing -- the first line should be 'schemaVersion: "
                        + SCHEMA_VERSION + "'");
            } else if (!(version instanceof ScalarNode scalar) || !scalar.value().equals(
                    String.valueOf(SCHEMA_VERSION))) {
                error(version.line(), "schemaVersion must be " + SCHEMA_VERSION);
            }
            forEachEntry(root, "loggers", this::logger);
            forEachEntry(root, "handlers", this::handler);
            defaultHandlers(root.entries().get("defaultHandlers"));
            forEachEntry(root, "rules", this::rule);
        }

        private void forEachEntry(MapNode root, String key, Consumer<MapNode> perEntry) {
            Node node = root.entries().get(key);
            if (node == null) {
                return;
            }
            if (node instanceof ScalarNode scalar && scalar.value().isEmpty()) {
                return; // "loggers:" with nothing under it -- an empty list
            }
            if (!(node instanceof ListNode list)) {
                error(node.line(), "'" + key + "' must be a list of entries, each starting with '- '");
                return;
            }
            for (Node item : list.items()) {
                if (item instanceof MapNode entry) {
                    perEntry.accept(entry);
                } else {
                    error(item.line(), "each '" + key + "' entry must be a set of 'field: value' lines");
                }
            }
        }

        private void logger(MapNode entry) {
            int before = errors.size();
            unknownFields(entry, LOGGER_FIELDS, "logger");
            String name = loggerName(entry, "name");
            Level level = null;
            String levelText = requiredText(entry, "level");
            if (levelText != null) {
                if (levelText.equalsIgnoreCase("AUTO")) {
                    error(line(entry, "level"), "AUTO applies to handlers only, not loggers");
                } else {
                    level = level(entry, "level", levelText);
                }
            }
            String reason = optionalText(entry, "reason");
            if (name != null && !seenLoggerNames.add(name)) {
                error(line(entry, "name"), "logger '" + name + "' is listed twice");
            }
            if (errors.size() == before) {
                loggers.add(new VendorDefaults.LoggerDefault(name, level, reason));
            }
        }

        private void handler(MapNode entry) {
            int before = errors.size();
            unknownFields(entry, HANDLER_FIELDS, "handler");
            String name = requiredText(entry, "name");
            String levelText = requiredText(entry, "level");
            Level level = null;
            HandlerLevelMode mode = HandlerLevelMode.FIXED;
            if (levelText != null) {
                if (levelText.equalsIgnoreCase("AUTO")) {
                    mode = HandlerLevelMode.AUTO;
                } else {
                    level = level(entry, "level", levelText);
                }
            }
            String reason = optionalText(entry, "reason");
            if (name != null && isGroupName(name)) {
                error(line(entry, "name"), name + " is a group, not a handler -- name each handler instead");
            } else if (name != null && !seenHandlerNames.add(name)) {
                error(line(entry, "name"), "handler '" + name + "' is listed twice");
            }
            if (errors.size() == before) {
                handlers.add(new VendorDefaults.HandlerDefault(new HandlerRef(name), level, mode, reason));
            }
        }

        private void defaultHandlers(Node node) {
            if (node == null) {
                return;
            }
            if (!(node instanceof ListNode list)) {
                error(node.line(), "'defaultHandlers' must be a list of handler names, e.g. [CONSOLE, FILE]");
                return;
            }
            List<HandlerRef> refs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean ok = true;
            for (Node item : list.items()) {
                if (!(item instanceof ScalarNode scalar) || scalar.value().isBlank()) {
                    error(item.line(), "each 'defaultHandlers' item must be a handler name");
                    ok = false;
                } else if (isGroupName(scalar.value())) {
                    error(item.line(), scalar.value() + " is a group, not a handler -- name each handler instead");
                    ok = false;
                } else if (!seen.add(scalar.value())) {
                    error(item.line(), "handler '" + scalar.value() + "' is listed twice in defaultHandlers");
                    ok = false;
                } else {
                    refs.add(new HandlerRef(scalar.value()));
                }
            }
            if (refs.isEmpty() && ok) {
                error(node.line(), "'defaultHandlers' must name at least one handler -- leave it out to keep "
                        + "the automatic choice");
                ok = false;
            }
            if (ok) {
                defaultHandlers = refs;
            }
        }

        private void rule(MapNode entry) {
            int before = errors.size();
            String action = requiredText(entry, "action");
            Set<String> allowed = new HashSet<>(COMMON_RULE_FIELDS);
            if ("drop".equals(action)) {
                allowed.addAll(DROP_FIELDS);
            } else if ("trim".equals(action)) {
                allowed.addAll(TRIM_FIELDS);
            } else if (action != null) {
                error(line(entry, "action"), "action must be 'drop' or 'trim', not '" + action + "'");
            }
            if (action == null || "drop".equals(action) || "trim".equals(action)) {
                unknownFields(entry, allowed, action == null ? "rule" : action + " rule");
            }

            String name = requiredText(entry, "id");
            String id = null;
            if (name != null) {
                if (!RULE_ID.matcher(name).matches()) {
                    error(line(entry, "id"), "id '" + name + "' must be 1-40 characters of a-z, 0-9 and '-'");
                } else {
                    id = VendorDefaults.RULE_ID_PREFIX + name;
                    if (!seenRuleIds.add(id)) {
                        error(line(entry, "id"), "rule id '" + name + "' is used twice");
                    }
                }
            }
            String logger = loggerName(entry, "logger");
            if (logger != null && protectedCategories.isProtected(logger)) {
                error(line(entry, "logger"), "'" + logger + "' is a protected category -- no rule may be "
                        + "attached to it");
            }

            Level levelAtMost = belowLevel(entry);
            String messageContains = optionalText(entry, "messageContains");
            String messageContainsIgnoreCase = optionalText(entry, "messageContainsIgnoreCase");
            if (messageContains != null && messageContainsIgnoreCase != null) {
                error(line(entry, "messageContainsIgnoreCase"),
                        "use only one of messageContains / messageContainsIgnoreCase");
            }
            String throwable = optionalText(entry, "throwable");
            String throwableMessageContains = optionalText(entry, "throwableMessageContains");
            boolean anyCause = bool(entry, "anyCause", false);
            String reason = optionalText(entry, "reason");

            String message = messageContains != null ? messageContains : messageContainsIgnoreCase;
            if ("drop".equals(action) && message == null && throwable == null && throwableMessageContains == null) {
                error(entry.line(), "a drop rule needs at least one of messageContains, "
                        + "messageContainsIgnoreCase, throwable, throwableMessageContains -- use 'loggers:' to "
                        + "change a logger's level instead");
            }

            SampleFullPolicy sampleFull = SampleFullPolicy.defaults();
            int frames = 0;
            boolean collapseCauses = false;
            if ("drop".equals(action)) {
                sampleFull = sampleFull(entry);
            } else if ("trim".equals(action)) {
                frames = frames(entry);
                collapseCauses = bool(entry, "collapseCauses", false);
            }

            if (errors.size() == before) {
                CompiledMatchers matchers = new CompiledMatchers(levelAtMost, message,
                        messageContainsIgnoreCase != null, throwable, throwableMessageContains, anyCause);
                rules.add(new VendorDefaults.RuleDefault(id, action, logger, matchers, reason, sampleFull, frames,
                        collapseCauses));
            }
        }

        /**
         * {@code below: LEVEL} to the compiled bound that excludes {@code LEVEL} itself -- same
         * rule as {@code logctl add rule --below} (doc/specs/drop-rule.md "Data model"),
         * including {@code FATAL} as a pseudo-level resolving to {@code ERROR}. Default ERROR.
         */
        private Level belowLevel(MapNode entry) {
            String text = optionalText(entry, "below");
            if (text == null) {
                return Level.WARN;
            }
            if (text.equalsIgnoreCase("FATAL")) {
                return Level.ERROR;
            }
            Level level = level(entry, "below", text);
            if (level == null) {
                return null;
            }
            if (level.ordinal() == 0 || level == Level.OFF) {
                error(line(entry, "below"), "'below: " + text + "' leaves nothing to match -- use TRACE through "
                        + "ERROR, or FATAL");
                return null;
            }
            return Level.values()[level.ordinal() - 1];
        }

        private SampleFullPolicy sampleFull(MapNode entry) {
            Node node = entry.entries().get("sampleFull");
            if (node == null) {
                return SampleFullPolicy.defaults();
            }
            if (!(node instanceof ScalarNode scalar)) {
                error(node.line(), "sampleFull must be a duration such as 5m, or false");
                return SampleFullPolicy.defaults();
            }
            if (!scalar.quoted() && scalar.value().equals("false")) {
                return SampleFullPolicy.disabled();
            }
            Duration duration = duration(scalar);
            return duration == null ? SampleFullPolicy.defaults() : SampleFullPolicy.every(duration);
        }

        private Duration duration(ScalarNode scalar) {
            Matcher m = DURATION.matcher(scalar.value());
            if (!m.matches()) {
                error(scalar.line(), "'" + scalar.value() + "' is not a duration -- expected <n>s, <n>m, <n>h or "
                        + "<n>d, e.g. 5m, or false");
                return null;
            }
            long value;
            try {
                value = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                error(scalar.line(), "duration '" + scalar.value() + "' is out of range");
                return null;
            }
            if (value == 0) {
                error(scalar.line(), "a duration must be greater than zero");
                return null;
            }
            try {
                return switch (m.group(2)) {
                    case "s" -> Duration.ofSeconds(value);
                    case "m" -> Duration.ofMinutes(value);
                    case "h" -> Duration.ofHours(value);
                    default -> Duration.ofDays(value);
                };
            } catch (ArithmeticException e) {
                error(scalar.line(), "duration '" + scalar.value() + "' is out of range");
                return null;
            }
        }

        private int frames(MapNode entry) {
            String text = optionalText(entry, "frames");
            if (text == null) {
                return 0;
            }
            try {
                int frames = Integer.parseInt(text);
                if (frames < 0) {
                    error(line(entry, "frames"), "frames must be 0 or more");
                    return 0;
                }
                return frames;
            } catch (NumberFormatException e) {
                error(line(entry, "frames"), "frames must be a whole number, not '" + text + "'");
                return 0;
            }
        }

        /** An exact logger name: no {@code *} (vendor-config-epic.md Decision #7), no whitespace. */
        private String loggerName(MapNode entry, String field) {
            String name = requiredText(entry, field);
            if (name == null) {
                return null;
            }
            if (name.contains("*")) {
                error(line(entry, field), "'" + name + "' is a pattern -- the vendor defaults file takes exact "
                        + "logger names only; name the root of a subtree to cover it");
                return null;
            }
            if (name.chars().anyMatch(Character::isWhitespace)) {
                error(line(entry, field), "logger name '" + name + "' contains whitespace");
                return null;
            }
            return name;
        }

        private Level level(MapNode entry, String field, String text) {
            try {
                return Level.valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                error(line(entry, field), "unknown level '" + text + "' (expected one of "
                        + List.of(Level.values()) + ")");
                return null;
            }
        }

        private boolean bool(MapNode entry, String field, boolean defaultValue) {
            Node node = entry.entries().get(field);
            if (node == null) {
                return defaultValue;
            }
            if (node instanceof ScalarNode scalar && !scalar.quoted()
                    && (scalar.value().equals("true") || scalar.value().equals("false"))) {
                return Boolean.parseBoolean(scalar.value());
            }
            error(node.line(), field + " must be true or false");
            return defaultValue;
        }

        private String requiredText(MapNode entry, String field) {
            Node node = entry.entries().get(field);
            if (node == null) {
                error(entry.line(), "'" + field + "' is missing");
                return null;
            }
            return text(node, field);
        }

        private String optionalText(MapNode entry, String field) {
            Node node = entry.entries().get(field);
            return node == null ? null : text(node, field);
        }

        private String text(Node node, String field) {
            if (!(node instanceof ScalarNode scalar)) {
                error(node.line(), "'" + field + "' must be a single value, not a list or map");
                return null;
            }
            if (scalar.value().isEmpty()) {
                error(node.line(), "'" + field + "' is empty");
                return null;
            }
            return scalar.value();
        }

        private static boolean isGroupName(String name) {
            return name.equals(HandlerRef.ALL_HANDLERS.value()) || name.equals(HandlerRef.DEFAULT_HANDLERS.value());
        }

        private void unknownFields(MapNode entry, Set<String> allowed, String what) {
            for (Map.Entry<String, Integer> key : entry.keyLines().entrySet()) {
                if (!allowed.contains(key.getKey())) {
                    error(key.getValue(), "unknown field '" + key.getKey() + "' in a " + what + " entry");
                }
            }
        }

        private static int line(MapNode entry, String field) {
            Integer line = entry.keyLines().get(field);
            return line != null ? line : entry.line();
        }

        private void error(int line, String message) {
            errors.add("line " + line + ": " + message);
        }
    }
}
