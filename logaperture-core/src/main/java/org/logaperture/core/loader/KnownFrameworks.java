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
 * manager is only a front for one of them, C1). Empty until a framework adapter registers its watch:
 * Log4j 2 is the first (doc/specs/log4j2-adapter.md).
 */
public final class KnownFrameworks {

    private KnownFrameworks() {
    }

    public static List<FrameworkWatch> all() {
        return List.of();
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
