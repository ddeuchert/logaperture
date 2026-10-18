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

import java.util.List;
import java.util.logging.Handler;

/**
 * Handlers that only hold other handlers back and hand them records, and so are looked
 * <em>through</em> when the adapter catalogues handlers (doc/specs/quarkus-support.md Q3,
 * "A transparent delayed handler").
 *
 * <p>Quarkus attaches one {@code QuarkusDelayedHandler} to the root logger and puts its console,
 * file, syslog and socket handlers inside it. To a user those inner handlers are "the handlers":
 * they carry the levels, the files and the formats. So {@code list handlers}, handler-level
 * control, the {@code ALL_HANDLERS} fan-out, the handler-floor hint, {@code top} and {@code
 * doctor} see the children in its place. The delayed handler itself stays the attach point for
 * filters ({@link JulLoggingAdapter#installStormDetection}, {@link
 * JulLoggingAdapter#installRulePipeline}): one filter there sees each record once, instead of
 * once per child.
 *
 * <p>Matched by class name, never {@code instanceof}: this adapter has no compile-time reference
 * to Quarkus (nor to JBoss LogManager), and the class is in an application class loader.
 */
final class TransparentHandlers {

    static final String QUARKUS_DELAYED_HANDLER = "io.quarkus.bootstrap.logging.QuarkusDelayedHandler";

    private TransparentHandlers() {
    }

    static boolean isTransparent(Handler handler) {
        return QUARKUS_DELAYED_HANDLER.equals(handler.getClass().getName());
    }

    /** The handlers to catalogue in {@code handler}'s place; empty before Quarkus has activated it. */
    static List<Handler> childrenOf(Handler handler) {
        return SubHandlers.of(handler);
    }
}
