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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.spi.LoggerContextFactory;
import org.apache.logging.log4j.spi.LoggerContextShutdownAware;
import org.logaperture.core.loader.BridgedFramework;
import org.logaperture.core.spi.ContextHandle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The entry class the class-loader detector binds for a Log4j 2 found outside the system class
 * path (doc/specs/log4j2-adapter.md, "Scope" 1; doc/specs/class-loader-detector.md). Defined only
 * by a {@code BridgeClassLoader}, which takes {@code org.apache.logging.log4j.*} from the loader
 * that owns the application's Log4j.
 *
 * <p>The readiness rule (spike I-1 to I-4): nothing here asks Log4j for anything until the
 * detector has seen the class of a context ({@code LoggerContext}) loaded; and a context is bound only
 * once it is {@code STARTED}, because one that is merely {@code INITIALIZED} has not read the
 * application's configuration yet. The constructor touches no Log4j state.
 */
public final class Log4j2Framework implements BridgedFramework {

    private final AtomicBoolean versionChecked = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Runnable> onShutdown = new AtomicReference<>();
    private final List<Log4j2LoggingAdapter> adapters = new CopyOnWriteArrayList<>();

    @Override
    public boolean ready() {
        checkVersion();
        return !startedContexts().isEmpty();
    }

    @Override
    public List<ContextHandle> open(Runnable onShutdown) {
        this.onShutdown.set(onShutdown);
        // Log4j's own shutdown listener (TC5): runs when a context stops, on any thread. It cannot be
        // removed, so it goes inert once this entry has let go.
        LoggerContextShutdownAware notifyOnShutdown = stopped -> {
            // The stopped context's adapter has nothing left to listen to: let go of it now, not at close().
            adapters.removeIf(adapter -> {
                boolean theStoppedOne = adapter.isFor(stopped);
                if (theStoppedOne) {
                    adapter.detach();
                }
                return theStoppedOne;
            });
            if (!closed.get()) {
                Runnable callback = this.onShutdown.get();
                if (callback != null) {
                    callback.run();
                }
            }
        };
        String version = version();
        List<LoggerContext> started = startedContexts();
        List<String> log4jNames = new ArrayList<>(started.size());
        for (LoggerContext context : started) {
            log4jNames.add(context.getName());
        }
        List<String> names = ContextNames.allOf(log4jNames);
        List<ContextHandle> handles = new ArrayList<>(started.size());
        for (int i = 0; i < started.size(); i++) {
            LoggerContext context = started.get(i);
            // Listener first, then the check: a context that stops from here on is heard, and one
            // that stopped before is not handed out (a handle for a dead context never goes away).
            context.addShutdownListener(notifyOnShutdown);
            if (!context.isStarted()) {
                continue;
            }
            String name = names.get(i);
            Log4j2LoggingAdapter adapter = new Log4j2LoggingAdapter(context, version);
            adapters.add(adapter);
            handles.add(ContextHandle.of("log4j2:" + name, name, adapter));
        }
        if (handles.isEmpty()) {
            throw new IllegalStateException("the Log4j context stopped before it could be bound");
        }
        return handles;
    }

    @Override
    public void close() {
        closed.set(true);
        onShutdown.set(null);
        for (Log4j2LoggingAdapter adapter : adapters) {
            adapter.detach(); // removes its property listener from the context (Scope 7)
        }
        adapters.clear();
    }

    /** Throws, so the detector reports it (C8), if this Log4j is older than 2.20.0 (A10). */
    private void checkVersion() {
        if (!versionChecked.compareAndSet(false, true)) {
            return;
        }
        String version = version();
        if (Log4j2Version.isTooOld(version)) {
            throw new IllegalStateException("Log4j " + version + " is older than "
                    + Log4j2Version.MIN_MAJOR + "." + Log4j2Version.MIN_MINOR + ".0, the oldest LogAperture controls");
        }
        if (version == null && !hasExplicitLevels()) {
            // Shaded or assembled: no version to read. LoggerConfig.getExplicitLevel arrived in 2.19.0, so
            // its absence says "older than the floor" without running any Log4j code.
            throw new IllegalStateException("this Log4j has no LoggerConfig.getExplicitLevel(), so it is older than "
                    + Log4j2Version.MIN_MAJOR + "." + Log4j2Version.MIN_MINOR + ".0, the oldest LogAperture controls"
                    + " (its version could not be read)");
        }
    }

    private static boolean hasExplicitLevels() {
        try {
            org.apache.logging.log4j.core.config.LoggerConfig.class.getMethod("getExplicitLevel");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** From the package of the already-loaded context class: no Log4j code runs. Null if not trustworthy. */
    private static String version() {
        return Log4j2Version.of(LoggerContext.class);
    }

    private static String configLocation(LoggerContext context) {
        Object location = context.getConfigLocation();
        return location == null ? "" : location.toString();
    }

    /**
     * The contexts that exist and are started. Asks the factory for the contexts it already has; it
     * never creates one (which would lose the application's own configuration, spike I-1).
     */
    private static List<LoggerContext> startedContexts() {
        LoggerContextFactory factory = LogManager.getFactory();
        if (!(factory instanceof Log4jContextFactory log4j)) {
            return List.of();
        }
        List<LoggerContext> started = new ArrayList<>();
        for (LoggerContext context : log4j.getSelector().getLoggerContexts()) {
            if (context.isStarted()) {
                started.add(context);
            }
        }
        // The selector keeps its contexts in a hash map, in no stable order, and the names below are
        // numbered by position and persisted. Config location first (the same file on every run), then
        // name. A context's default name is a class-loader identity hash, so two contexts in one copy
        // that share a config location and have no web path cannot be told apart stably (spec, A7).
        started.sort(Comparator.comparing(Log4j2Framework::configLocation)
                .thenComparing((LoggerContext context) -> String.valueOf(context.getName())));
        return started;
    }
}
