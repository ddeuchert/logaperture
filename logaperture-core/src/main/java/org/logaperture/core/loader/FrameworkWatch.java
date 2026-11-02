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

import java.util.List;
import java.util.Objects;

/**
 * One logging framework the {@link LoaderScanner} looks for in loaders other than the system class
 * loader (doc/specs/class-loader-detector.md, "Scope" 2). A watch only <em>names</em> things: it
 * holds no framework type and loads nothing, so the scanner can carry it before the framework has
 * started (the readiness constraint, "Scope" 7).
 *
 * @param framework     short stable id, e.g. {@code "log4j2"}; prefixes the context key (C7) so two
 *                      frameworks cannot collide
 * @param markerClass   fully qualified name of the class whose presence among the loaded classes
 *                      means the framework is in that loader
 * @param ownerPackages package prefixes the owning loader supplies to the bridge loader (C3), each
 *                      ending in a dot, e.g. {@code "org.apache.logging.log4j."}
 * @param entryClass    the adapter entry class inside the bridge jar (C2)
 * @param julManagerPrefix class-name prefix of the {@code java.util.logging.manager} this framework
 *                      supplies (Log4j: {@code org.apache.logging.log4j.jul.}), or {@code null}; when
 *                      the JVM's manager starts with it, the JUL tree is only a front for this framework
 *                      and is not bound as a context of its own (C1)
 */
public record FrameworkWatch(String framework, String markerClass, List<String> ownerPackages,
                             String entryClass, String julManagerPrefix) {

    public FrameworkWatch(String framework, String markerClass, List<String> ownerPackages, String entryClass) {
        this(framework, markerClass, ownerPackages, entryClass, null);
    }

    public FrameworkWatch {
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(markerClass, "markerClass");
        Objects.requireNonNull(entryClass, "entryClass");
        ownerPackages = List.copyOf(ownerPackages);
        for (String prefix : ownerPackages) {
            if (!prefix.endsWith(".")) {
                throw new IllegalArgumentException("owner package prefix must end in a dot: " + prefix);
            }
        }
    }
}
