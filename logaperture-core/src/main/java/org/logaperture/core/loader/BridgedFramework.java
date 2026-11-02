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

import java.util.List;

/**
 * What an adapter's entry class implements (doc/specs/class-loader-detector.md, "Scope" 5). The
 * entry class lives in the bridge jar and is defined by a {@link BridgeClassLoader}; this
 * interface is in {@code org.logaperture.core}, which the bridge takes from the system loader, so
 * the detector can hold the entry without ever naming a framework type.
 *
 * <p>The constructor must touch no framework state: {@link BoundInstance} constructs the entry as
 * soon as the framework's marker class is seen, long before the framework has started.
 */
public interface BridgedFramework {

    /**
     * Whether the framework has a <em>started</em> context (spike I-4: a context that is only
     * {@code INITIALIZED} has not read the application's configuration yet). Cheap, side-effect
     * free, called repeatedly. Throwing means the framework cannot be bound.
     */
    boolean ready();

    /**
     * Brings the framework's context(s) under control. Called once, after {@link #ready()} returned
     * {@code true}. Registers the framework's own shutdown listener to call {@code onShutdown} (TC5),
     * which may be called on any thread, at any time after this returns, and at most once matters.
     *
     * @return one handle per context; each {@link ContextHandle#stableKey()} carries the
     *         {@code framework:} prefix (C7)
     */
    List<ContextHandle> open(Runnable onShutdown);

    /**
     * Lets go: removes every listener this entry installed in the framework and ends its threads
     * (TC5). Called at most once, possibly before {@link #open} finished, and also when binding
     * failed or gave up.
     */
    void close();
}
