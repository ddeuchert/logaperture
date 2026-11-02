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

import java.util.Objects;
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

    private final ContainerIntegration container;
    private final AggregateLevelControl aggregate;
    private final Consumer<AggregateLevelControl> onContextReady;

    /**
     * @param onContextReady called after each successful install; pass the agent's
     *                       {@code onFirstContextReady} wrapped in {@link #once}, so a JVM whose only
     *                       context is a bundled framework still gets a control surface, and one that
     *                       already has the container's own does not publish it twice
     */
    public ContainerContextSink(ContainerIntegration container, AggregateLevelControl aggregate,
                                Consumer<AggregateLevelControl> onContextReady) {
        this.container = Objects.requireNonNull(container, "container");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.onContextReady = Objects.requireNonNull(onContextReady, "onContextReady");
    }

    /** {@code callback}, but only the first time: for sharing one "first context ready" between sources. */
    public static Consumer<AggregateLevelControl> once(Consumer<AggregateLevelControl> callback) {
        AtomicBoolean called = new AtomicBoolean();
        return operations -> {
            if (called.compareAndSet(false, true)) {
                callback.accept(operations);
            }
        };
    }

    @Override
    public void add(ContextHandle context) {
        try {
            container.adoptContext(context);
            onContextReady.accept(aggregate);
        } catch (Throwable t) {
            Diagnostics.error("LogAperture could not install level control for " + context.stableKey(), t);
        }
    }

    @Override
    public void remove(String stableKey) {
        try {
            container.releaseContext(stableKey);
        } catch (Throwable t) {
            Diagnostics.error("LogAperture could not remove " + stableKey, t);
        }
    }
}
