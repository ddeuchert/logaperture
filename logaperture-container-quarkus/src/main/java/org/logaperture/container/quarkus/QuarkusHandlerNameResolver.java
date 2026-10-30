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

import org.logaperture.adapter.jul.HandlerNameResolver;
import org.logaperture.adapter.jul.TypeHandlerNameResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Names Quarkus handlers by the names written in {@code application.properties}
 * ({@code quarkus.log.handler.file."AUDIT"}), doc/specs/quarkus-handler-names.md.
 *
 * <p>Quarkus keeps no name on a running handler, so the name comes from the configuration: each
 * category's {@code handlers} list, and {@code quarkus.log.handlers} for the root, are in the order the
 * handlers are attached, so the <em>i</em>th name goes with the <em>i</em>th handler (#1). A pair must
 * also agree on type and on the attributes the configuration states ({@link HandlerAttributes}, #10).
 * A list is matched where Quarkus put it (#9): a category's first <em>n</em> handlers, and the last
 * <em>n</em> of the root's delayed handler as they were when names were first resolved, so a handler
 * added later shifts nothing. A list that does not match is skipped, its handlers keep their type
 * names (#11), and {@link #problems()} says why, for {@code doctor} (#12).
 * Every handler not named this way goes to {@link TypeHandlerNameResolver} ({@code console},
 * {@code file}, ...), avoiding the configured names (#3).
 *
 * <p>Nothing is named before Quarkus has activated its delayed handler (the handlers are not attached
 * yet), and nothing is named from the configuration when it cannot be read ({@link #configUnreadable()}).
 */
final class QuarkusHandlerNameResolver implements HandlerNameResolver {

    private final Supplier<Optional<Map<String, String>>> properties;
    private final BooleanSupplier ready;
    private final Function<String, Logger> loggers;
    private final TypeHandlerNameResolver typeNames = new TypeHandlerNameResolver();
    private volatile boolean unreadable;
    /** The parsed configuration once it has been read; it does not change while the JVM runs. */
    private volatile QuarkusLogConfig parsed;
    /** The root delayed handler's children when names were first resolved; taken again once one is gone (#9). */
    private volatile List<Handler> rootSnapshot;
    private volatile List<ListProblem> problems = List.of();

    /**
     * A configured {@code handlers} list that was skipped, or matched with less certainty than usual.
     *
     * @param list   the property that holds it: {@code quarkus.log.handlers}, or a category's
     * @param kind   what happened to it
     * @param detail why, in words for {@code doctor}
     */
    record ListProblem(String list, Kind kind, String detail) {
        enum Kind {
            /** Not matched: its handlers keep their type names. */
            SKIPPED,
            /** Matched, but by position and type alone while the logger has other handlers. */
            TYPE_ONLY
        }
    }

    /**
     * @param properties the {@code quarkus.log.*} properties, empty when they cannot be read
     * @param ready      whether Quarkus has attached its handlers
     * @param loggers    an existing logger by name ({@code ""} is the root), or {@code null}
     */
    QuarkusHandlerNameResolver(Supplier<Optional<Map<String, String>>> properties, BooleanSupplier ready,
            Function<String, Logger> loggers) {
        this.properties = properties;
        this.ready = ready;
        this.loggers = loggers;
    }

    /** Whether the configuration could not be read the last time names were resolved. */
    boolean configUnreadable() {
        return unreadable;
    }

    /** The lists that were skipped or matched by type alone the last time names were resolved. */
    List<ListProblem> problems() {
        return problems;
    }

    @Override
    public Map<Handler, String> resolve(List<Handler> handlers) {
        if (!ready.getAsBoolean()) {
            return Map.of(); // Quarkus has not attached its handlers yet: the caller asks again
        }
        Map<Handler, String> configured = new IdentityHashMap<>();
        QuarkusLogConfig config = parsed;
        if (config == null) {
            Optional<Map<String, String>> read = readQuietly();
            config = read.map(QuarkusLogConfig::parse).orElse(null);
            parsed = config;
        }
        unreadable = config == null;
        if (config != null) {
            List<ListProblem> found = new ArrayList<>();
            configured.putAll(configuredNames(config, found));
            problems = List.copyOf(found);
        } else {
            problems = List.of();
        }
        Set<String> reserved = new HashSet<>(configured.values());
        List<Handler> others = new ArrayList<>();
        for (Handler handler : handlers) {
            if (!configured.containsKey(handler)) {
                others.add(handler);
            }
        }
        Map<Handler, String> names = new HashMap<>(typeNames.resolve(others, reserved));
        for (Handler handler : handlers) {
            if (configured.containsKey(handler)) {
                names.put(handler, configured.get(handler));
            }
        }
        return names;
    }

    private Optional<Map<String, String>> readQuietly() {
        try {
            return properties.get();
        } catch (RuntimeException unexpected) {
            return Optional.empty();
        }
    }

    /** Handler to configured name for every list that lines up; a name that lands on two handlers is dropped. */
    private Map<Handler, String> configuredNames(QuarkusLogConfig config, List<ListProblem> problems) {
        Map<String, List<Handler>> byName = new HashMap<>();
        for (Map.Entry<String, List<String>> category : config.categories().entrySet()) {
            Logger logger = loggers.apply(category.getKey());
            List<String> names = config.builtAmong(category.getValue());
            if (logger == null || names.isEmpty()) {
                continue; // no logger: Quarkus attached nothing there
            }
            List<Handler> attached = List.of(logger.getHandlers());
            List<Handler> window = attached.subList(0, Math.min(names.size(), attached.size()));
            String list = "quarkus.log.category.\"" + category.getKey() + "\".handlers";
            align(config, list, names, window, attached.size() > window.size(), byName, problems);
        }
        List<String> rootNames = config.builtAmong(config.root());
        if (!rootNames.isEmpty()) {
            List<Handler> inside = rootChildren();
            List<Handler> snapshot = snapshotOf(inside);
            List<Handler> window = snapshot.subList(Math.max(0, snapshot.size() - rootNames.size()), snapshot.size());
            align(config, "quarkus.log.handlers", rootNames, window, addedAfter(inside, window), byName, problems);
        }
        Map<Handler, String> result = new IdentityHashMap<>();
        for (Map.Entry<String, List<Handler>> entry : byName.entrySet()) {
            List<Handler> distinct = distinct(entry.getValue());
            if (distinct.size() == 1) {
                result.put(distinct.get(0), entry.getKey());
            }
        }
        return result;
    }

    /**
     * The root's children as they were when names were first resolved (#9): handlers added since go
     * after them and must not shift the names. Taken again when one of them is gone, as after a
     * dev-mode reload, which replaces them all.
     */
    private List<Handler> snapshotOf(List<Handler> current) {
        List<Handler> snapshot = rootSnapshot;
        if (snapshot == null || !containsAll(current, snapshot)) {
            snapshot = List.copyOf(current);
            rootSnapshot = snapshot;
        }
        return snapshot;
    }

    private static boolean containsAll(List<Handler> current, List<Handler> wanted) {
        Set<Handler> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(current);
        return present.containsAll(wanted);
    }

    /** Whether {@code current} holds a handler after the last one of {@code window}. */
    private static boolean addedAfter(List<Handler> current, List<Handler> window) {
        if (window.isEmpty()) {
            return false;
        }
        Handler last = window.get(window.size() - 1);
        for (int i = current.size() - 1; i >= 0; i--) {
            if (current.get(i) == last) {
                return i < current.size() - 1;
            }
        }
        return false;
    }

    /**
     * Pairs {@code names} with {@code handlers} when every pair agrees on type and on its configured
     * attributes; otherwise pairs nothing (#11). Records why a list was skipped, or that it was matched
     * by type alone next to other handlers ({@code othersPresent}).
     */
    private static void align(QuarkusLogConfig config, String list, List<String> names, List<Handler> handlers,
            boolean othersPresent, Map<String, List<Handler>> byName, List<ListProblem> problems) {
        if (handlers.size() < names.size()) {
            problems.add(new ListProblem(list, ListProblem.Kind.SKIPPED, "it names " + names.size() + " handlers ("
                    + String.join(", ", typesOf(config, names)) + ") but the logger has " + handlers.size()
                    + (handlers.isEmpty() ? "" : " (" + String.join(", ", typesOf(handlers)) + ")")));
            return;
        }
        List<String> typeOnly = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String expected = config.typeOf(name);
            String actual = TypeHandlerNameResolver.typeOf(handlers.get(i));
            if (!expected.equals(actual)) {
                problems.add(new ListProblem(list, ListProblem.Kind.SKIPPED, name + " is configured as a " + expected
                        + " handler, but handler " + (i + 1) + " on the logger is "
                        + (actual == null ? "of another kind" : "a " + actual + " handler")));
                return;
            }
            HandlerAttributes.Result checked = HandlerAttributes.check(name, expected, config.attributesOf(name),
                    handlers.get(i));
            if (checked.mismatch() != null) {
                problems.add(new ListProblem(list, ListProblem.Kind.SKIPPED, checked.mismatch()));
                return;
            }
            if (!checked.confirmed()) {
                typeOnly.add(name);
            }
        }
        for (int i = 0; i < names.size(); i++) {
            byName.computeIfAbsent(names.get(i), n -> new ArrayList<>()).add(handlers.get(i));
        }
        if (othersPresent && !typeOnly.isEmpty()) {
            problems.add(new ListProblem(list, ListProblem.Kind.TYPE_ONLY, String.join(", ", typeOnly)
                    + (typeOnly.size() == 1 ? " was" : " were") + " matched by position and type only, and the logger"
                    + " has other handlers too"));
        }
    }

    private static List<String> typesOf(QuarkusLogConfig config, List<String> names) {
        List<String> types = new ArrayList<>();
        for (String name : names) {
            types.add(config.typeOf(name));
        }
        return types;
    }

    private static List<String> typesOf(List<Handler> handlers) {
        List<String> types = new ArrayList<>();
        for (Handler handler : handlers) {
            types.add(describe(TypeHandlerNameResolver.typeOf(handler)));
        }
        return types;
    }

    private static String describe(String type) {
        return type == null ? "another kind of handler" : type;
    }

    private static List<Handler> distinct(List<Handler> handlers) {
        Set<Handler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Handler> out = new ArrayList<>();
        for (Handler handler : handlers) {
            if (seen.add(handler)) {
                out.add(handler);
            }
        }
        return out;
    }

    /** The handlers inside the root logger's {@code QuarkusDelayedHandler}, in attachment order. */
    private List<Handler> rootChildren() {
        Logger root = loggers.apply("");
        if (root == null) {
            return List.of();
        }
        for (Handler handler : root.getHandlers()) {
            if (!DelayedHandlerWatch.DELAYED_HANDLER_CLASS.equals(handler.getClass().getName())) {
                continue;
            }
            try {
                Method getHandlers = handler.getClass().getMethod("getHandlers");
                Object inside = getHandlers.invoke(handler);
                return inside instanceof Handler[] children ? List.of(children) : List.of();
            } catch (ReflectiveOperationException | RuntimeException notThere) {
                return List.of();
            }
        }
        return List.of();
    }
}
