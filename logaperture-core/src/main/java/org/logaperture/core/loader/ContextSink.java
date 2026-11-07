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

import org.logaperture.core.spi.ContextHandle;

/**
 * Where the detector hands each context it has bound, and takes it back (C6). The agent adapts
 * this onto the active {@code ContainerIntegration}'s container ({@code register} and
 * {@code removeContext} of {@code AggregateLevelControl}).
 */
public interface ContextSink {

    /** A framework context is ready to be controlled. */
    void add(ContextHandle context);

    /**
     * The detector found {@code framework} and could not bind it for a reason a user should hear of (an
     * unsupported version, or an adapter that failed: not merely no context after 3 s). The agent's sink brings the
     * control surface up so {@code doctor} can say why (doc/specs/surface-on-bind-failure.md, D1, D2). Default:
     * nothing.
     */
    default void unbound(String framework) {
    }

    /**
     * The detector found {@code framework} in {@code owner} and ended without a context, for any reason: an
     * unsupported version, an adapter that failed, or no started context in time
     * (doc/specs/jul-fallback-on-log4j-bind-failure.md, J3). Called after {@link #unbound} where that applies.
     * Returns whether the container now controls another tree for the same application instead (the
     * {@code java.util.logging} one behind {@code log4j-jul}), which {@code doctor} then says. Default: no.
     */
    default boolean notBound(String framework, ClassLoader owner) {
        return false;
    }

    /**
     * This context has gone. Removing one twice, or one never added, is a no-op, and so is removing
     * one that a later {@link #add} has since replaced under the same key: a redeploy can register
     * the new copy before the old copy's shutdown listener fires, and the old one must not take the
     * live one with it.
     */
    void remove(ContextHandle context);
}
