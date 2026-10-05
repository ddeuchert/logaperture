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
import org.logaperture.core.StormObserver;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/rule-pipeline-foundation.md "Canonical filter layering", issue
 * #145: the storm and rule filters must stay exactly {@code Rule -> Storm ->
 * base} across any number of re-arm ticks, in any install order.
 */
class FilterLayeringTest {

    private static final int TICKS = 1_000;
    private static final RuleGate ALWAYS_ALLOW = (recordIdentity, event) -> GateVerdict.allow();

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

    private final AtomicInteger observations = new AtomicInteger();
    private final StormObserver countingObserver = observation -> observations.incrementAndGet();

    private JulLoggingAdapter adapter;
    private FakePersistentHandler handler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        adapter = new JulLoggingAdapter();
        handler = new FakePersistentHandler();
        logger = Logger.getLogger("it." + UUID.randomUUID().toString().replace("-", "") + ".layering");
        logger.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
    }

    private void sweepTick() {
        adapter.installStormDetection(countingObserver);
        adapter.installRulePipeline(ALWAYS_ALLOW);
    }

    private LogRecord record() {
        LogRecord record = new LogRecord(Level.INFO, "hello");
        record.setLoggerName(logger.getName());
        return record;
    }

    private void assertCanonical(Filter expectedBase) {
        FilterLayering.Run run = FilterLayering.Run.of(handler.getFilter());
        assertTrue(run.canonical, "layering must be canonical");
        assertEquals(2, run.depth, "exactly one rule filter and one storm filter");
        assertInstanceOf(JulRuleFilter.class, handler.getFilter(), "rule filter outermost");
        assertSame(expectedBase, run.base);
    }

    @Test
    void manySweepTicks_keepTheChainAtTwoLayers_andObserveEachRecordOnce() { // the bug as reported
        for (int tick = 0; tick < TICKS; tick++) {
            sweepTick();
        }

        assertCanonical(null);
        assertTrue(handler.getFilter().isLoggable(record()));
        assertEquals(1, observations.get(), "one record reaches storm detection exactly once");
    }

    @Test
    void ruleInstalledFirst_stillEndsCanonical() {
        for (int tick = 0; tick < TICKS; tick++) {
            adapter.installRulePipeline(ALWAYS_ALLOW);
            adapter.installStormDetection(countingObserver);
        }

        assertCanonical(null);
    }

    @Test
    void eachInstallAlone_isIdempotent() {
        for (int tick = 0; tick < TICKS; tick++) {
            adapter.installStormDetection(countingObserver);
        }
        Filter stormOnly = handler.getFilter();
        assertInstanceOf(JulStormFilter.class, stormOnly);
        assertEquals(1, FilterLayering.Run.of(stormOnly).depth);

        adapter.installRulePipeline(ALWAYS_ALLOW);
        Filter steady = handler.getFilter();
        sweepTick();
        assertSame(steady, handler.getFilter(), "a canonical chain is left untouched, not rebuilt");
    }

    @Test
    void anAlreadyGrownChain_collapsesOnOneInstall_keepingTheBase() {
        Filter base = record -> true;
        Filter grown = base;
        for (int i = 0; i < 50; i++) {
            grown = new JulRuleFilter(new JulStormFilter(grown, countingObserver), ALWAYS_ALLOW);
            grown = new JulStormFilter(grown, countingObserver); // the alternating shape #145 built
        }
        handler.setFilter(grown);

        adapter.installRulePipeline(ALWAYS_ALLOW);

        assertCanonical(base);
        assertTrue(handler.getFilter().isLoggable(record()));
        assertEquals(1, observations.get());
    }

    @Test
    void aForeignBaseFilter_staysInnermost_andItsDenyStillDecides() {
        Filter denyAll = record -> false;
        handler.setFilter(denyAll);

        for (int tick = 0; tick < TICKS; tick++) {
            sweepTick();
        }

        assertCanonical(denyAll);
        assertFalse(handler.getFilter().isLoggable(record()));
    }

    @Test
    void aForeignFilterWrappingOurs_costsOnePairOnce_thenStopsGrowing() { // F3
        sweepTick();
        Filter ours = handler.getFilter();
        Filter foreignWrapper = record -> ours.isLoggable(record);
        handler.setFilter(foreignWrapper);

        for (int tick = 0; tick < TICKS; tick++) {
            sweepTick();
        }

        assertCanonical(foreignWrapper);
    }

    @Test
    void manyFormatterTicks_keepByteCountingOutsideTrim_withoutGrowing() { // formatter symmetry, F5
        Formatter original = handler.getFormatter();
        for (int tick = 0; tick < TICKS; tick++) {
            adapter.installTrimRendering(ALWAYS_ALLOW);
            adapter.installByteCounting();
        }

        ByteCountingFormatter counting = assertInstanceOf(ByteCountingFormatter.class, handler.getFormatter());
        JulTrimFormatter trim = assertInstanceOf(JulTrimFormatter.class, counting.delegate());
        assertSame(original, trim.delegate());
    }
}
