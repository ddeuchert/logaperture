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

import java.io.File;
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/trim-rule.md "Evaluation", "Interaction with top". Unlike
 * {@code top}'s own byte-accounting ({@link JulTopCountingTest}), trim wraps
 * <em>every</em> handler's formatter, console included -- there is no
 * persistent-only restriction (doc/specs/trim-rule.md "What trim means"
 * carries no such scope reduction, matching {@code drop}'s own "every
 * handler" scope). {@link #installTrimRendering_thenInstallByteCounting_trimEndsUpInside}
 * and its reordered counterpart use a persistent fake handler instead,
 * since {@code top}'s own console-skip (Decision #1) would otherwise make
 * the layering unobservable.
 */
class JulTrimRenderingTest {

    private static final RuleGate ALWAYS_ALLOW = (recordIdentity, event) -> GateVerdict.allow();

    /** A handler that looks persistent to {@link JulHandlerDiagnostics}, same double {@link JulTopCountingTest} uses. */
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

    private JulLoggingAdapter adapter;
    private String prefix;
    private Handler handler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        adapter = new JulLoggingAdapter();
        prefix = "it." + UUID.randomUUID().toString().replace("-", "") + ".";
    }

    private String name(String suffix) {
        return prefix + suffix;
    }

    @AfterEach
    void tearDown() {
        if (logger != null && handler != null) {
            logger.removeHandler(handler);
        }
    }

    @Test
    void installTrimRendering_wrapsAConsoleHandlerToo() {
        handler = new ConsoleHandler();
        logger = Logger.getLogger(name("console"));
        logger.addHandler(handler);

        adapter.installTrimRendering(ALWAYS_ALLOW);

        assertTrue(handler.getFormatter() instanceof JulTrimFormatter);
    }

    @Test
    void installTrimRendering_calledTwice_doesNotDoubleWrap() {
        handler = new ConsoleHandler();
        logger = Logger.getLogger(name("idempotent"));
        logger.addHandler(handler);

        adapter.installTrimRendering(ALWAYS_ALLOW);
        Formatter afterFirst = handler.getFormatter();
        adapter.installTrimRendering(ALWAYS_ALLOW);

        assertEquals(afterFirst, handler.getFormatter(), "re-installing leaves an already-wrapped handler alone");
    }

    @Test
    void installTrimRendering_thenInstallByteCounting_trimEndsUpInside() {
        handler = new FakePersistentHandler();
        logger = Logger.getLogger(name("layered"));
        logger.addHandler(handler);

        adapter.installTrimRendering(ALWAYS_ALLOW);
        adapter.installByteCounting();

        Formatter outer = handler.getFormatter();
        assertTrue(outer instanceof ByteCountingFormatter, "byte-counting is outermost");
        Formatter inner = ((ByteCountingFormatter) outer).delegate();
        assertTrue(inner instanceof JulTrimFormatter, "trim is installed underneath");
    }

    @Test
    void installByteCounting_thenInstallTrimRendering_reLayersTrimUnderneath() {
        // Out-of-order robustness: even if byte-counting somehow got installed first on a given
        // tick, a later installTrimRendering() call still produces the correct trim-inside,
        // byte-counting-outside layering rather than stacking trim on top.
        handler = new FakePersistentHandler();
        logger = Logger.getLogger(name("reordered"));
        logger.addHandler(handler);

        adapter.installByteCounting();
        adapter.installTrimRendering(ALWAYS_ALLOW);

        Formatter outer = handler.getFormatter();
        assertTrue(outer instanceof ByteCountingFormatter, "byte-counting stays outermost");
        Formatter inner = ((ByteCountingFormatter) outer).delegate();
        assertTrue(inner instanceof JulTrimFormatter, "trim is inserted underneath, not stacked on top");
    }

    @Test
    void installTrimRendering_calledAgainAfterCorrectlyLayered_doesNotReWrap() {
        handler = new FakePersistentHandler();
        logger = Logger.getLogger(name("stable"));
        logger.addHandler(handler);

        adapter.installTrimRendering(ALWAYS_ALLOW);
        adapter.installByteCounting();
        Formatter afterLayering = handler.getFormatter();

        adapter.installTrimRendering(ALWAYS_ALLOW);

        assertEquals(afterLayering, handler.getFormatter());
    }
}
