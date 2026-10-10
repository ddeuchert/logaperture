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
package org.logaperture.container.wildfly;

import org.jboss.logmanager.handlers.ConsoleHandler;
import org.jboss.logmanager.handlers.FileHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.adapter.jul.HandlerNameResolver;
import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.api.HandlerRef;
import org.logaperture.core.DefaultHandlerGroupRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DEFAULT_HANDLERS}'s automatic pick against JBoss LogManager's real handler classes, the
 * ones WildFly's {@code <console-handler>} and {@code <file-handler>} create (issue #188). The
 * test JVM runs JBoss LogManager as its {@code LogManager} (see the pom). With no name resolver,
 * handlers are known only by identity token, so the pick rests on recognizing the console
 * handler by its class: the case that went wrong. The resolved case names them as WildFly would.
 */
class JBossHandlerDiagnosticsTest {

    private final Logger root = Logger.getLogger("");

    /** JBoss LogManager's own default console handler, detached so each test sets root up itself. */
    private Handler[] originalRootHandlers;

    @BeforeEach
    void detachRootHandlers() {
        originalRootHandlers = root.getHandlers();
        for (Handler handler : originalRootHandlers) {
            root.removeHandler(handler);
        }
    }

    @AfterEach
    void restoreRootHandlers() {
        for (Handler handler : originalRootHandlers) {
            root.addHandler(handler);
        }
    }

    @Test
    void jbossConsoleHandler_isAConsole_andAFileHandlerIsNot(@TempDir Path dir) throws Exception {
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(HandlerNameResolver.NONE);
        ConsoleHandler console = new ConsoleHandler();
        FileHandler file = new FileHandler(dir.resolve("server.log").toFile(), true);
        root.addHandler(file);
        root.addHandler(console);
        try {
            assertTrue(adapter.handlerDiagnostics(refOf(adapter, console)).isConsole());
            assertFalse(adapter.handlerDiagnostics(refOf(adapter, file)).isConsole());
        } finally {
            root.removeHandler(console);
            root.removeHandler(file);
            file.close();
        }
    }

    @Test
    void defaultHandlers_picksTheConsole_evenWhenTheFileIsAttachedToRootFirst(@TempDir Path dir) throws Exception {
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(HandlerNameResolver.NONE);
        ConsoleHandler console = new ConsoleHandler();
        FileHandler file = new FileHandler(dir.resolve("server.log").toFile(), true);
        root.addHandler(file);
        root.addHandler(console);
        try {
            List<HandlerRef> members = new DefaultHandlerGroupRegistry().members(adapter);

            assertEquals(List.of(refOf(adapter, console)), members);
        } finally {
            root.removeHandler(console);
            root.removeHandler(file);
            file.close();
        }
    }

    @Test
    void defaultHandlers_picksConsole_whenTheNamesResolve(@TempDir Path dir) throws Exception {
        ConsoleHandler console = new ConsoleHandler();
        FileHandler file = new FileHandler(dir.resolve("server.log").toFile(), true);
        HandlerNameResolver asWildFlyNamesThem = handlers -> Map.of(console, "CONSOLE", file, "FILE");
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(asWildFlyNamesThem);
        root.addHandler(file);
        root.addHandler(console);
        try {
            List<HandlerRef> members = new DefaultHandlerGroupRegistry().members(adapter);

            assertEquals(List.of(new HandlerRef("CONSOLE")), members);
        } finally {
            root.removeHandler(console);
            root.removeHandler(file);
            file.close();
        }
    }

    private static HandlerRef refOf(JulLoggingAdapter adapter, java.util.logging.Handler handler) {
        return adapter.realHandlers().stream()
                .filter(ref -> ref.equals(HandlerRef.anonymous(handler)))
                .findFirst().orElseThrow();
    }
}
