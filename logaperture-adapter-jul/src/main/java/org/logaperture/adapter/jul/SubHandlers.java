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

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Handler;

/**
 * The handlers nested inside a delegating handler -- JBoss LogManager's
 * {@code ExtHandler.getHandlers()}, which an {@code AsyncHandler} hands
 * each record to on its own thread (doc/specs/trim-rule.md "Evaluation",
 * issue #80). Plain JUL has no such nesting. Reached by reflection, like
 * {@link JulHandlerDiagnostics}' own JBoss-only accessors, since this
 * adapter has no compile-time JBoss LogManager dependency ({@link
 * JulLoggingAdapter}'s class doc).
 */
final class SubHandlers {

    private SubHandlers() {
    }

    /** {@code handler}'s nested handlers, or empty for a handler type that doesn't nest any. */
    static List<Handler> of(Handler handler) {
        try {
            Method getHandlers = handler.getClass().getMethod("getHandlers");
            Object result = getHandlers.invoke(handler);
            return result instanceof Handler[] nested ? List.of(nested) : List.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of(); // not a delegating handler, or reflection was denied
        }
    }
}
