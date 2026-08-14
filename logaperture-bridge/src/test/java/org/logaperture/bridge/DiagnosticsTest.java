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
package org.logaperture.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @AfterEach
    void resetDiagnostics() {
        Diagnostics.resetToDefault();
    }

    @Test
    void atThresholdInfo_errorWarnInfoPrint_debugSuppressed() {
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.INFO);

        Diagnostics.error("boom");
        Diagnostics.warn("careful");
        Diagnostics.info("fyi");
        Diagnostics.debug("chatty");

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("ERROR boom"));
        assertTrue(output.contains("WARN careful"));
        assertTrue(output.contains("INFO fyi"));
        assertFalse(output.contains("chatty"));
    }

    @Test
    void thresholdDebug_everythingPrints() {
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.DEBUG);

        Diagnostics.debug("chatty");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("DEBUG chatty"));
    }

    @Test
    void thresholdError_onlyErrorPrints() {
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.ERROR);

        Diagnostics.error("boom");
        Diagnostics.warn("careful");

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("ERROR boom"));
        assertFalse(output.contains("careful"));
    }

    @Test
    void errorWithCause_printsStackTrace() {
        Diagnostics.configure(new PrintStream(captured, true, StandardCharsets.UTF_8), DiagnosticLevel.ERROR);

        Diagnostics.error("boom", new RuntimeException("root cause"));

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("root cause"));
    }
}
