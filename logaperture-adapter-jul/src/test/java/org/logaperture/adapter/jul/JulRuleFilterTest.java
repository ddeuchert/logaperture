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
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleGate;
import org.logaperture.core.StormObservation;
import org.logaperture.core.StormObserver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * doc/specs/rule-pipeline-foundation.md "Relationship to the
 * storm-detection filter" + doc/specs/drop-rule.md "Evaluation" -- same
 * technique {@code JulStormObserverTest} uses, no JBoss LogManager needed.
 */
class JulRuleFilterTest {

    private static final RuleGate ALWAYS_ALLOW = (recordIdentity, event) -> GateVerdict.allow();
    private static final RuleGate ALWAYS_DENY = (recordIdentity, event) -> GateVerdict.deny("r1");

    private static final class FakePersistentHandler extends Handler {
        FakePersistentHandler() {
            setFormatter(new SimpleFormatter());
        }

        public File getFile() {
            return new File("fake.log");
        }

        @Override
        public void publish(LogRecord record) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

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
    void installedFilter_chainsAPreExistingFilter_allowCase() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Filter preExisting = record -> true;
        handler.setFilter(preExisting);
        Logger logger = Logger.getLogger(name("allow"));
        logger.addHandler(handler);
        try {
            adapter.installRulePipeline(ALWAYS_ALLOW);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("allow"));
            assertTrue(handler.getFilter().isLoggable(record));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_chainsAPreExistingFilter_denyCase() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Filter preExisting = record -> false;
        handler.setFilter(preExisting);
        Logger logger = Logger.getLogger(name("deny"));
        logger.addHandler(handler);
        try {
            adapter.installRulePipeline(ALWAYS_ALLOW);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("deny"));
            assertFalse(handler.getFilter().isLoggable(record),
                    "this filter must never override a pre-existing deny into an allow");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_withNoPreExistingFilter_allowsWhenGateAllows() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("nofilter"));
        logger.addHandler(handler);
        try {
            adapter.installRulePipeline(ALWAYS_ALLOW);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("nofilter"));
            assertTrue(handler.getFilter().isLoggable(record));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_deniesWhenGateDenies() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("denygate"));
        logger.addHandler(handler);
        try {
            adapter.installRulePipeline(ALWAYS_DENY);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("denygate"));
            assertFalse(handler.getFilter().isLoggable(record));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installedFilter_gateFailure_failsOpen() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("gatefails"));
        logger.addHandler(handler);
        RuleGate throwing = (recordIdentity, event) -> {
            throw new RuntimeException("boom");
        };
        try {
            adapter.installRulePipeline(throwing);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("gatefails"));
            assertTrue(handler.getFilter().isLoggable(record), "a gate-evaluation bug must never itself drop an event");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void secondInstallCall_doesNotDoubleInstall() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("idempotent"));
        logger.addHandler(handler);
        try {
            adapter.installRulePipeline(ALWAYS_ALLOW);
            Filter afterFirst = handler.getFilter();
            adapter.installRulePipeline(ALWAYS_ALLOW);

            assertEquals(afterFirst, handler.getFilter(), "re-installing leaves an already-wrapped handler's filter alone");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void composesWithStormDetectionsFilter_ruleFilterInstalledFirst() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("compose-rule-first"));
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        RecordingObserver observer = new RecordingObserver();
        try {
            adapter.installRulePipeline(ALWAYS_ALLOW);
            adapter.installStormDetection(observer);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("compose-rule-first"));
            assertTrue(handler.getFilter().isLoggable(record));

            logger.info("both filters see this");
            assertEquals(1, observer.observed.size(), "storm detection still observes every event with the rule filter chained above it");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void composesWithStormDetectionsFilter_stormFilterInstalledFirst() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("compose-storm-first"));
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        RecordingObserver observer = new RecordingObserver();
        try {
            adapter.installStormDetection(observer);
            adapter.installRulePipeline(ALWAYS_ALLOW);

            LogRecord record = new LogRecord(Level.INFO, "hello");
            record.setLoggerName(name("compose-storm-first"));
            assertTrue(handler.getFilter().isLoggable(record));

            logger.info("both filters see this");
            assertEquals(1, observer.observed.size());
        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * doc/specs/drop-rule.md "Interaction with storm detection", Decision
     * #3: when the rule filter actually denies (production install order:
     * storm detection first, so it ends up the inner delegate), storm
     * detection's observer must still see the event -- a dropped event is
     * not silently invisible to storm detection.
     */
    @Test
    void ruleFilterDenies_stormDetectionInnerDelegate_stillObserves() {
        FakePersistentHandler handler = new FakePersistentHandler();
        Logger logger = Logger.getLogger(name("deny-storm-inner"));
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        RecordingObserver observer = new RecordingObserver();
        try {
            adapter.installStormDetection(observer); // inner
            adapter.installRulePipeline(ALWAYS_DENY); // outer -- production order

            // A bare Handler subclass's own publish() (this fake included) doesn't call isLoggable
            // itself -- Logger.log() doesn't check it centrally either, unlike StreamHandler's real
            // publish() -- so the filter chain is exercised directly, same as every other test above.
            LogRecord record = new LogRecord(Level.INFO, "dropped, but still observed by storm detection");
            record.setLoggerName(name("deny-storm-inner"));
            assertFalse(handler.getFilter().isLoggable(record), "the outer rule filter still denies");
            assertEquals(1, observer.observed.size(), "the inner storm-detection delegate still observed it");
        } finally {
            logger.removeHandler(handler);
        }
    }
}
