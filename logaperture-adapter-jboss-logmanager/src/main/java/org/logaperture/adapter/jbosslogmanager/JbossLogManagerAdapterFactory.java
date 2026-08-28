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
package org.logaperture.adapter.jbosslogmanager;

import org.jboss.logmanager.LogContext;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.Objects;

/**
 * Builds a {@link JbossLogManagerAdapter} for a given {@link
 * org.jboss.logmanager.LogContext} — mirrors {@code
 * logaperture-adapter-logback}'s {@code LogbackAdapterFactory}. Slice 3's
 * WildFly {@code ContainerIntegration} calls {@link #forContext} once per
 * registered {@code LogContext} (the server's own, plus one per deployment
 * that carries its own logging configuration).
 */
public final class JbossLogManagerAdapterFactory {

    private JbossLogManagerAdapterFactory() {
    }

    /** An adapter bound to {@code context}. */
    public static LoggingAdapter forContext(LogContext context) {
        return new JbossLogManagerAdapter(Objects.requireNonNull(context, "context"));
    }

    /**
     * An adapter bound to whatever {@code LogContext} the current
     * {@code LogContextSelector} resolves — the server's own, in a
     * standalone WildFly with no per-deployment logging configuration
     * (doc/specs/wildfly-support.md, Slice 3).
     */
    public static LoggingAdapter forCurrentContext() {
        return forContext(LogContext.getLogContext());
    }
}
