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
import org.logaperture.core.loader.BridgedFramework;
import org.logaperture.core.spi.ContextHandle;

import java.util.ArrayList;
import java.util.List;
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

    @Override
    public boolean ready() {
        checkVersion();
        return !startedContexts().isEmpty();
    }

    @Override
    public List<ContextHandle> open(Runnable onShutdown) {
        this.onShutdown.set(onShutdown);
        String version = version();
        List<LoggerContext> started = startedContexts();
        List<ContextHandle> handles = new ArrayList<>(started.size());
        int ordinal = 0;
        for (LoggerContext context : started) {
            ordinal++;
            String name = ContextNames.of(context.getName(), ordinal);
            handles.add(ContextHandle.of("log4j2:" + name, name, new Log4j2LoggingAdapter(context, version)));
            // Log4j's own shutdown listener (TC5): runs when this context stops, on any thread. It
            // cannot be removed, so it goes inert once this entry has let go.
            context.addShutdownListener(stopped -> {
                if (!closed.get()) {
                    Runnable callback = this.onShutdown.get();
                    if (callback != null) {
                        callback.run();
                    }
                }
            });
        }
        return handles;
    }

    @Override
    public void close() {
        closed.set(true);
        onShutdown.set(null);
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
    }

    /** From the package of the already-loaded context class: no Log4j code runs. */
    private static String version() {
        Package pkg = LoggerContext.class.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
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
        return started;
    }
}
