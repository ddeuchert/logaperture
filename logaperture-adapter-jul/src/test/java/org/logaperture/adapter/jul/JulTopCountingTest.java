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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.LoggerByteCount;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/top.md "Adapter SPI", exercised against {@link JulLoggingAdapter}
 * directly. Persistence is faked with a plain {@link Handler} exposing a
 * public {@code getFile()} -- the same duck-typed reflection {@link
 * JulHandlerDiagnostics} already uses for a real JBoss LogManager handler
 * (doc/specs/doctor.md "Adapter SPI"), so this needs no JBoss LogManager on
 * the classpath, same reasoning as {@code JulLoggingAdapterTest}'s own
 * plain-JUL-only scope.
 */
class JulTopCountingTest {

    /** A handler that looks persistent to {@link JulHandlerDiagnostics} without needing a real file-backed handler class. */
    private static final class FakePersistentHandler extends Handler {
        private final List<LogRecord> published = new ArrayList<>();

        FakePersistentHandler() {
            setFormatter(new SimpleFormatter());
        }

        /** The one method {@link JulHandlerDiagnostics#of} needs to see to call this handler persistent. */
        public File getFile() {
            return new File("fake.log");
        }

        @Override
        public void publish(LogRecord record) {
            if (isLoggable(record)) {
                published.add(record);
                getFormatter().format(record); // mirrors a real handler calling its formatter before writing
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private JulLoggingAdapter adapter;
    private String prefix;
    private java.util.logging.Level originalRootLevel;

    @BeforeEach
    void setUp() {
        adapter = new JulLoggingAdapter();
        prefix = "it." + UUID.randomUUID().toString().replace("-", "") + ".";
        originalRootLevel = Logger.getLogger("").getLevel();
        if (originalRootLevel == null) {
            Logger.getLogger("").setLevel(java.util.logging.Level.INFO);
            originalRootLevel = java.util.logging.Level.INFO;
        }
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").setLevel(originalRootLevel);
    }

    private String name(String suffix) {
        return prefix + suffix;
    }

    @Test
    void installByteCounting_wrapsAPersistentHandler_notAConsoleHandler() {
        FakePersistentHandler persistent = new FakePersistentHandler();
        ConsoleHandler console = new ConsoleHandler();
        Logger logger = Logger.getLogger(name("mixed"));
        logger.addHandler(persistent);
        logger.addHandler(console);
        try {
            adapter.installByteCounting();

            assertTrue(persistent.getFormatter() instanceof ByteCountingFormatter, "persistent handler is wrapped");
            assertFalse(console.getFormatter() instanceof ByteCountingFormatter, "console handler is left alone");
        } finally {
            logger.removeHandler(persistent);
            logger.removeHandler(console);
        }
    }

    @Test
    void loggingThroughAWrappedHandler_isReflectedInByteCounts() {
        FakePersistentHandler persistent = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("counted"));
        logger.addHandler(persistent);
        try {
            adapter.installByteCounting();

            logger.info("hello from the counted logger");

            List<LoggerByteCount> counts = adapter.byteCounts();
            LoggerByteCount row = counts.stream()
                    .filter(c -> c.loggerName().equals(name("counted")))
                    .findFirst().orElseThrow();
            assertTrue(row.totalBytes() > 0);
        } finally {
            logger.removeHandler(persistent);
        }
    }

    @Test
    void installByteCounting_calledTwice_doesNotDoubleWrapOrDoubleCount() {
        FakePersistentHandler persistent = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("idempotent"));
        logger.addHandler(persistent);
        try {
            adapter.installByteCounting();
            Formatter afterFirst = persistent.getFormatter();
            adapter.installByteCounting();

            assertEquals(afterFirst, persistent.getFormatter(), "re-installing leaves an already-wrapped handler alone");

            logger.info("counted exactly once");

            long totalBytes = adapter.byteCounts().stream()
                    .filter(c -> c.loggerName().equals(name("idempotent")))
                    .mapToLong(LoggerByteCount::totalBytes)
                    .sum();
            assertEquals(1, persistent.published.size(), "the handler itself only ever saw one record");
            long expectedBytes = new SimpleFormatter().format(persistent.published.get(0))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertEquals(expectedBytes, totalBytes, "one publish is counted once, not twice");
        } finally {
            logger.removeHandler(persistent);
        }
    }

    @Test
    void installByteCounting_consoleOnlyLogger_contributesNothing() {
        ConsoleHandler console = new ConsoleHandler();
        Logger logger = Logger.getLogger(name("consoleOnly"));
        logger.addHandler(console);
        try {
            adapter.installByteCounting();
            logger.info("never counted");

            assertTrue(adapter.byteCounts().stream().noneMatch(c -> c.loggerName().equals(name("consoleOnly"))));
        } finally {
            logger.removeHandler(console);
        }
    }
}
