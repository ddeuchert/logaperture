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

import java.util.Objects;
import java.util.function.Function;

/**
 * Connects a {@link LoaderScanner} to {@link BoundInstance}: every loader the scanner finds gets
 * an instance, whose adapter classes are read from the source the watch's framework names.
 * Holds no instance: the framework's own shutdown listener keeps a bound one alive, and nothing
 * here may keep a loader reachable.
 */
public final class FrameworkBinder implements LoaderScanner.Listener {

    private final Function<FrameworkWatch, BridgeSource> sources;
    private final ClassLoader system;
    private final ContextSink sink;
    private final BoundInstance.Timing timing;

    public FrameworkBinder(Function<FrameworkWatch, BridgeSource> sources, ClassLoader system, ContextSink sink,
                           BoundInstance.Timing timing) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.system = Objects.requireNonNull(system, "system");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    @Override
    public void found(FrameworkWatch watch, ClassLoader owner) {
        BoundInstance.start(watch, owner, sources.apply(watch), system, sink, timing);
    }
}
