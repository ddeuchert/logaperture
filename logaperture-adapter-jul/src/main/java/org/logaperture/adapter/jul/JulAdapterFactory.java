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

import org.logaperture.core.spi.LoggingAdapter;

import java.util.Objects;

/**
 * Builds a {@link JulLoggingAdapter} — mirrors {@code
 * logaperture-adapter-logback}'s {@code LogbackAdapterFactory}. The adapter
 * is over {@code java.util.logging}; on WildFly the installed
 * {@code java.util.logging.LogManager} is JBoss LogManager and its logger
 * tree is the server's system context (the only context this release
 * supports).
 */
public final class JulAdapterFactory {

    private JulAdapterFactory() {
    }

    /**
     * An adapter over the installed {@code java.util.logging.LogManager},
     * with no handler-name resolution — every handler keeps its {@code
     * <class>@<idhash>} identity token. Plain JUL, or WildFly before issue
     * #14 wires a resolver in.
     */
    public static LoggingAdapter forCurrentContext() {
        return new JulLoggingAdapter();
    }

    /**
     * An adapter over the installed {@code java.util.logging.LogManager} that
     * resolves real handler names through {@code resolver} — doc/specs/
     * handler-floor-control.md "WildFly handler name resolution" (issue #14).
     * {@code logaperture-container-wildfly} passes a resolver backed by
     * WildFly's own management model, and holds the returned {@link
     * JulLoggingAdapter} (not just {@link LoggingAdapter}) so it can call
     * {@link JulLoggingAdapter#invalidateNameCache()} from its
     * configuration-change hook.
     */
    public static JulLoggingAdapter forCurrentContext(HandlerNameResolver resolver) {
        return new JulLoggingAdapter(Objects.requireNonNull(resolver, "resolver"));
    }
}
