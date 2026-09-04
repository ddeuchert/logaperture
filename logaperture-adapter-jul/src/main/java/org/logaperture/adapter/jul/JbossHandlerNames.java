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
package org.logaperture.adapter.jul;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Best-effort resolution of a live {@link Handler}'s WildFly-configured name
 * ({@code CONSOLE}, {@code FILE}, …) — doc/specs/handler-floor-control.md
 * "Adapter SPI", Open decision #1.
 *
 * <p><b>Why reflection, not a compile dependency.</b> {@link
 * JulLoggingAdapter}'s whole design point is zero compile-time reference to
 * {@code org.jboss.logmanager} (see its class doc) so a plain {@code
 * -javaagent} attach works with no bootclasspath surgery. A handler's
 * configured name is not on {@code java.util.logging.Handler} at all — the
 * JDK base class has no such property, and neither does JBoss LogManager's
 * own {@code ExtHandler}/{@code ConsoleHandler}. It lives one layer up, in
 * {@code org.jboss.logmanager.configuration.ContextConfiguration}, attached
 * to a {@code LogContext}'s root {@code Logger} — reachable only by
 * reflecting into a class this module never references directly.
 *
 * <p><b>Unverified against real WildFly.</b> {@code ContextConfiguration} is
 * JBoss LogManager 3.x's own declarative-configuration mechanism; whether
 * WildFly's logging subsystem populates it for handlers it creates from
 * {@code standalone.xml} (as opposed to managing them entirely through its
 * own model, bypassing this class) has not been confirmed against a running
 * server — that's for {@code WildFlyContainerIT} to settle. Every failure
 * mode here — the class missing, the method missing, the attachment {@code
 * null}, the map not containing this handler — degrades to {@link
 * Optional#empty()}, and {@link HandlerRefs#resolve} falls back to the
 * identity token in that case, so this is safe to ship either way.
 */
final class JbossHandlerNames {

    private static final String CONTEXT_CONFIGURATION_CLASS = "org.jboss.logmanager.configuration.ContextConfiguration";
    private static final String JBOSS_LOGGER_CLASS = "org.jboss.logmanager.Logger";
    private static final String ATTACHMENT_KEY_CLASS = "org.jboss.logmanager.Logger$AttachmentKey";

    /** Per-root-logger cache of the resolved name-to-handler map, rebuilt lazily; {@code null} means "unavailable". */
    private static final Map<Logger, Map<String, Handler>> CACHE = new ConcurrentHashMap<>();

    private static volatile boolean unavailable; // sticky: one failed attempt means don't keep trying via reflection

    private JbossHandlerNames() {
    }

    /**
     * The configured name for {@code handler}, if JBoss LogManager's {@code
     * ContextConfiguration} is attached to {@code root} and names it —
     * empty for plain JUL, for a JBoss LogManager that never populated the
     * attachment, or for a handler {@code standalone.xml} didn't configure
     * (e.g. one an application added programmatically).
     */
    static Optional<String> nameOf(Logger root, Handler handler) {
        if (unavailable) {
            return Optional.empty();
        }
        Map<String, Handler> byName = CACHE.computeIfAbsent(root, JbossHandlerNames::loadHandlerMap);
        if (byName.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, Handler> entry : byName.entrySet()) {
            if (entry.getValue() == handler) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Drops the cached map for {@code root} -- call after a reconfiguration that may have replaced handlers. */
    static void invalidate(Logger root) {
        CACHE.remove(root);
    }

    private static Map<String, Handler> loadHandlerMap(Logger root) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            Class<?> loggerClass = Class.forName(JBOSS_LOGGER_CLASS);
            if (!loggerClass.isInstance(root)) {
                return Map.of(); // plain JUL, not JBoss LogManager -- nothing to resolve, ever
            }
            Class<?> ccClass = Class.forName(CONTEXT_CONFIGURATION_CLASS);
            Class<?> keyClass = Class.forName(ATTACHMENT_KEY_CLASS);

            Object key = lookup.findStaticGetter(ccClass, "CONTEXT_CONFIGURATION_KEY", keyClass).invoke();
            MethodHandle getAttachment = lookup.findVirtual(
                    loggerClass, "getAttachment", MethodType.methodType(Object.class, keyClass));
            Object contextConfiguration = getAttachment.invoke(root, key);
            if (contextConfiguration == null) {
                return Map.of(); // JBoss LogManager, but nothing ever attached a ContextConfiguration
            }

            MethodHandle getHandlers = lookup.findVirtual(ccClass, "getHandlers", MethodType.methodType(Map.class));
            Map<String, Supplier<Handler>> resources = (Map<String, Supplier<Handler>>) getHandlers.invoke(contextConfiguration);

            Map<String, Handler> resolved = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Supplier<Handler>> entry : resources.entrySet()) {
                Handler live = entry.getValue().get(); // ConfigurationResource<Handler> IS a Supplier<Handler>
                if (live != null) {
                    resolved.put(entry.getKey(), live);
                }
            }
            return Map.copyOf(resolved);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Any failure here means "can't resolve names this way, ever" --
            // sticky, so a broken reflective path doesn't retry per handler.
            unavailable = true;
            return Map.of();
        } catch (Throwable e) {
            // A shape we didn't anticipate (e.g. a future JBoss LogManager
            // that changes this API) -- degrade the same way rather than
            // taking the adapter down with it.
            unavailable = true;
            return Map.of();
        }
    }
}
