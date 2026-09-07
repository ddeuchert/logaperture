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
package org.logaperture.container.wildfly;

import org.logaperture.adapter.jul.HandlerNameResolver;
import org.logaperture.bridge.Diagnostics;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;

/**
 * Resolves WildFly's configured handler names ({@code CONSOLE}, {@code FILE},
 * a dedicated {@code SIF} or {@code API-REQUESTS} handler …) from the running
 * server itself — doc/specs/handler-floor-control.md "WildFly handler name
 * resolution" (issue #14).
 *
 * <p><b>In-VM only.</b> The agent already runs inside the WildFly JVM, so
 * this reaches the server's own MSC {@code ServiceContainer} directly: no
 * socket, no management credentials, no {@code wildfly-controller-client} on
 * the classpath. Every reflective handle is loaded through a WildFly module's
 * own class loader (obtained via {@code org.jboss.modules}, the one WildFly
 * API on the system class path), never this module's — the same
 * classloader-anchored discipline the retired {@code JbossHandlerNames}
 * attempt got wrong (doc/specs/handler-floor-control.md "Adapter SPI").
 *
 * <p><b>Best-effort.</b> Every failure path — MSC not reachable, an
 * unexpected server version, a handler with no service — returns whatever
 * subset resolved (possibly nothing). The adapter keeps every unresolved
 * handler on its {@code <class>@<idhash>} identity token, and {@code
 * ALL_HANDLERS} control is unaffected. This class never throws.
 *
 * <p>Mechanism: walk the {@code ServiceContainer}'s services, keep those
 * whose value is a live {@link Handler} that is one of the instances the
 * adapter asked about, and take the configured name from the service name.
 * The intended hybrid (doc/specs/handler-floor-control.md Decision #1) also
 * cross-checks names against an in-VM {@code ModelController} read of {@code
 * /subsystem=logging}; that half is added if {@code WildFlyContainerIT}
 * shows the service-name walk alone is not enough on real WildFly.
 */
final class WildFlyHandlerNameResolver implements HandlerNameResolver {

    private static final String MODULES_CLASS = "org.jboss.modules.Module";
    /** Modules that transitively expose {@code org.jboss.msc}; first one that loads wins. */
    private static final List<String> CANDIDATE_MODULES = List.of("org.jboss.msc", "org.jboss.as.controller");

    @Override
    public Map<Handler, String> resolve(List<Handler> handlers) {
        try {
            return resolveInternal(handlers);
        } catch (Throwable failure) {
            Diagnostics.debug("LogAperture: WildFly handler name resolution failed this pass (" + failure + ")");
            return Map.of();
        }
    }

    private Map<Handler, String> resolveInternal(List<Handler> handlers) throws Exception {
        if (handlers.isEmpty()) {
            return Map.of();
        }
        ClassLoader mscLoader = firstLoadableModuleLoader();
        if (mscLoader == null) {
            return Map.of();
        }
        Object serviceContainer = currentServiceContainer(mscLoader);
        if (serviceContainer == null) {
            return Map.of();
        }

        Set<Handler> wanted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(handlers);
        Map<Handler, String> resolved = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        List<Object> serviceNames =
                (List<Object>) serviceContainer.getClass().getMethod("getServiceNames").invoke(serviceContainer);
        java.lang.reflect.Method getService =
                serviceContainer.getClass().getMethod("getService", loadClass(mscLoader, "org.jboss.msc.service.ServiceName"));

        for (Object serviceName : serviceNames) {
            String canonical = String.valueOf(serviceName);
            if (!looksLikeLoggingHandlerService(canonical)) {
                continue;
            }
            Object controller = getService.invoke(serviceContainer, serviceName);
            if (controller == null) {
                continue;
            }
            Object value = serviceValueOrNull(controller);
            if (!(value instanceof Handler handler) || !wanted.contains(handler)) {
                continue;
            }
            String name = configuredNameFrom(canonical);
            if (name != null && !name.isEmpty()) {
                resolved.putIfAbsent(handler, name);
            }
        }

        if (!resolved.isEmpty()) {
            Diagnostics.debug("LogAperture: resolved " + resolved.size() + " of " + handlers.size()
                    + " WildFly handler name(s) via MSC");
        }
        return resolved;
    }

    /** {@code ServiceController#getValue()} throws if the service is not UP — that is a skip, not a failure. */
    private static Object serviceValueOrNull(Object serviceController) {
        try {
            return serviceController.getClass().getMethod("getValue").invoke(serviceController);
        } catch (ReflectiveOperationException | RuntimeException notUp) {
            return null;
        }
    }

    /** {@code CurrentServiceContainer.getServiceContainer()} — the running server's MSC container. */
    private static Object currentServiceContainer(ClassLoader loader) {
        try {
            Class<?> current = loadClass(loader, "org.jboss.msc.service.CurrentServiceContainer");
            return current.getMethod("getServiceContainer").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            Diagnostics.debug("LogAperture: no MSC CurrentServiceContainer (" + absent + ")");
            return null;
        }
    }

    private static ClassLoader firstLoadableModuleLoader() {
        Class<?> moduleClass;
        Object bootLoader;
        try {
            moduleClass = Class.forName(MODULES_CLASS, false, ClassLoader.getSystemClassLoader());
            bootLoader = moduleClass.getMethod("getBootModuleLoader").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException noModules) {
            return null; // not a JBoss-Modules server after all
        }
        for (String moduleName : CANDIDATE_MODULES) {
            try {
                Object module = bootLoader.getClass().getMethod("loadModule", String.class).invoke(bootLoader, moduleName);
                return (ClassLoader) module.getClass().getMethod("getClassLoader").invoke(module);
            } catch (ReflectiveOperationException | RuntimeException tryNext) {
                // module not present under this name on this version -- try the next candidate
            }
        }
        return null;
    }

    private static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /**
     * A cheap pre-filter so the common non-logging services are skipped
     * before the (throwing) {@code getValue()} probe. WildFly's logging
     * handler services carry both tokens somewhere in the name across the
     * versions seen so far; a false negative here just means a handler stays
     * on its identity token, never an error.
     */
    private static boolean looksLikeLoggingHandlerService(String canonicalServiceName) {
        String lower = canonicalServiceName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("log") && lower.contains("handler");
    }

    /**
     * The configured name is the service-name segment after a {@code handler}
     * segment, or failing that the last segment. WildFly logging handler
     * service names have taken forms like {@code
     * org.wildfly.logging.handler.CONSOLE} and {@code
     * jboss.logging.handler."CONSOLE"} — both yield {@code CONSOLE} here.
     */
    private static String configuredNameFrom(String canonicalServiceName) {
        String[] segments = canonicalServiceName.split("\\.");
        String candidate = segments.length == 0 ? null : segments[segments.length - 1];
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equalsIgnoreCase("handler")) {
                candidate = segments[i + 1];
                break;
            }
        }
        return candidate == null ? null : candidate.replace("\"", "").trim();
    }
}
