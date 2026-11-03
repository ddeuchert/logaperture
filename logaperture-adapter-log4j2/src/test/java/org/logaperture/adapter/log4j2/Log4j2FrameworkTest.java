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

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;
import org.logaperture.core.spi.ContextHandle;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Log4j2FrameworkTest {

    @Test
    void ready_isTrueOnceAContextIsStarted_andOpenHandsBackItsHandle() {
        Log4j2Framework entry = new Log4j2Framework();
        LoggerContext context = Configurator.initialize("t", (String) null);
        try {
            assertTrue(entry.ready(), "a started context is ready");
            List<ContextHandle> handles = entry.open(() -> { });
            assertEquals(1, handles.stream().filter(h -> h.stableKey().startsWith("log4j2:")).count());
            ContextHandle handle = handles.get(0);
            assertEquals("log4j2", handle.displayName());
            assertEquals("log4j2:log4j2", handle.stableKey());
            assertEquals("Log4j 2", handle.adapter().backendInfo().name());
        } finally {
            context.stop();
        }
    }

    @Test
    void contextShutdown_callsTheCallbackOnce_andNotAfterClose() {
        Log4j2Framework entry = new Log4j2Framework();
        LoggerContext context = Configurator.initialize("t2", (String) null);
        AtomicInteger shutdowns = new AtomicInteger();
        entry.open(shutdowns::incrementAndGet);
        context.stop();
        assertEquals(1, shutdowns.get());

        Log4j2Framework closed = new Log4j2Framework();
        LoggerContext second = Configurator.initialize("t3", (String) null);
        AtomicInteger none = new AtomicInteger();
        closed.open(none::incrementAndGet);
        closed.close();
        second.stop();
        assertEquals(0, none.get(), "a closed entry's listener is inert");
    }

    @Test
    void ready_isFalseWhileNoContextIsStarted() {
        Log4j2Framework entry = new Log4j2Framework();
        Configurator.initialize("t4", (String) null);
        org.apache.logging.log4j.core.impl.Log4jContextFactory factory =
                (org.apache.logging.log4j.core.impl.Log4jContextFactory) org.apache.logging.log4j.LogManager.getFactory();
        assertTrue(entry.ready(), "a started context exists");
        for (LoggerContext started : factory.getSelector().getLoggerContexts()) {
            started.stop();
        }
        assertFalse(entry.ready(), "every context has stopped");
    }

    @Test
    void open_failsWhenTheContextStoppedBeforeItWasBound() {
        Log4j2Framework entry = new Log4j2Framework();
        LoggerContext context = Configurator.initialize("t5", (String) null);
        context.stop();
        // No started context is left: a bound instance with no handles would never rebind or release.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> entry.open(() -> { }));
    }
}
