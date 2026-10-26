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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.LogManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * doc/specs/plain-jvm-jul.md #3: on a plain JVM, onReset fires when the application re-reads its
 * logging configuration; on WildFly and Quarkus it stays a no-op. Uses updateConfiguration with a
 * mapper that keeps every current value, so the test JVM's own JUL setup is left as it was.
 */
class JulConfigurationReloadTest {

    private static void reloadKeepingEverything() throws IOException {
        BiFunction<String, String, String> keepOld = (oldValue, newValue) -> oldValue;
        LogManager.getLogManager().updateConfiguration(key -> keepOld);
    }

    @Test
    void plainJvmAdapterRunsTheListenerOnReload() throws IOException {
        JulLoggingAdapter adapter = JulAdapterFactory.forPlainJvm();
        AtomicInteger runs = new AtomicInteger();
        adapter.onReset(runs::incrementAndGet);
        try {
            reloadKeepingEverything();
            assertEquals(1, runs.get());
        } finally {
            adapter.clearResetListener();
        }
        reloadKeepingEverything();
        assertEquals(1, runs.get(), "cleared, so a later reload does not run it");
    }

    @Test
    void aSecondListenerReplacesTheFirst() throws IOException {
        JulLoggingAdapter adapter = JulAdapterFactory.forPlainJvm();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        adapter.onReset(first::incrementAndGet);
        adapter.onReset(second::incrementAndGet);
        try {
            reloadKeepingEverything();
        } finally {
            adapter.clearResetListener();
        }
        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void containerAdapterIgnoresReloads() throws IOException {
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(HandlerNameResolver.NONE);
        AtomicInteger runs = new AtomicInteger();
        adapter.onReset(runs::incrementAndGet);
        reloadKeepingEverything();
        assertEquals(0, runs.get());
    }

    @Test
    void plainJvmAdapterNamesTheConsoleByType() {
        java.util.logging.ConsoleHandler console = new java.util.logging.ConsoleHandler();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("org.logaperture.test.reload");
        logger.addHandler(console);
        try {
            JulLoggingAdapter adapter = JulAdapterFactory.forPlainJvm();
            boolean named = adapter.knownHandlers().stream().anyMatch(ref -> ref.value().equals("console"));
            org.junit.jupiter.api.Assertions.assertTrue(named, "handlers: " + adapter.knownHandlers());
        } finally {
            logger.removeHandler(console);
            console.close();
        }
    }

    /** Named like the JDK type, so the resolver calls it "file"; the test JVM's root has no file handler. */
    private static final class FileHandler extends java.util.logging.StreamHandler {
    }

    /**
     * A reload replaces the JDK's handlers with new instances. Before the listener re-applies
     * overrides, "file" must resolve to the new handler, not the closed one.
     */
    @Test
    void afterAReloadTheNameResolvesToTheNewHandler() throws IOException {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("org.logaperture.test.replaced");
        logger.setUseParentHandlers(false);
        FileHandler before = new FileHandler();
        logger.addHandler(before);
        FileHandler after = new FileHandler();
        JulLoggingAdapter adapter = JulAdapterFactory.forPlainJvm();
        try {
            org.logaperture.api.HandlerRef file = new org.logaperture.api.HandlerRef("file");
            org.junit.jupiter.api.Assertions.assertTrue(adapter.realHandlers().contains(file), adapter.realHandlers().toString());
            Runnable reapplyDebug = () -> adapter.setHandlerLevel(file, org.logaperture.api.Level.DEBUG);
            adapter.onReset(reapplyDebug);

            logger.removeHandler(before); // what a reload does: close the old handler, attach a new one
            before.close();
            logger.addHandler(after);
            reloadKeepingEverything();

            assertEquals(java.util.logging.Level.FINE, after.getLevel(), "the override reached the new handler");
        } finally {
            adapter.clearResetListener();
            logger.removeHandler(after);
            after.close();
            logger.setUseParentHandlers(true);
        }
    }
}
