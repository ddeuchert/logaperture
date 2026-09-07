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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;

/**
 * Resolves WildFly's configured handler names ({@code CONSOLE}, {@code FILE},
 * a dedicated {@code SIF} or {@code API-REQUESTS} handler …) from the running
 * server itself — doc/specs/handler-floor-control.md "WildFly handler name
 * resolution" (issue #14).
 *
 * <p><b>In-VM only.</b> The agent already runs inside the WildFly JVM. It
 * obtains the server's own {@code ModelController} from the MSC {@code
 * ServiceContainer} and runs a local, in-process {@code ModelControllerClient}
 * ({@code createClient} — no socket, no {@code $local} handshake, no
 * management credentials, no {@code wildfly-controller-client} on the compile
 * path). Every reflective handle is loaded through a WildFly module's own
 * class loader (via {@code org.jboss.modules}, the one WildFly API on the
 * system class path) and invoked through public interfaces, never the
 * module-private {@code *Impl} classes — the classloader-and-access
 * discipline the retired {@code JbossHandlerNames} attempt got wrong
 * (doc/specs/handler-floor-control.md "Adapter SPI").
 *
 * <p>Names come from {@code read-children-resources} under {@code
 * /subsystem=logging} for each handler resource type; each name is then bound
 * to one of the live {@link Handler} instances the adapter asked about — a
 * {@code console-handler} to the sole console instance, a file-type handler to
 * the sole file instance, or by matching the model's configured file name
 * against {@code FileHandler.getFile()} when there is more than one.
 *
 * <p><b>Best-effort.</b> Every failure path — the model not reachable yet, an
 * unexpected server version, a handler the model doesn't describe — returns
 * whatever subset resolved (possibly nothing). The adapter keeps every
 * unresolved handler on its {@code <class>@<idhash>} identity token, and
 * {@code ALL_HANDLERS} control is unaffected. This class never throws.
 */
final class WildFlyHandlerNameResolver implements HandlerNameResolver {

    private static final String MODULES_CLASS = "org.jboss.modules.Module";
    /** Modules that transitively expose MSC + the AS controller + client API; broadest first. */
    private static final List<String> CANDIDATE_MODULES =
            List.of("org.jboss.as.server", "org.jboss.as.controller", "org.jboss.as.controller-client");
    /** {@code CurrentServiceContainer}-style holders, WildFly's own first (it keeps that one current). */
    private static final List<String> CURRENT_CONTAINER_CLASSES = List.of(
            "org.jboss.as.server.CurrentServiceContainer",
            "org.jboss.msc.service.CurrentServiceContainer");
    private static final String MODEL_CONTROLLER_SERVICE = "jboss.as.server-controller";
    /** The logging subsystem's handler resource types (WildFly Core, stable across 18–2x). */
    private static final List<String> HANDLER_RESOURCE_TYPES = List.of(
            "console-handler", "file-handler", "periodic-rotating-file-handler",
            "size-rotating-file-handler", "periodic-size-rotating-file-handler",
            "syslog-handler", "custom-handler");

    private static final boolean DEBUG = Boolean.getBoolean("logaperture.wildfly.handlerNames.debug");

    @Override
    public Map<Handler, String> resolve(List<Handler> handlers) {
        try {
            return resolveInternal(handlers);
        } catch (Throwable failure) {
            dbg("resolveInternal threw: " + failure);
            Diagnostics.debug("LogAperture: WildFly handler name resolution failed this pass (" + failure + ")");
            return Map.of();
        }
    }

    private Map<Handler, String> resolveInternal(List<Handler> handlers) throws Exception {
        if (handlers.isEmpty()) {
            return Map.of();
        }
        ClassLoader loader = firstLoadableModuleLoader();
        dbg("moduleLoader=" + loader);
        if (loader == null) {
            return Map.of();
        }
        Object serviceContainer = currentServiceContainer(loader);
        dbg("serviceContainer=" + serviceContainer);
        if (serviceContainer == null) {
            return Map.of();
        }
        Object modelController = modelController(loader, serviceContainer);
        dbg("modelController=" + modelController);
        if (modelController == null) {
            return Map.of();
        }

        // Resolve every method through its public declaring interface, not the
        // module-private *Impl the instance actually is.
        Class<?> modelControllerIface = Class.forName("org.jboss.as.controller.ModelController", false, loader);
        Class<?> clientIface = Class.forName("org.jboss.as.controller.client.ModelControllerClient", false, loader);
        Object client = modelControllerIface.getMethod("createClient", java.util.concurrent.Executor.class)
                .invoke(modelController, (java.util.concurrent.Executor) Runnable::run);
        try {
            Map<String, String> nameToType = readLoggingHandlerNames(loader, clientIface, client);
            dbg("model handler names=" + nameToType);
            Map<String, String> fileNameByHandlerName = readFileNames(loader, clientIface, client, nameToType);
            Map<Handler, String> bound = bind(handlers, nameToType, fileNameByHandlerName);
            dbg("bound " + bound.size() + " of " + handlers.size());
            if (!bound.isEmpty()) {
                Diagnostics.debug("LogAperture: resolved " + bound.size() + " of " + handlers.size()
                        + " WildFly handler name(s) from /subsystem=logging");
            }
            return bound;
        } finally {
            closeQuietly(clientIface, client);
        }
    }

