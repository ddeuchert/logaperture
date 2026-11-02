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

import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.spi.ContainerIntegration;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Starts the class-loader detector for the agent (doc/specs/class-loader-detector.md, "Scope" 1, 6;
 * C6): the scanner, the binder that makes a {@link BoundInstance} for what it finds, and the sink
 * that hands the contexts to whichever {@link ContainerIntegration} the agent activated. It runs
 * whatever the integration is, so a framework bundled in a deployment is found on WildFly and Quarkus
 * as well as on a plain JVM.
 */
public final class FrameworkDetection {

    /** Where an adapter's class files sit inside the agent jar: one folder per framework (C2). */
    public static final String BRIDGE_FOLDER = "META-INF/logaperture/";

    private FrameworkDetection() {
    }

    /**
     * Starts scanning for {@code watches}. With none, starts nothing -- no thread.
     *
     * @param onContextReady the agent's "first context ready" callback, already wrapped in
     *                       {@link ContainerContextSink#once} and shared with the integration's own
     * @param agentLoader    the loader that holds the agent jar (and so the adapter class files)
     */
    public static LoaderScanner start(Instrumentation inst, ContainerIntegration container,
                                      AggregateLevelControl aggregate, Consumer<AggregateLevelControl> onContextReady,
                                      List<FrameworkWatch> watches, ClassLoader agentLoader) {
        Function<FrameworkWatch, BridgeSource> sources =
                watch -> BridgeSource.underPrefix(agentLoader, BRIDGE_FOLDER + watch.framework());
        return start(inst::getAllLoadedClasses, container, aggregate, onContextReady, watches, sources,
                ClassLoader.getSystemClassLoader(), LoaderScanner.Cadence.standard(), BoundInstance.Timing.standard());
    }

    static LoaderScanner start(Supplier<Class<?>[]> loadedClasses, ContainerIntegration container,
                               AggregateLevelControl aggregate, Consumer<AggregateLevelControl> onContextReady,
                               List<FrameworkWatch> watches, Function<FrameworkWatch, BridgeSource> sources,
                               ClassLoader system, LoaderScanner.Cadence cadence, BoundInstance.Timing timing) {
        ContextSink sink = new ContainerContextSink(container, aggregate, onContextReady);
        FrameworkBinder binder = new FrameworkBinder(sources, system, sink, timing);
        LoaderScanner scanner = new LoaderScanner(loadedClasses, watches, binder, cadence);
        scanner.start();
        return scanner;
    }
}
