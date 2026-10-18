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

import io.quarkus.bootstrap.logging.QuarkusDelayedHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.core.StormObservation;
import org.logaperture.core.StormObserver;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/quarkus-support.md Q3: Quarkus puts its console and file handlers inside one
 * {@code QuarkusDelayedHandler} on the root logger. The adapter looks through it when it
 * catalogues handlers and keeps it as the place filters go.
 */
class JulTransparentHandlerTest {

    private static class Inner extends Handler {
        Inner(boolean file) {
            setFormatter(new SimpleFormatter());
            this.file = file;
        }

        final boolean file;

        /** Makes a handler persistent for {@link JulHandlerDiagnostics}, like the fake in the storm tests. */
        public File getFile() {
            return file ? new File("fake.log") : null;
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

    private Logger root;
    private QuarkusDelayedHandler delayed;
    private Inner console;
    private Inner file;
    private JulLoggingAdapter adapter;
    private java.util.logging.Level rootLevel;

    @BeforeEach
    void setUp() {
        root = Logger.getLogger("");
        rootLevel = root.getLevel();
        delayed = new QuarkusDelayedHandler();
        console = new Inner(false);
        file = new Inner(true);
        delayed.addHandler(console);
        delayed.addHandler(file);
        root.addHandler(delayed);
        adapter = new JulLoggingAdapter();
    }

    @AfterEach
    void tearDown() {
        root.removeHandler(delayed);
        root.setLevel(rootLevel);
    }

    private List<Handler> catalogued() {
        return adapter.realHandlers().stream().map(adapter::handlerFor).toList();
    }

    @Test
    void realHandlers_listsTheChildrenInsteadOfTheDelayedHandler() {
        List<Handler> handlers = catalogued();
        assertTrue(handlers.stream().anyMatch(h -> h == console));
        assertTrue(handlers.stream().anyMatch(h -> h == file));
        assertFalse(handlers.stream().anyMatch(h -> h == delayed));
    }

    @Test
    void handlersOnRoot_areTheChildren() {
        List<HandlerRef> onRoot = adapter.handlersOnRoot();
        assertEquals(2, onRoot.stream().filter(r -> {
            Handler h = adapter.handlerFor(r);
            return h == console || h == file;
        }).count());
        assertFalse(onRoot.stream().anyMatch(r -> adapter.handlerFor(r) == delayed));
    }

    @Test
    void handlerLevel_canBeSetOnAChild() {
        HandlerRef consoleRef = adapter.realHandlers().stream()
                .filter(r -> adapter.handlerFor(r) == console).findFirst().orElseThrow();
        adapter.setHandlerLevel(consoleRef, Level.WARN);
        assertEquals(java.util.logging.Level.WARNING, console.getLevel());
    }

    @Test
    void handlerFloorsBelow_namesAChildThatBlocks() {
        console.setLevel(java.util.logging.Level.INFO);
        String loggerName = "tq." + UUID.randomUUID();
        List<HandlerFloor> floors = adapter.handlerFloorsBelow(loggerName, Level.DEBUG);
        // other handlers on the test JVM's root (JUL's own console) may block too; ours must be named
        HandlerFloor ours = floors.stream().filter(f -> adapter.handlerFor(f.handlerRef()) == console)
                .findFirst().orElseThrow();
        assertEquals(Level.INFO, ours.currentLevel());
        assertFalse(floors.stream().anyMatch(f -> adapter.handlerFor(f.handlerRef()) == delayed));
    }

    @Test
    void installStormDetection_filtersTheDelayedHandlerOnce_notTheChildren() {
        StormObserver observer = new StormObserver() {
            @Override
            public void observe(StormObservation observation) {
            }
        };
        adapter.installStormDetection(observer);

        assertInstanceOf(JulStormFilter.class, delayed.getFilter());
        assertNull(console.getFilter());
        assertNull(file.getFilter());
    }

    @Test
    void installByteCounting_wrapsThePersistentChild() {
        adapter.installByteCounting();
        assertInstanceOf(ByteCountingFormatter.class, file.getFormatter());
        assertFalse(console.getFormatter() instanceof ByteCountingFormatter, "console-only is never counted");
    }

    @Test
    void beforeActivation_theDelayedHandlerHasNoChildren_andNothingIsCatalogued() {
        root.removeHandler(delayed);
        QuarkusDelayedHandler empty = new QuarkusDelayedHandler();
        root.addHandler(empty);
        try {
            assertTrue(adapter.realHandlers().stream().noneMatch(r -> adapter.handlerFor(r) == empty));
        } finally {
            root.removeHandler(empty);
            root.addHandler(delayed);
        }
    }
}
