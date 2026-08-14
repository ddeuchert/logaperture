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
package org.logaperture.adapter.logback;

import ch.qos.logback.classic.LoggerContext;
import org.logaperture.core.spi.LoggingAdapter;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Entry point for callers outside this package. Returns the {@code
 * core.spi.LoggingAdapter} interface type, not the concrete class — this
 * is what lets {@code logaperture-container-none} stay free of any
 * compile-time {@code ch.qos.logback} import.
 */
public final class LogbackAdapterFactory {

    private LogbackAdapterFactory() {
    }

    /**
     * @throws AdapterInstallException if SLF4J isn't currently bound to a
     *                                 Logback {@code LoggerContext} (e.g. a
     *                                 NOP binding, or a different framework)
     */
    public static LoggingAdapter forCurrentContext() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new AdapterInstallException(
                    "SLF4J is not bound to a Logback LoggerContext (found: "
                            + factory.getClass().getName() + ")");
        }
        return new LogbackLoggingAdapter(context);
    }
}
