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
import java.util.Map;
import java.util.logging.Handler;

/**
 * Resolves live {@link Handler} instances to their framework-configured names
 * — doc/specs/handler-floor-control.md "WildFly handler name resolution"
 * (issue #14). The generic {@link JulLoggingAdapter} has no way to know that
 * WildFly's stock {@code FILE} handler is called {@code FILE}; a container
 * that <em>does</em> know its framework (here {@code
 * logaperture-container-wildfly}, reading WildFly's own management model
 * in-VM) supplies one of these so {@code logctl handler FILE …} works by the
 * name an operator actually reads in {@code standalone.xml}.
 *
 * <p>Deliberately a bulk call: an implementation can do one management-model
 * read for the whole set rather than one per handler. It must never throw —
 * an implementation that can resolve nothing (the model isn't queryable yet,
 * an unexpected server version) returns an empty map, and the adapter keeps
 * every handler on its {@code <class>@<idhash>} identity-token fallback.
 */
@FunctionalInterface
public interface HandlerNameResolver {

    /**
     * The configured name for as many of {@code handlers} as can be resolved
     * right now. Absent entries keep the adapter's identity-token fallback.
     * Never throws; returns an empty map when nothing resolves.
     */
    Map<Handler, String> resolve(List<Handler> handlers);

    /** Resolves nothing — the default for plain {@code java.util.logging}. */
    HandlerNameResolver NONE = handlers -> Map.of();
}
