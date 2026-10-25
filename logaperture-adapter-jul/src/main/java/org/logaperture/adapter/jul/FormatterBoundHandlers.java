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
package org.logaperture.adapter.jul;

import java.util.Set;
import java.util.logging.Handler;

/**
 * Handlers whose own code requires its formatter to be of one type, so wrapping it breaks every
 * publish (doc/specs/quarkus-extension.md #5). LogAperture leaves their formatter alone: {@code trim}
 * rules and {@code top} then don't reach them, and {@code doctor} says so.
 */
final class FormatterBoundHandlers {

    /**
     * Quarkus's Dev UI log stream handler (dev mode only) casts its formatter to its own
     * {@code JsonFormatter}; a wrapped formatter makes it fail with {@code ClassCastException}.
     */
    static final Set<String> CLASS_NAMES = Set.of("io.quarkus.devui.runtime.logstream.MutinyLogHandler");

    private FormatterBoundHandlers() {
    }

    /** {@code true} if {@code handler} is, or extends, one of {@link #CLASS_NAMES}. */
    static boolean isBound(Handler handler) {
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            if (CLASS_NAMES.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }
}
