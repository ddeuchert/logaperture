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
 * handlers are attached, so the <em>i</em>th name goes with the <em>i</em>th handler (#1). A list whose
 * handlers do not match its names in count or type is skipped, and its handlers keep their type names.
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

    @Override
    public Map<Handler, String> resolve(List<Handler> handlers) {
        if (!ready.getAsBoolean()) {
            return Map.of(); // Quarkus has not attached its handlers yet: the caller asks again
        }
        Map<Handler, String> configured = new IdentityHashMap<>();
        Optional<Map<String, String>> read = readQuietly();
        unreadable = read.isEmpty();
        read.ifPresent(values -> configured.putAll(configuredNames(QuarkusLogConfig.parse(values))));
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
    private Map<Handler, String> configuredNames(QuarkusLogConfig config) {
        Map<String, List<Handler>> byName = new HashMap<>();
        for (Map.Entry<String, List<String>> category : config.categories().entrySet()) {
            Logger logger = loggers.apply(category.getKey());
            if (logger != null) {
                List<Handler> attached = List.of(logger.getHandlers());
                align(config, config.builtAmong(category.getValue()), attached, byName);
            }
        }
        List<String> rootNames = config.builtAmong(config.root());
        if (!rootNames.isEmpty()) {
            List<Handler> inside = rootChildren();
            if (inside.size() >= rootNames.size()) {
                align(config, rootNames, inside.subList(inside.size() - rootNames.size(), inside.size()), byName);
            }
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

    /** Pairs {@code names} with {@code handlers} when they match in count and type; otherwise pairs nothing. */
    private static void align(QuarkusLogConfig config, List<String> names, List<Handler> handlers,
            Map<String, List<Handler>> byName) {
        if (names.isEmpty() || names.size() != handlers.size()) {
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            if (!config.typeOf(names.get(i)).equals(TypeHandlerNameResolver.typeOf(handlers.get(i)))) {
                return;
            }
        }
        for (int i = 0; i < names.size(); i++) {
            byName.computeIfAbsent(names.get(i), n -> new ArrayList<>()).add(handlers.get(i));
        }
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
