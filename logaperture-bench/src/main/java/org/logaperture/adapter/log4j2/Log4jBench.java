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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;

/**
 * A real Log4j 2 {@link LoggerContext} for the component benchmarks (doc/specs/overhead-benchmarks.md,
 * "Revision 2026-11-06: the Log4j 2 gate"): one logger, its level INFO, nothing else configured, so the
 * only thing the benchmarks add to a call is the gate under test. It lives in the adapter's package
 * because {@link GateFilter} is package-private.
 */
final class Log4jBench {

    /** The logger the 1.0 suite uses, so the gate sees the same name and the same parent. */
    static final String LOGGER = org.logaperture.core.IdleComponentsBenchmark.LOGGER;
    /** A parameterized message, as an application using {@code {}} writes it. */
    static final String PATTERN = "Processed order {} for customer {} in {} ms";

    private Log4jBench() {
    }

    /** A started context whose root level is INFO, named so two forks never share one. */
    static LoggerContext startedContext(String name) {
        LoggerContext context = new LoggerContext(name);
        context.start();
        Configuration configuration = context.getConfiguration();
        configuration.getRootLogger().setLevel(Level.INFO);
        context.updateLoggers();
        return context;
    }

    static Logger logger(LoggerContext context) {
        Logger logger = context.getLogger(LOGGER);
        if (!Level.INFO.equals(logger.getLevel())) {
            throw new IllegalStateException("expected INFO on " + LOGGER + ", got " + logger.getLevel());
        }
        return logger;
    }
}
