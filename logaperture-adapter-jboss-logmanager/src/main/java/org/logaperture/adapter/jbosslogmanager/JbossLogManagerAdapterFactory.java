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

import org.logaperture.core.spi.LoggingAdapter;

/**
 * Builds a {@link JbossLogManagerAdapter} — mirrors {@code
 * logaperture-adapter-logback}'s {@code LogbackAdapterFactory}. The adapter
 * is over {@code java.util.logging}; on WildFly the installed
 * {@code java.util.logging.LogManager} is JBoss LogManager and its logger
 * tree is the server's system context (the only context this release
 * supports).
 */
public final class JbossLogManagerAdapterFactory {

    private JbossLogManagerAdapterFactory() {
    }

    /** An adapter over the installed {@code java.util.logging.LogManager}. */
    public static LoggingAdapter forCurrentContext() {
        return new JbossLogManagerAdapter();
    }
}
