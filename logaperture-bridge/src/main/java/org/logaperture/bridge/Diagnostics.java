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

import java.io.PrintStream;
import java.time.Instant;

/**
 * The agent's own minimal, dependency-free diagnostic writer
 * (doc/logaperture-spec.md §4.5). The agent cannot use the target
 * application's logging framework for its own output — that's a
 * re-entrancy loop waiting to happen — so this is deliberately a small,
 * standalone facade with zero third-party dependencies (see {@code
 * logaperture-bridge}'s module description).
 *
 * <p>Every failure path in {@code logaperture-core}'s "ordinary failure
 * handling" (doc/specs/level-control.md "Failure handling") funnels through
 * here: a caught exception during install or a mutating operation gets
 * logged via this class, never allowed to propagate into the target
 * application.
 *
 * <p>Threshold is controlled by {@code -Dlogaperture.diagnostics.level=},
 * read once at class-init time (case-insensitive, defaults to {@link
 * DiagnosticLevel#INFO} if unset or unrecognized).
 */
public final class Diagnostics {

    private static final String LEVEL_PROPERTY = "logaperture.diagnostics.level";

    private static volatile PrintStream target = System.err;
    private static volatile DiagnosticLevel threshold = levelFromSystemProperty();

    private Diagnostics() {
    }

    /**
     * Reconfigures the sink and threshold. Exposed for tests; production
     * code should rely on the {@code -Dlogaperture.diagnostics.level=}
     * default rather than calling this.
     */
    public static void configure(PrintStream newTarget, DiagnosticLevel newThreshold) {
        target = newTarget;
        threshold = newThreshold;
    }

    /** Restores the default sink ({@code System.err}) and the system-property-derived threshold. */
    public static void resetToDefault() {
        target = System.err;
        threshold = levelFromSystemProperty();
    }

    public static void error(String message) {
        error(message, null);
    }

    public static void error(String message, Throwable cause) {
        write(DiagnosticLevel.ERROR, message, cause);
    }

    public static void warn(String message) {
        warn(message, null);
    }

    public static void warn(String message, Throwable cause) {
        write(DiagnosticLevel.WARN, message, cause);
    }

    public static void info(String message) {
        write(DiagnosticLevel.INFO, message, null);
    }

    public static void debug(String message) {
        write(DiagnosticLevel.DEBUG, message, null);
    }

    private static void write(DiagnosticLevel level, String message, Throwable cause) {
        if (level.ordinal() > threshold.ordinal()) {
            return;
        }
        PrintStream out = target;
        out.println("[logaperture] " + Instant.now() + " " + level + " " + message);
        if (cause != null) {
            cause.printStackTrace(out);
        }
    }

    private static DiagnosticLevel levelFromSystemProperty() {
        String raw = System.getProperty(LEVEL_PROPERTY);
        if (raw == null) {
            return DiagnosticLevel.INFO;
        }
        try {
            return DiagnosticLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DiagnosticLevel.INFO;
        }
    }
}
