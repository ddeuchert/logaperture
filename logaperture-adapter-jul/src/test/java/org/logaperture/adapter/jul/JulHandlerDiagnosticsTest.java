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
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isConsole} is what {@code DEFAULT_HANDLERS}'s automatic pick ranks root handlers by
 * (doc/specs/handler-floor-control.md "Deterministic initial-membership rule"). WildFly's console
 * handler is JBoss LogManager's own {@code ConsoleHandler}, not JUL's, so it has to count too
 * (issue #188). The real JBoss class is checked in {@code logaperture-container-wildfly}'s
 * {@code JBossHandlerDiagnosticsTest}.
 */
class JulHandlerDiagnosticsTest {

    @Test
    void isConsole_julConsoleHandler() {
        ConsoleHandler console = new ConsoleHandler();
        try {
            assertTrue(JulHandlerDiagnostics.of(console).isConsole());
        } finally {
            console.close();
        }
    }

    @Test
    void isConsole_aConsoleHandlerThatIsNotJuls() {
        assertTrue(JulHandlerDiagnostics.of(new org.logaperture.adapter.jul.standin.ConsoleHandler()).isConsole());
    }

    @Test
    void isConsole_aSubclassOfAConsoleHandler() {
        assertTrue(JulHandlerDiagnostics.of(new org.logaperture.adapter.jul.standin.ConsoleHandler() { }).isConsole());
    }

    @Test
    void isConsole_notForAnotherHandler() {
        assertFalse(JulHandlerDiagnostics.of(new StreamHandler()).isConsole());
    }

    /** doc/specs/plain-jvm-jul.md #5: the JDK FileHandler persists, but its file is unknown. */
    @Test
    void jdkFileHandler_isPersistentWithoutAKnownFile(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        FileHandler file = new FileHandler(dir.resolve("app%u.log").toString());
        try {
            org.logaperture.api.HandlerDiagnostics diagnostics = JulHandlerDiagnostics.of(file);
            assertTrue(diagnostics.isPersistent(), "top counts it");
            assertFalse(diagnostics.hasKnownFile(), "doctor's file checks leave it alone");
            assertTrue(diagnostics.fileWithoutPath());
        } finally {
            file.close();
        }
    }

    @Test
    void streamHandler_isNotPersistent() {
        assertFalse(JulHandlerDiagnostics.of(new StreamHandler()).isPersistent());
    }
}
