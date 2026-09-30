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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * DiagnosticLevel#WARN} if unset or unrecognized -- doc/specs/quieter-output.md Q7).
 *
 * <p>Every message LogAperture prints goes through here, one format, {@code [logaperture] LEVEL
 * message} -- no timestamp of its own, since every console and log already has one. It writes to
 * the {@code System.err} captured when this class loads (in {@code premain}), so a message is never
 * re-logged by a container that later wraps {@code System.err} -- WildFly files that as {@code ERROR
 * [stderr]} (Q7).
 */
public final class Diagnostics {

    private static final String LEVEL_PROPERTY = "logaperture.diagnostics.level";

    /** A throttled message is written at most once per this long, per key. */
    static final long THROTTLE_NANOS = 60_000_000_000L;

    private static volatile PrintStream target = System.err;
    private static volatile DiagnosticLevel threshold = levelFromSystemProperty();
    private static final Map<String, Long> lastThrottledAt = new ConcurrentHashMap<>();

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
        lastThrottledAt.clear();
    }

    /**
     * A once-at-startup line the operator should see at the default level -- the startup banner
     * (doc/specs/quieter-output.md Q3), or the handover of exported sticky settings: written at every
     * threshold but {@code ERROR}, with no level word.
     */
    public static void notice(String message) {
        if (threshold == DiagnosticLevel.ERROR) {
            return;
        }
        target.println("[logaperture] " + message);
    }

    /**
     * A warning about something that can happen on every log event -- a rule or storm evaluation
     * that threw -- written at most once a minute per {@code key}, so a broken rule can't flood the
     * console (doc/specs/quieter-output.md Q7).
     */
    public static void warnThrottled(String key, String message, Throwable cause) {
        long now = System.nanoTime();
        Long last = lastThrottledAt.get(key);
        if (last != null && now - last < THROTTLE_NANOS) {
            return;
        }
        if (last == null ? lastThrottledAt.putIfAbsent(key, now) != null : !lastThrottledAt.replace(key, last, now)) {
            return; // another thread wrote it just now
        }
        warn(message, cause);
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
        out.println("[logaperture] " + level + " " + message);
        if (cause != null) {
            cause.printStackTrace(out);
        }
    }

    /** The current threshold -- for tests. */
    static DiagnosticLevel threshold() {
        return threshold;
    }

    private static DiagnosticLevel levelFromSystemProperty() {
        String raw = System.getProperty(LEVEL_PROPERTY);
        if (raw == null) {
            return DiagnosticLevel.WARN;
        }
        try {
            return DiagnosticLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DiagnosticLevel.WARN;
        }
    }
}
