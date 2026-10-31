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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the {@code quarkus.log.*} properties from the application's MicroProfile Config
 * (doc/specs/quarkus-handler-names.md #5), the same sources Quarkus read: profiles, environment
 * variables, ConfigMaps. By reflection on {@code ConfigProvider}, found through {@code loader}, so
 * there is no compile-time dependency; in the agent {@code loader} is the one that defines
 * {@code ConfigProvider} (it is not the agent's), in the extension the application's.
 */
final class QuarkusConfigReader {

    private static final String CONFIG_PROVIDER = "org.eclipse.microprofile.config.ConfigProvider";
    private static final String CONFIG = "org.eclipse.microprofile.config.Config";
    private static final String PREFIX = "quarkus.log.";

    private QuarkusConfigReader() {
    }

    /** The {@code quarkus.log.*} properties as strings, or empty when the configuration cannot be reached. */
    static Optional<Map<String, String>> read(ClassLoader loader) {
        if (loader == null) {
            return Optional.empty();
        }
        try {
            Class<?> provider = Class.forName(CONFIG_PROVIDER, false, loader);
            Class<?> configType = Class.forName(CONFIG, false, loader);
            Object config = provider.getMethod("getConfig", ClassLoader.class).invoke(null, loader);
            Method names = configType.getMethod("getPropertyNames");
            Method value = configType.getMethod("getOptionalValue", String.class, Class.class);
            Map<String, String> out = new HashMap<>();
            for (Object name : (Iterable<?>) names.invoke(config)) {
                String key = String.valueOf(name);
                if (key.startsWith(PREFIX)) {
                    readOne(config, value, key, out);
                }
            }
            for (String handler : handlersWithoutAFlag(out.keySet())) {
                readOne(config, value, handler + ".enabled", out);
            }
            return Optional.of(out);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreachable) {
            return Optional.empty();
        }
    }

    /**
     * The named handlers among {@code keys} that no spelling of their name sets {@code enable} or
     * {@code enabled} for, one key prefix each. For these, {@link #read} asks for {@code enabled}:
     * it is not among the property names unless someone set it, but the configuration answers it
     * with Quarkus's own default for that handler type, so whether Quarkus built the handler follows
     * the running version (doc/specs/quarkus-handler-names.md #14). A handler with a flag set is left
     * alone: an environment variable spells the name in lower case, and a default looked up under that
     * spelling must not compete with the value set under the other.
     */
    static Set<String> handlersWithoutAFlag(Set<String> keys) {
        Map<String, String> prefixByHandler = new LinkedHashMap<>();
        Set<String> flagged = new HashSet<>();
        for (String key : keys) {
            QuarkusLogConfig.HandlerKey handler = QuarkusLogConfig.handlerKey(key);
            if (handler == null) {
                continue;
            }
            prefixByHandler.putIfAbsent(handler.identity(), handler.prefix());
            if ("enable".equals(handler.property()) || "enabled".equals(handler.property())) {
                flagged.add(handler.identity());
            }
        }
        prefixByHandler.keySet().removeAll(flagged);
        return new LinkedHashSet<>(prefixByHandler.values());
    }

    /** One property; a value that cannot be read (an unexpandable {@code ${...}}) costs that key, not the rest. */
    private static void readOne(Object config, Method value, String key, Map<String, String> out)
            throws ReflectiveOperationException {
        try {
            Optional<?> found = (Optional<?>) value.invoke(config, key, String.class);
            found.ifPresent(v -> out.put(key, String.valueOf(v)));
        } catch (InvocationTargetException badValue) {
            if (badValue.getCause() instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * The class loader that defines {@code ConfigProvider} among {@code loaded} classes (the agent
     * has {@code Instrumentation.getAllLoadedClasses()}), or {@code null} before it is loaded.
     */
    static ClassLoader definingLoader(Class<?>[] loaded) {
        for (Class<?> type : loaded) {
            if (CONFIG_PROVIDER.equals(type.getName()) && type.getClassLoader() != null) {
                return type.getClassLoader();
            }
        }
        return null;
    }
}