    // --- model reads --------------------------------------------------------------------------------

    /** {@code read-children-names} per handler resource type; returns name -> resource type. */
    private static Map<String, String> readLoggingHandlerNames(ClassLoader loader, Class<?> clientIface, Object client)
            throws Exception {
        Class<?> modelNode = Class.forName("org.jboss.dmr.ModelNode", false, loader);
        Method mnGet = modelNode.getMethod("get", String.class);
        Method mnGetPath = modelNode.getMethod("get", String[].class);
        Method mnSetString = modelNode.getMethod("set", String.class);
        Method mnAdd2 = modelNode.getMethod("add", String.class, String.class);
        Method mnAsString = modelNode.getMethod("asString");
        Method mnAsList = modelNode.getMethod("asList");
        Method mnHasDefined = modelNode.getMethod("hasDefined", String.class);
        Method execute = clientIface.getMethod("execute", modelNode);

        Map<String, String> out = new LinkedHashMap<>();
        for (String type : HANDLER_RESOURCE_TYPES) {
            Object op = modelNode.getConstructor().newInstance();
            mnSetString.invoke(mnGet.invoke(op, "operation"), "read-children-names");
            mnSetString.invoke(mnGet.invoke(op, "child-type"), type);
            mnAdd2.invoke(mnGet.invoke(op, "address"), "subsystem", "logging");

            Object result = execute.invoke(client, op);
            if (!(boolean) mnHasDefined.invoke(result, "result")) {
                continue;
            }
            Object names = mnGetPath.invoke(result, (Object) new String[] {"result"});
            for (Object nameNode : (List<?>) mnAsList.invoke(names)) {
                out.put((String) mnAsString.invoke(nameNode), type);
            }
        }
        return out;
    }

