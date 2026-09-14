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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place the sample emits log lines. {@link #emitAllLevels} logs
 * through all three frameworks under the one shared name {@value
 * #LOGGER_NAME}, so a single {@code logctl debug
 * org.logaperture.sample.work.Worker} override lands on every one of them at
 * once — which is the whole point of that method: to show one override
 * taking effect across everything JBoss LogManager funnels. {@link #emit}
 * instead logs a caller-chosen message, at a caller-chosen level, through a
 * caller-chosen framework -- but through every ancestor category of the
 * given logger name, not just the leaf, so a pattern override further up the
 * hierarchy has something registered to match. Backs {@code GET /log}'s
 * query parameters.
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

    /**
     * The five levels {@link #emit} accepts, independent of any one
     * framework's own level type. JUL's five-level scheme doesn't share
     * SLF4J/Log4j's names, so {@link #emit} maps this to
     * {@code java.util.logging.Level} itself (FINEST/FINE/INFO/WARNING/
     * SEVERE) when the target is JUL.
     */
    enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR;

        /**
         * @throws IllegalArgumentException if {@code value} isn't one of
         *                                   this enum's names, matched
         *                                   case-insensitively
         */
        static Level parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "level must be one of trace, debug, info, warn, error (got \"" + value + "\")");
            }
        }
    }

    /** The logging frameworks {@link #emit} can target. */
    enum Implementation {
        SLF4J, JUL, LOG4J;

        /**
         * @throws IllegalArgumentException if {@code value} isn't one of
         *                                   this enum's names, matched
         *                                   case-insensitively
         */
        static Implementation parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "implementation must be one of slf4j, jul, log4j (got \"" + value + "\")");
            }
        }
    }

    /**
     * Logs {@code message} once, at {@code level}, through {@code
     * implementation} -- once per dot-separated category of {@code
     * loggerName}, from the top-level segment down to {@code loggerName}
     * itself. {@code "org.logaperture.log"} logs through {@code "org"},
     * {@code "org.logaperture"} and {@code "org.logaperture.log"}, each as
     * its own named logger, so a pattern override anywhere in that ancestry
     * (not just an exact match on the leaf) has a real logger registered to
     * land on. Returns the categories logged, in that top-down order.
     *
     * <p>Unlike {@link #emitAllLevels}, logger name, level and framework are
     * all caller-chosen -- each framework caches its own {@code Logger}
     * instances by name, so this needs no cache of its own.
     */
    static List<String> emit(String loggerName, String message, Level level, Implementation implementation) {
        List<String> categories = categories(loggerName);
        for (String category : categories) {
            switch (implementation) {
                case JUL -> java.util.logging.Logger.getLogger(category).log(toJulLevel(level), message);
                case SLF4J -> emitSlf4j(org.slf4j.LoggerFactory.getLogger(category), level, message);
                case LOG4J -> org.apache.logging.log4j.LogManager.getLogger(category)
                        .log(toLog4jLevel(level), message);
            }
        }
        return categories;
    }

    /**
     * {@code loggerName} split at each {@code '.'} into its ancestor
     * categories, top-down: {@code "org.logaperture.log"} becomes {@code
     * ["org", "org.logaperture", "org.logaperture.log"]}. A name with no dot
     * is its own single-element list.
     */
    private static List<String> categories(String loggerName) {
        List<String> categories = new ArrayList<>();
        int from = 0;
        while (true) {
            int dot = loggerName.indexOf('.', from);
            if (dot < 0) {
                categories.add(loggerName);
                return categories;
            }
            categories.add(loggerName.substring(0, dot));
            from = dot + 1;
        }
    }

    private static void emitSlf4j(org.slf4j.Logger logger, Level level, String message) {
        switch (level) {
            case TRACE -> logger.trace(message);
            case DEBUG -> logger.debug(message);
            case INFO -> logger.info(message);
            case WARN -> logger.warn(message);
            case ERROR -> logger.error(message);
        }
    }

    private static java.util.logging.Level toJulLevel(Level level) {
        return switch (level) {
            case TRACE -> java.util.logging.Level.FINEST;
            case DEBUG -> java.util.logging.Level.FINE;
            case INFO -> java.util.logging.Level.INFO;
            case WARN -> java.util.logging.Level.WARNING;
            case ERROR -> java.util.logging.Level.SEVERE;
        };
    }

    private static org.apache.logging.log4j.Level toLog4jLevel(Level level) {
        return switch (level) {
            case TRACE -> org.apache.logging.log4j.Level.TRACE;
            case DEBUG -> org.apache.logging.log4j.Level.DEBUG;
            case INFO -> org.apache.logging.log4j.Level.INFO;
            case WARN -> org.apache.logging.log4j.Level.WARN;
            case ERROR -> org.apache.logging.log4j.Level.ERROR;
        };
    }
}
