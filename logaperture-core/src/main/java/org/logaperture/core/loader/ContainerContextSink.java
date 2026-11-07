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
package org.logaperture.core.loader;

import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.spi.ContainerIntegration;
import org.logaperture.core.spi.ContextHandle;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The {@link ContextSink} the agent uses: hands each context the detector bound to the
 * {@link ContainerIntegration} it activated, and tells the agent the control surface can come up
 * if no context has done that yet (doc/specs/class-loader-detector.md, C6, "Scope" 6).
 *
 * <p>An install that fails is a {@code Diagnostics} line and no context: it must not take the
 * {@link BoundInstance} that called it down with it.
 */
public final class ContainerContextSink implements ContextSink {

    private static final Consumer<AggregateLevelControl> NO_UNBOUND = operations -> { };

    private final ContainerIntegration container;
    private final AggregateLevelControl aggregate;
    private final Consumer<AggregateLevelControl> onContextReady;
    private final Consumer<AggregateLevelControl> onUnbound;
    /** The handle currently registered under each key, so a stale remove can be told from a live one. */
    private final Map<String, ContextHandle> current = new ConcurrentHashMap<>();

    /**
     * @param onContextReady called after each successful install; pass the agent's
     *                       {@code onFirstContextReady} wrapped in {@link #once}, so a JVM whose only
     *                       context is a bundled framework still gets a control surface, and one that
     *                       already has the container's own does not publish it twice
     */
    public ContainerContextSink(ContainerIntegration container, AggregateLevelControl aggregate,
                                Consumer<AggregateLevelControl> onContextReady) {
        this(container, aggregate, onContextReady, NO_UNBOUND);
    }

    /**
     * @param onUnbound called when the detector found a framework and could not bind it (see
     *                  {@link ContextSink#unbound}): brings the control surface up with no context. Must be
     *                  idempotent and must not do what only a real context justifies (the vendor-defaults audit).
     */
    public ContainerContextSink(ContainerIntegration container, AggregateLevelControl aggregate,
                                Consumer<AggregateLevelControl> onContextReady,
                                Consumer<AggregateLevelControl> onUnbound) {
        this.container = Objects.requireNonNull(container, "container");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.onContextReady = Objects.requireNonNull(onContextReady, "onContextReady");
        this.onUnbound = Objects.requireNonNull(onUnbound, "onUnbound");
    }

    /**
     * {@code callback}, but only the first time it succeeds: for sharing one "first context ready"
     * between sources. A callback that throws is tried again by the next caller, so one failed
     * publication of the control surface does not leave the JVM without it for good.
     */
    public static Consumer<AggregateLevelControl> once(Consumer<AggregateLevelControl> callback) {
        AtomicBoolean called = new AtomicBoolean();
        return operations -> {
            if (called.compareAndSet(false, true)) {
                try {
                    callback.accept(operations);
                } catch (RuntimeException | Error e) {
                    called.set(false);
                    throw e;
                }
            }
        };
    }

    @Override
    public void add(ContextHandle context) {
        try {
            container.adoptContext(context);
        } catch (Throwable t) {
            Diagnostics.error("LogAperture could not install level control for " + context.stableKey(), t);
            return;
        }
        current.put(context.stableKey(), context);
        try {
            onContextReady.accept(aggregate);
        } catch (Throwable t) {
            // the context is installed; it is the control surface that did not come up
            Diagnostics.error("LogAperture installed " + context.stableKey()
                    + " but could not publish its control surface", t);
        }
    }

    @Override
    public void unbound(String framework) {
        try {
            onUnbound.accept(aggregate);
        } catch (Throwable t) {
            Diagnostics.error("LogAperture found " + framework + " but could not publish its control surface", t);
        }
    }

    @Override
    public boolean notBound(String framework, ClassLoader owner) {
        try {
            boolean tookOver = container.frameworkNotBound(framework, owner);
            if (tookOver) {
                BindFailures.noteFallback(framework, owner);
            }
            return tookOver;
        } catch (Throwable t) {
            Diagnostics.warn("LogAperture could not fall back after " + framework + " was not bound", t);
            return false;
        }
    }

    @Override
    public void remove(ContextHandle context) {
        if (!current.remove(context.stableKey(), context)) {
            return; // never added, already removed, or replaced by a newer copy under the same key
        }
        try {
            container.releaseContext(context.stableKey());
        } catch (Throwable t) {
            Diagnostics.error("LogAperture could not remove " + context.stableKey(), t);
        }
    }
}
