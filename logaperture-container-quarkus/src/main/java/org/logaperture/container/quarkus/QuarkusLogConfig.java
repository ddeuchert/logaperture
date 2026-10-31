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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of Quarkus's logging configuration that name handlers (doc/specs/quarkus-handler-names.md):
 * which names are configured under which handler type, and which names each category (and the root)
 * lists in its {@code handlers}, in order. Pure parsing of {@code quarkus.log.*} properties, so it
 * is testable without Quarkus.
 */
final class QuarkusLogConfig {

    private static final Pattern HANDLER_KEY = Pattern.compile(
            "quarkus\\.log\\.handler\\.(console|file|syslog|socket)\\.(?:\"(.+?)\"|([^.\"]+))\\.(.+)");
    private static final Pattern CATEGORY_HANDLERS_KEY = Pattern.compile(
            "quarkus\\.log\\.category\\.(?:\"(.+)\"|([^.\"]+))\\.handlers");
    private static final String ROOT_HANDLERS_KEY = "quarkus.log.handlers";
    private static final Set<String> CHECKED_ATTRIBUTES = Set.of("path", "endpoint", "format");

    /**
     * Configured name to its handler type; a name configured under two types is left out ({@link #ambiguous}).
     * Keyed by {@link #fold}: an environment variable cannot carry case, so MicroProfile Config reports
     * {@code QUARKUS_LOG_HANDLER_FILE__AUDIT__ENABLE} as the handler {@code "audit"} while the
     * {@code handlers} list says {@code AUDIT}.
     */
    private final Map<String, String> typeByName = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();
    /** The two flags Quarkus reads, kept apart: {@code enable} wins over {@code enabled} (#15). */
    private final Map<String, Boolean> enableByName = new HashMap<>();
    private final Map<String, Boolean> enabledByName = new HashMap<>();
    /** Folded name to the attributes a pair is checked on (#10): {@code path}, {@code endpoint}, {@code format}. */
    private final Map<String, Map<String, String>> attributesByName = new HashMap<>();
    private final Map<String, List<String>> categoryLists = new LinkedHashMap<>();
    private List<String> rootList = List.of();

    private QuarkusLogConfig() {
    }

    static QuarkusLogConfig parse(Map<String, String> properties) {
        QuarkusLogConfig config = new QuarkusLogConfig();
        for (Map.Entry<String, String> property : properties.entrySet()) {
            String key = property.getKey();
            Matcher handler = HANDLER_KEY.matcher(key);
            if (handler.matches()) {
                config.addHandlerProperty(handler.group(1), handler.group(2) != null ? handler.group(2) : handler.group(3),
                        handler.group(4), property.getValue());
                continue;
            }
            Matcher category = CATEGORY_HANDLERS_KEY.matcher(key);
            if (category.matches()) {
                config.categoryLists.put(category.group(1) != null ? category.group(1) : category.group(2),
                        names(property.getValue()));
            } else if (ROOT_HANDLERS_KEY.equals(key)) {
                config.rootList = names(property.getValue());
            }
        }
        return config;
    }

    private void addHandlerProperty(String type, String name, String property, String value) {
        name = fold(name);
        String known = typeByName.putIfAbsent(name, type);
        if (known != null && !known.equals(type)) {
            ambiguous.add(name);
        }
        if ("enable".equals(property)) {
            enableByName.put(name, Boolean.parseBoolean(value.trim()));
        } else if ("enabled".equals(property)) {
            enabledByName.put(name, Boolean.parseBoolean(value.trim()));
        } else if (CHECKED_ATTRIBUTES.contains(property) && !value.isBlank()) {
            // Quarkus uses a format exactly as written, spaces included; a path or endpoint it trims.
            String kept = "format".equals(property) ? value : value.trim();
            attributesByName.computeIfAbsent(name, n -> new HashMap<>()).put(property, kept);
        }
    }

    /**
     * The attributes the configuration states for {@code listed}, by property ({@code path},
     * {@code endpoint}, {@code format}); empty when it states none. Only what is written is here:
     * Quarkus's defaults differ between versions, so they are not checked (#10).
     */
    Map<String, String> attributesOf(String listed) {
        return attributesByName.getOrDefault(fold(listed), Map.of());
    }

    private static List<String> names(String value) {
        List<String> names = new ArrayList<>();
        for (String name : value.split(",")) {
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /**
     * Whether Quarkus builds a handler for {@code name}, by Quarkus's own rule:
     * {@code enable.orElse(enabled)} (#15). {@code enabled} is normally in the properties even when
     * nobody set it, as the configuration reports Quarkus's default for it (#14, see
     * {@link QuarkusConfigReader}); when it is not, a named console handler is on and the other types
     * are off, the defaults of the tested versions (Quarkus logs "linked to a category but not
     * configured" for a named file handler that is off).
     */
    boolean isBuilt(String listed) {
        String name = fold(listed);
        String type = typeByName.get(name);
        if (type == null || ambiguous.contains(name)) {
            return false;
        }
        Boolean enable = enableByName.get(name);
        if (enable != null) {
            return enable;
        }
        Boolean enabled = enabledByName.get(name);
        return enabled != null ? enabled : "console".equals(type);
    }

    /** The type a name was configured under, or {@code null} when it is unknown or ambiguous. */
    String typeOf(String listed) {
        String name = fold(listed);
        return ambiguous.contains(name) ? null : typeByName.get(name);
    }

    private static String fold(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /** Category to the names it lists, in configured order. */
    Map<String, List<String>> categories() {
        return categoryLists;
    }

    /** The names {@code quarkus.log.handlers} lists for the root logger, in configured order. */
    List<String> root() {
        return rootList;
    }

    /** The built names among {@code listed}, in order: the handlers Quarkus actually attaches. */
    List<String> builtAmong(List<String> listed) {
        List<String> built = new ArrayList<>();
        for (String name : listed) {
            if (isBuilt(name)) {
                built.add(name);
            }
        }
        return built;
    }
}
