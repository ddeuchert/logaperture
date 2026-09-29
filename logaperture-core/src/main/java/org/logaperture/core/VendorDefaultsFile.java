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
            Set.of("schemaVersion", "loggers", "handlers", "handlerGroupStateIds", "defaultHandlers",
                    "defaultHandlersStateId", "rules", "namespace", "recipes");
    private static final Set<String> RECIPE_FIELDS =
            Set.of("name", "summary", "description", "loggers", "handlers", "rules");
    /** doc/specs/recipes.md #1: reverse-domain recommended, not enforced; up to 64 characters. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final Pattern RECIPE_NAME = Pattern.compile("[a-z0-9-]{1,40}");
    /** Namespaces no recipe file may declare (#1) -- {@code org.logaperture} covers its subdomains too. */
    private static final Set<String> RESERVED_NAMESPACES = Set.of("vendor", "logaperture", "org.logaperture");
    private static final Set<String> LOGGER_FIELDS = Set.of("name", "level", "reason", "stateId");
    private static final Set<String> HANDLER_FIELDS = Set.of("name", "level", "reason", "stateId");
    private static final Set<String> COMMON_RULE_FIELDS = Set.of("id", "action", "logger", "below",
            "messageContains", "messageContainsIgnoreCase", "throwable", "throwableMessageContains", "anyCause",
            "reason", "stateId");
    private static final Set<String> DROP_FIELDS = Set.of("sampleFull");
    private static final Set<String> TRIM_FIELDS = Set.of("frames", "collapseCauses");
    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhd])");
    /** A state id as {@code FileStateStore} assigns it (doc/specs/export-round-trip.md): a UUID, lower-case hex. */
    private static final Pattern STATE_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

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
        Validation v = new Validation(new ArrayList<>(), protectedCategories, false);
        List<Recipe> recipes = v.run(root, new RecipeSource(RecipeSource.Kind.VENDOR_DEFAULTS,
                RecipeSource.VENDOR_DEFAULTS_LABEL, path.toString()));
        if (!v.errors.isEmpty()) {
            return VendorDefaults.rejected(path, v.errors);
        }
        return VendorDefaults.loaded(path, writable, v.loggers, v.handlers, v.defaultHandlers,
                v.defaultHandlersStateId, v.handlerGroupStateIds, v.rules, recipes);
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
                field(out, "stateId", logger.stateId());
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
                field(out, "stateId", handler.stateId());
            }
        }
        if (!export.handlerGroupStateIds().isEmpty()) {
            out.append("handlerGroupStateIds:\n");
            for (String stateId : export.handlerGroupStateIds()) {
                out.append("  - ").append(value(stateId)).append('\n');
            }
        }
        if (export.defaultHandlers() != null) {
            out.append("defaultHandlers:\n");
            for (HandlerRef ref : export.defaultHandlers()) {
                out.append("  - ").append(value(ref.value())).append('\n');
            }
            if (export.defaultHandlersStateId() != null) {
                out.append("defaultHandlersStateId: ").append(value(export.defaultHandlersStateId())).append('\n');
            }
        }
        if (!export.rules().isEmpty()) {
            out.append("rules:\n");
            for (VendorDefaults.RuleDefault rule : export.rules()) {
                String comment = export.ruleComments().get(rule.id());
                if (comment != null) {
                    out.append("  # ").append(comment).append('\n');
                }
                writeRule(out, "  ", rule, rule.id().substring(VendorDefaults.RULE_ID_PREFIX.length()));
            }
        }
        if (!export.recipes().isEmpty()) {
            writeRecipes(out, export.recipes());
        }
        return out.toString();
    }

    /** The {@code namespace:} and {@code recipes:} sections -- doc/specs/recipes.md "The recipe file". */
    private static void writeRecipes(StringBuilder out, List<Recipe> recipes) {
        out.append("namespace: ").append(value(recipes.get(0).namespace())).append('\n');
        out.append("recipes:\n");
        for (Recipe recipe : recipes) {
            out.append("  - name: ").append(recipe.name()).append('\n');
            field(out, "    ", "summary", recipe.summary());
            if (recipe.description() != null) {
                text(out, "    ", "description", recipe.description());
            }
            if (!recipe.loggers().isEmpty()) {
                out.append("    loggers:\n");
                for (VendorDefaults.LoggerDefault logger : recipe.loggers()) {
                    out.append("      - name: ").append(value(logger.name())).append('\n');
                    out.append("        level: ").append(logger.level().name()).append('\n');
                    field(out, "        ", "reason", logger.reason());
                }
            }
            if (!recipe.handlers().isEmpty()) {
                out.append("    handlers:\n");
                for (VendorDefaults.HandlerDefault handler : recipe.handlers()) {
                    out.append("      - name: ").append(value(handler.ref().value())).append('\n');
                    out.append("        level: ")
                            .append(handler.mode() == HandlerLevelMode.AUTO ? "AUTO" : handler.level().name())
                            .append('\n');
                    field(out, "        ", "reason", handler.reason());
                }
            }
            if (!recipe.rules().isEmpty()) {
                out.append("    rules:\n");
                for (VendorDefaults.RuleDefault rule : recipe.rules()) {
                    writeRule(out, "      ", rule, rule.id());
                }
            }
        }
    }

    /**
     * Multi-line text as {@code |} block text (doc/specs/recipes.md #2), so it reads back
     * unchanged; a value {@code |} can't hold as written -- one line, or a first line starting with
     * a space, which would be read as indentation -- is written as a quoted value instead.
     */
    private static void text(StringBuilder out, String indent, String key, String text) {
        String firstLine = text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        if (!text.contains("\n") || firstLine.startsWith(" ") || text.contains("\r") || text.contains("\t")) {
            field(out, indent, key, text);
            return;
        }
        out.append(indent).append(key).append(": |\n");
        for (String line : text.split("\n", -1)) {
            out.append(line.isEmpty() ? "" : indent + "  " + line).append('\n');
        }
    }

    /** One rule entry at {@code indent} ({@code "  "} for the file's own rules), with {@code id} as written. */
    private static void writeRule(StringBuilder out, String indent, VendorDefaults.RuleDefault rule, String id) {
        String fields = indent + "  ";
        CompiledMatchers m = rule.matchers();
        out.append(indent).append("- id: ").append(id).append('\n');
        out.append(fields).append("action: ").append(rule.action()).append('\n');
        out.append(fields).append("logger: ").append(value(rule.loggerName())).append('\n');
        field(out, fields, m.messageIgnoreCase() ? "messageContainsIgnoreCase" : "messageContains", m.messageContains());
        field(out, fields, "throwable", m.throwableType());
        field(out, fields, "throwableMessageContains", m.throwableMessageContains());
        if (m.anyCause()) {
            out.append(fields).append("anyCause: true\n");
        }
        if (m.levelAtMost() != null) {
            // No 'below' line for an unbounded rule (possible only through JMX, never logctl): the
            // file can't say "no bound", and reloads it with the ERROR keep-floor -- the safer side.
            out.append(fields).append("below: ").append(RuleExpression.belowFor(m.levelAtMost())).append('\n');
        }
        if ("drop".equals(rule.action())) {
            out.append(fields).append("sampleFull: ").append(rule.sampleFull().enabled()
                    ? RuleExpression.duration(rule.sampleFull().every().toMillis()) : "false").append('\n');
        } else if ("trim".equals(rule.action())) {
            out.append(fields).append("frames: ").append(rule.frames()).append('\n');
            if (rule.collapseCauses()) {
                out.append(fields).append("collapseCauses: true\n");
            }
        }
        field(out, fields, "reason", rule.reason());
        field(out, fields, "stateId", rule.stateId());
    }

    private static void field(StringBuilder out, String key, String value) {
        field(out, "    ", key, value);
    }

    private static void field(StringBuilder out, String indent, String key, String value) {
        if (value != null) {
            out.append(indent).append(key).append(": ").append(value(value)).append('\n');
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

    /**
     * One pass over the parsed tree, collecting every error rather than stopping at the first.
     * Also validates recipe files ({@link RecipeFile}) and each recipe's entries: in {@code
     * recipeEntries} mode an entry takes no {@code stateId}, and a rule's {@code id} is optional
     * and not {@code vendor:}-prefixed (doc/specs/recipes.md "The recipe file").
     */
    static final class Validation {
        final List<String> errors;
        final List<VendorDefaults.LoggerDefault> loggers = new ArrayList<>();
        final List<VendorDefaults.HandlerDefault> handlers = new ArrayList<>();
        List<HandlerRef> defaultHandlers;
        String defaultHandlersStateId;
        final List<String> handlerGroupStateIds = new ArrayList<>();
        final List<VendorDefaults.RuleDefault> rules = new ArrayList<>();
        private final Set<String> seenLoggerNames = new HashSet<>();
        private final Set<String> seenHandlerNames = new HashSet<>();
        private final Set<String> seenRuleIds = new HashSet<>();
        private final ProtectedCategories protectedCategories;
        private final boolean recipeEntries;

        /**
         * @param errors        where errors are collected -- shared with the validation of the file
         *                      that holds these entries, so a recipe's entry errors reject that file
         * @param recipeEntries whether the entries are a recipe's (see the class doc)
         */
        Validation(List<String> errors, ProtectedCategories protectedCategories, boolean recipeEntries) {
            this.errors = errors;
            this.protectedCategories = protectedCategories;
            this.recipeEntries = recipeEntries;
        }

        /** Validates a whole vendor defaults file; its recipes, if it has any, are returned. */
        List<Recipe> run(MapNode root, RecipeSource source) {
            for (String key : root.entries().keySet()) {
                if (!TOP_LEVEL_KEYS.contains(key)) {
                    error(root.keyLines().get(key), "unknown key '" + key + "' (expected one of "
                            + "schemaVersion, loggers, handlers, handlerGroupStateIds, defaultHandlers, "
                            + "defaultHandlersStateId, rules, namespace, recipes)");
                }
            }
            schemaVersion(root);
            forEachEntry(root, "loggers", this::logger);
            forEachEntry(root, "handlers", this::handler);
            defaultHandlers(root.entries().get("defaultHandlers"));
            Node membersStateId = root.entries().get("defaultHandlersStateId");
            if (membersStateId != null) {
                if (!root.entries().containsKey("defaultHandlers")) {
                    error(membersStateId.line(), "defaultHandlersStateId needs a defaultHandlers list beside it");
                }
                defaultHandlersStateId = stateId(membersStateId);
            }
            handlerGroupStateIds(root.entries().get("handlerGroupStateIds"));
            forEachEntry(root, "rules", this::rule);
            String namespace = namespace(root, root.entries().containsKey("recipes"),
                    "a vendor defaults file with 'recipes:' needs a 'namespace:' for their ids, e.g. "
                            + "'namespace: com.acme'");
            return root.entries().containsKey("recipes") ? recipes(root, namespace, source) : List.of();
        }

        void schemaVersion(MapNode root) {
            Node version = root.entries().get("schemaVersion");
            if (version == null) {
                error(root.line(), "schemaVersion is missing -- the first line should be 'schemaVersion: "
                        + SCHEMA_VERSION + "'");
            } else if (!(version instanceof ScalarNode scalar) || !scalar.value().equals(
                    String.valueOf(SCHEMA_VERSION))) {
                error(version.line(), "schemaVersion must be " + SCHEMA_VERSION);
            }
        }

        /**
         * The top-level {@code namespace:} -- doc/specs/recipes.md #1. {@code null} when absent
         * (an error if {@code required}) or invalid.
         */
        String namespace(MapNode root, boolean required, String missingMessage) {
            Node node = root.entries().get("namespace");
            if (node == null) {
                if (required) {
                    error(root.line(), missingMessage);
                }
                return null;
            }
            String namespace = text(node, "namespace");
            if (namespace == null) {
                return null;
            }
            if (!NAMESPACE.matcher(namespace).matches()) {
                error(node.line(), "namespace '" + namespace + "' must be up to 64 characters of a-z, 0-9, '.' and "
                        + "'-' -- a reverse domain you control is recommended, e.g. io.undertow");
                return null;
            }
            if (RESERVED_NAMESPACES.contains(namespace) || namespace.startsWith("org.logaperture.")) {
                error(node.line(), "namespace '" + namespace + "' is reserved -- use a reverse domain you control, "
                        + "e.g. com.acme");
                return null;
            }
            return namespace;
        }

        /**
         * The {@code recipes:} list under {@code root} -- doc/specs/recipes.md "The recipe file".
         * Each recipe's entries are validated in recipe mode into this validation's errors. With
         * any error, the result is not used (the caller rejects the whole file), so partial
         * results here are harmless.
         */
        List<Recipe> recipes(MapNode root, String namespace, RecipeSource source) {
            List<Recipe> recipes = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Consumer<MapNode> perRecipe = entry -> {
                Recipe recipe = recipe(entry, namespace, source, names);
                if (recipe != null) {
                    recipes.add(recipe);
                }
            };
            Node node = root.entries().get("recipes");
            if (node instanceof ListNode list && list.items().isEmpty()
                    || node instanceof ScalarNode scalar && scalar.value().isEmpty()) {
                error(node.line(), "'recipes' is empty -- list at least one recipe");
                return recipes;
            }
            forEachEntry(root, "recipes", perRecipe);
            return recipes;
        }

        private Recipe recipe(MapNode entry, String namespace, RecipeSource source, Set<String> names) {
            int before = errors.size();
            unknownFields(entry, RECIPE_FIELDS, "recipe");
            String name = requiredText(entry, "name");
            if (name != null && !RECIPE_NAME.matcher(name).matches()) {
                error(line(entry, "name"), "recipe name '" + name + "' must be 1-40 characters of a-z, 0-9 and '-'");
            } else if (name != null && !names.add(name)) {
                error(line(entry, "name"), "recipe '" + name + "' is listed twice");
            }
            String summary = requiredText(entry, "summary");
            if (summary != null && summary.contains("\n")) {
                error(line(entry, "summary"), "summary must be one line -- put the rest in 'description'");
            }
            String description = optionalText(entry, "description");
            if (source.kind() == RecipeSource.Kind.LIBRARY) {
                for (String field : List.of("handlers", "rules")) {
                    if (entry.entries().containsKey(field)) {
                        error(line(entry, field), "a library's recipe may only set logger levels -- handler "
                                + "levels and rules belong in a vendor defaults file or recipes folder recipe");
                    }
                }
            }
            Validation entries = new Validation(errors, ProtectedCategories.none(), true);
            entries.forEachEntry(entry, "loggers", entries::logger);
            entries.forEachEntry(entry, "handlers", entries::handler);
            entries.forEachEntry(entry, "rules", entries::rule);
            if (entries.loggers.isEmpty() && entries.handlers.isEmpty() && entries.rules.isEmpty()
                    && errors.size() == before) {
                error(entry.line(), "recipe '" + name + "' changes nothing -- give it loggers"
                        + (source.kind() == RecipeSource.Kind.LIBRARY ? "" : ", handlers or rules"));
            }
            if (errors.size() != before || namespace == null) {
                return null;
            }
            return new Recipe(namespace, name, summary, description, entries.loggers, entries.handlers,
                    entries.rules, source);
        }

        private void handlerGroupStateIds(Node node) {
            if (node == null) {
                return;
            }
            if (!(node instanceof ListNode list)) {
                error(node.line(), "'handlerGroupStateIds' must be a list of ids written by logctl export "
                        + "vendor-defaults");
                return;
            }
            for (Node item : list.items()) {
                String stateId = stateId(item);
                if (stateId != null) {
                    handlerGroupStateIds.add(stateId);
                }
            }
        }

        /** An optional {@code stateId} (doc/specs/export-round-trip.md S7): a UUID, or an error. */
        private String stateId(MapNode entry) {
            Node node = entry.entries().get("stateId");
            return node == null ? null : stateId(node);
        }

        private String stateId(Node node) {
            String text = text(node, "stateId");
            if (text != null && !STATE_ID.matcher(text).matches()) {
                error(node.line(), "stateId '" + text + "' is not an id written by logctl export vendor-defaults "
                        + "-- remove the line, or restore it from the exported file");
                return null;
            }
            return text;
        }

        void forEachEntry(MapNode root, String key, Consumer<MapNode> perEntry) {
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
            unknownFields(entry, entryFields(LOGGER_FIELDS), "logger");
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
            String stateId = stateId(entry);
            if (name != null && !seenLoggerNames.add(name)) {
                error(line(entry, "name"), "logger '" + name + "' is listed twice");
            }
            if (errors.size() == before) {
                loggers.add(new VendorDefaults.LoggerDefault(name, level, reason, stateId));
            }
        }

        private void handler(MapNode entry) {
            int before = errors.size();
            unknownFields(entry, entryFields(HANDLER_FIELDS), "handler");
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
            String stateId = stateId(entry);
            if (name != null && isGroupName(name)) {
                error(line(entry, "name"), name + " is a group, not a handler -- name each handler instead");
            } else if (name != null && !seenHandlerNames.add(name)) {
                error(line(entry, "name"), "handler '" + name + "' is listed twice");
            }
            if (errors.size() == before) {
                handlers.add(new VendorDefaults.HandlerDefault(new HandlerRef(name), level, mode, reason, stateId));
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
            Set<String> allowed = new HashSet<>(entryFields(COMMON_RULE_FIELDS));
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

            String name = recipeEntries ? optionalText(entry, "id") : requiredText(entry, "id");
            String id = null;
            if (name != null) {
                if (!RULE_ID.matcher(name).matches()) {
                    error(line(entry, "id"), "id '" + name + "' must be 1-40 characters of a-z, 0-9 and '-'");
                } else {
                    id = recipeEntries ? name : VendorDefaults.RULE_ID_PREFIX + name;
                    if (!seenRuleIds.add(id)) {
                        error(line(entry, "id"), "rule id '" + name + "' is used twice");
                    }
                }
            } else if (recipeEntries) {
                id = "rule-" + (rules.size() + 1); // only a label: applying it gives an ordinary rN id
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
            String stateId = stateId(entry);

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
                        collapseCauses, stateId));
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

        String text(Node node, String field) {
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

        /** {@code fields}, less {@code stateId} for a recipe's entries -- a recipe is never exported. */
        private Set<String> entryFields(Set<String> fields) {
            if (!recipeEntries) {
                return fields;
            }
            Set<String> without = new HashSet<>(fields);
            without.remove("stateId");
            return without;
        }

        void unknownFields(MapNode entry, Set<String> allowed, String what) {
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

        void error(int line, String message) {
            errors.add("line " + line + ": " + message);
        }
    }
}
