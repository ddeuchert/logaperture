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
package org.logaperture.core.spi;

import java.util.Objects;

/**
 * One logging context a {@link ContainerIntegration} has discovered — see
 * doc/specs/wildfly-support.md, "The {@code ContainerIntegration} SPI". A
 * plain {@code java -jar} app has exactly one (the {@code "system"}
 * context); a container may have several (the server itself, plus one per
 * deployment that carries its own logging configuration).
 *
 * <p>Everything {@code core} needs to run level control against a single
 * context, and to recognise that context again after a redeploy.
 */
public interface ContextHandle {

    /** {@code stableKey} for the {@code "system"} context — the server/app itself. */
    String SYSTEM = "system";

    /**
     * Stable identity for this context. Survives classloader replacement:
     * {@link #SYSTEM} for the server/app itself, the deployment name
     * ({@code "myapp.war"}) for a deployment context. Used to recognise a
     * context across a redeploy and to label rows in output. This release
     * does not key persisted overrides on it — overrides are broadcast to
     * every context, not scoped (doc/specs/wildfly-support.md, "Broadcast
     * semantics").
     */
    String stableKey();

    /** Human-readable, for {@code logctl levels}' CONTEXT column. Often the same as {@link #stableKey()}. */
    String displayName();

    /** The bound adapter for this context. One {@link LoggingAdapter} instance per context (§15.4). */
    LoggingAdapter adapter();

    /** A trivial immutable {@link ContextHandle}. */
    static ContextHandle of(String stableKey, String displayName, LoggingAdapter adapter) {
        Objects.requireNonNull(stableKey, "stableKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(adapter, "adapter");
        return new ContextHandle() {
            @Override
            public String stableKey() {
                return stableKey;
            }

            @Override
            public String displayName() {
                return displayName;
            }

            @Override
            public LoggingAdapter adapter() {
                return adapter;
            }

            @Override
            public String toString() {
                return "ContextHandle[" + stableKey + "]";
            }
        };
    }
}