    /** For file-type handlers, the configured file's leaf name (for disambiguating &gt;1 file handler). */
    private static Map<String, String> readFileNames(
            ClassLoader loader, Class<?> clientIface, Object client, Map<String, String> nameToType) throws Exception {
        Class<?> modelNode = Class.forName("org.jboss.dmr.ModelNode", false, loader);
        Method mnGet = modelNode.getMethod("get", String.class);
        Method mnGetPath = modelNode.getMethod("get", String[].class);
        Method mnSetString = modelNode.getMethod("set", String.class);
        Method mnAdd2 = modelNode.getMethod("add", String.class, String.class);
        Method mnAsString = modelNode.getMethod("asString");
        Method mnHasDefined = modelNode.getMethod("hasDefined", String.class);
        Method execute = clientIface.getMethod("execute", modelNode);

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : nameToType.entrySet()) {
            if (e.getValue().equals("console-handler") || e.getValue().equals("syslog-handler")
                    || e.getValue().equals("custom-handler")) {
                continue;
            }
            Object op = modelNode.getConstructor().newInstance();
            mnSetString.invoke(mnGet.invoke(op, "operation"), "read-attribute");
            mnSetString.invoke(mnGet.invoke(op, "name"), "file");
            Object address = mnGet.invoke(op, "address");
            mnAdd2.invoke(address, "subsystem", "logging");
            mnAdd2.invoke(address, e.getValue(), e.getKey());

            Object result = execute.invoke(client, op);
            if (!(boolean) mnHasDefined.invoke(result, "result")) {
                continue;
            }
            Object file = mnGetPath.invoke(result, (Object) new String[] {"result"});
            if ((boolean) mnHasDefined.invoke(file, "path")) {
                String path = (String) mnAsString.invoke(mnGet.invoke(file, "path"));
                out.put(e.getKey(), leaf(path));
            }
        }
        return out;
    }

    // --- binding ------------------------------------------------------------------------------------

    /** Package-visible for {@code WildFlyHandlerNameResolverTest}. */
    static Map<Handler, String> bind(
            List<Handler> handlers, Map<String, String> nameToType, Map<String, String> fileNameByHandlerName) {
        List<Handler> consoles = new ArrayList<>();
        List<Handler> files = new ArrayList<>();
        for (Handler h : handlers) {
            (isConsole(h) ? consoles : files).add(h);
        }
        List<String> consoleNames = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (Map.Entry<String, String> e : nameToType.entrySet()) {
            (e.getValue().equals("console-handler") ? consoleNames : fileNames).add(e.getKey());
        }

        Map<Handler, String> out = new IdentityHashMap<>();

        // The 1<->1 shortcut is only safe when the *model* also defines exactly
        // one handler of that kind. A model can define more handlers than are
        // currently attached (a `file-handler=AUDIT` declared but not assigned
        // to any logger), and blindly taking `candidates.size() == 1` would
        // bind the live handler to the wrong name.
        if (consoleNames.size() == 1 && consoles.size() == 1) {
            out.put(consoles.get(0), consoleNames.get(0));
        }
        if (fileNames.size() == 1 && files.size() == 1) {
            out.put(files.get(0), fileNames.get(0));
        } else {
            for (String fileName : fileNames) {
                String wantLeaf = fileNameByHandlerName.get(fileName);
                if (wantLeaf == null) {
                    continue; // no configured path to match on -- leave it on its token
                }
                for (Handler h : files) {
                    if (!out.containsKey(h) && wantLeaf.equalsIgnoreCase(fileNameOf(h))) {
                        out.put(h, fileName);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean isConsole(Handler handler) {
        return handler.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("console");
    }

    /** {@code org.jboss.logmanager.handlers.FileHandler#getFile()} is public — the retired attempt confirmed it resolves. */
    private static String fileNameOf(Handler handler) {
        try {
            Object file = handler.getClass().getMethod("getFile").invoke(handler);
            return file == null ? null : leaf(String.valueOf(file));
        } catch (ReflectiveOperationException | RuntimeException notAFileHandler) {
            return null;
        }
    }

    private static String leaf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // --- WildFly-internal handles -----------------------------------------------------------------

    private static Object modelController(ClassLoader loader, Object serviceContainer) {
        try {
            Class<?> serviceName = Class.forName("org.jboss.msc.service.ServiceName", false, loader);
            Class<?> registry = Class.forName("org.jboss.msc.service.ServiceRegistry", false, loader);
            Class<?> controller = Class.forName("org.jboss.msc.service.ServiceController", false, loader);
            Object name = serviceName.getMethod("parse", String.class).invoke(null, MODEL_CONTROLLER_SERVICE);
            Object svc = registry.getMethod("getService", serviceName).invoke(serviceContainer, name);
            if (svc == null) {
                dbg("no service " + MODEL_CONTROLLER_SERVICE);
                return null;
            }
            try {
                return controller.getMethod("getValue").invoke(svc);
            } catch (ReflectiveOperationException | RuntimeException notUp) {
                dbg(MODEL_CONTROLLER_SERVICE + " not UP yet: " + notUp);
                return null;
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
            dbg("modelController lookup failed: " + absent);
            return null;
        }
    }

    /** The running server's MSC {@code ServiceContainer}, via whichever {@code CurrentServiceContainer} holder answers. */
    private static Object currentServiceContainer(ClassLoader loader) {
        for (String holder : CURRENT_CONTAINER_CLASSES) {
            try {
                Class<?> current = Class.forName(holder, false, loader);
                Object container = current.getMethod("getServiceContainer").invoke(null);
                dbg(holder + ".getServiceContainer() -> " + container);
                if (container != null) {
                    return container;
                }
            } catch (ReflectiveOperationException | RuntimeException tryNext) {
                dbg(holder + " unusable: " + tryNext);
            }
        }
        return null;
    }

    private static ClassLoader firstLoadableModuleLoader() {
        Object bootLoader;
        try {
            Class<?> moduleClass = Class.forName(MODULES_CLASS, false, ClassLoader.getSystemClassLoader());
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

    private static void closeQuietly(Class<?> clientIface, Object client) {
        try {
            clientIface.getMethod("close").invoke(client);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // best effort -- ModelControllerClient extends Closeable
        }
    }

    private static void dbg(String message) {
        if (DEBUG) {
            System.err.println("[#14 resolver] " + message);
        }
    }
}
