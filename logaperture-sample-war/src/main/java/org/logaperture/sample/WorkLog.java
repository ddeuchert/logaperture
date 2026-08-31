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
package org.logaperture.sample;

/**
 * The one place the sample emits log lines. All three framework loggers share
 * the name {@value #LOGGER_NAME}, so a single
 * {@code logctl debug org.logaperture.sample.work.Worker} override lands on
 * every one of them at once — which is the whole point of the sample: to show
 * one override taking effect across everything JBoss LogManager funnels.
 */
final class WorkLog {

    /** The single logger name to target with {@code logctl}. */
    static final String LOGGER_NAME = "org.logaperture.sample.work.Worker";

    private static final java.util.logging.Logger JUL =
            java.util.logging.Logger.getLogger(LOGGER_NAME);
    private static final org.slf4j.Logger SLF4J =
            org.slf4j.LoggerFactory.getLogger(LOGGER_NAME);
    private static final org.apache.logging.log4j.Logger LOG4J =
            org.apache.logging.log4j.LogManager.getLogger(LOGGER_NAME);

    private WorkLog() {
    }

    /**
     * Log {@code marker} once at each of TRACE, DEBUG, INFO, WARN and ERROR
     * through {@code java.util.logging}, SLF4J and Log4j — fifteen records in
     * all. Which ones actually surface depends on the level in force, which is
     * exactly what an override changes.
     */
    static void emitAllLevels(String marker) {
        JUL.finest("[jul] TRACE " + marker);
        JUL.fine("[jul] DEBUG " + marker);
        JUL.info("[jul] INFO " + marker);
        JUL.warning("[jul] WARN " + marker);
        JUL.severe("[jul] ERROR " + marker);

        SLF4J.trace("[slf4j] TRACE {}", marker);
        SLF4J.debug("[slf4j] DEBUG {}", marker);
        SLF4J.info("[slf4j] INFO {}", marker);
        SLF4J.warn("[slf4j] WARN {}", marker);
        SLF4J.error("[slf4j] ERROR {}", marker);

        LOG4J.trace("[log4j] TRACE {}", marker);
        LOG4J.debug("[log4j] DEBUG {}", marker);
        LOG4J.info("[log4j] INFO {}", marker);
        LOG4J.warn("[log4j] WARN {}", marker);
        LOG4J.error("[log4j] ERROR {}", marker);
    }
}
