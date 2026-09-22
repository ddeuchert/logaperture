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
import org.logaperture.core.StormObservation;
import org.logaperture.core.StormObserver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/storm-detection.md "Adapter SPI" + "Testing", exercised against
 * {@link JulLoggingAdapter} directly -- same fake-persistent-handler
 * technique {@code JulTopCountingTest} uses, no JBoss LogManager needed.
 */
class JulStormObserverTest {

    private static final class FakePersistentHandler extends Handler {
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
                // no-op -- these tests only care about the filter's verdict
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** Records every observation fed to it -- never throws, matching the real contract. */
    private static final class RecordingObserver implements StormObserver {
        final List<StormObservation> observed = new ArrayList<>();

        @Override
        public void observe(StormObservation observation) {
            observed.add(observation);
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
            Logger.getLogger("").setLevel(Level.INFO);
            originalRootLevel = Level.INFO;
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
    void installedFilter_returnsIdenticalVerdict_toAPreExistingFilter_allowCase() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Filter preExisting = record -> true; // allows everything
        handler.setFilter(preExisting);
        Logger logger = Logger.getLogger(name("allow"));
        logger.addHandler(handler);
        try {
            adapter.installStormDetection(new RecordingObserver());

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("allow"));
            assertTrue(handler.getFilter().isLoggable(record), "must return exactly what the pre-existing filter would have");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_returnsIdenticalVerdict_toAPreExistingFilter_denyCase() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Filter preExisting = record -> false; // denies everything
        handler.setFilter(preExisting);
        Logger logger = Logger.getLogger(name("deny"));
        logger.addHandler(handler);
        try {
            adapter.installStormDetection(new RecordingObserver());

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("deny"));
            assertFalse(handler.getFilter().isLoggable(record), "the observer must never override a deny into an allow");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_withNoPreExistingFilter_alwaysAllows() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("nofilter"));
        logger.addHandler(handler);
        try {
            adapter.installStormDetection(new RecordingObserver());

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("nofilter"));
            assertTrue(handler.getFilter().isLoggable(record));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_feedsEveryCandidateEvent_toTheObserver() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("feed"));
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        RecordingObserver observer = new RecordingObserver();
        try {
            adapter.installStormDetection(observer);

            logger.info("first");
            logger.warning("second");

            assertEquals(2, observer.observed.size());
            assertEquals(name("feed"), observer.observed.get(0).loggerName());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void secondInstallCall_doesNotDoubleInstall_orDoubleCount() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("idempotent"));
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        RecordingObserver observer = new RecordingObserver();
        try {
            adapter.installStormDetection(observer);
            Filter afterFirst = handler.getFilter();
            adapter.installStormDetection(observer);

            assertEquals(afterFirst, handler.getFilter(), "re-installing leaves an already-wrapped handler's filter alone");

            logger.info("counted exactly once");
            assertEquals(1, observer.observed.size());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installStormDetection_consoleHandler_alsoWrapped_observerNeverDeniesAnEvent() {
        // Unlike installByteCounting (persistent handlers only), storm
        // detection is a gate-stage observer on every real handler -- a
        // console-only logger's records must still reach the observer.
        ConsoleHandler console = new ConsoleHandler();
        Logger logger = Logger.getLogger(name("console"));
        logger.addHandler(console);
        logger.setUseParentHandlers(false);
        RecordingObserver observer = new RecordingObserver();
        try {
            adapter.installStormDetection(observer);
            logger.info("seen by the observer even though it's console-only");

            assertEquals(1, observer.observed.size());
        } finally {
            logger.removeHandler(console);
        }
    }
}
