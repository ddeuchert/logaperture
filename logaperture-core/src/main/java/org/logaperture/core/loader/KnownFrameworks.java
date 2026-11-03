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

/**
 * The frameworks the detector looks for (doc/specs/class-loader-detector.md). One list, read by both
 * the agent (to scan for them) and the {@code none} integration (to leave a JUL tree alone when its
 * manager is only a front for one of them, C1). Log4j 2 is the first (doc/specs/log4j2-adapter.md).
 */
public final class KnownFrameworks {

    /**
     * Log4j 2 (doc/specs/log4j2-adapter.md, "Scope" 1): the marker is a context's class, the owner
     * supplies every {@code org.apache.logging.log4j.} package, and the entry lives in the bridge
     * jar. {@code log4j-jul}'s manager is a front for it (C1).
     */
    public static final FrameworkWatch LOG4J2 = new FrameworkWatch("log4j2",
            "org.apache.logging.log4j.core.LoggerContext",
            List.of("org.apache.logging.log4j."),
            "org.logaperture.adapter.log4j2.Log4j2Framework",
            "org.apache.logging.log4j.jul.");

    private KnownFrameworks() {
    }

    public static List<FrameworkWatch> all() {
        return List.of(LOG4J2);
    }

    /**
     * Whether {@code julManagerClass} (the value of {@code java.util.logging.manager}, or
     * {@code null}) is the JUL manager of a framework in {@code watches}. Reads no JUL state: the
     * caller passes the system property.
     */
    public static boolean julManagerFrontsABoundFramework(String julManagerClass, List<FrameworkWatch> watches) {
        if (julManagerClass == null) {
            return false;
        }
        for (FrameworkWatch watch : watches) {
            String prefix = watch.julManagerPrefix();
            if (prefix != null && julManagerClass.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
