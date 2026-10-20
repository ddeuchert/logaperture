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
package org.logaperture.core;

import org.logaperture.bridge.Diagnostics;
import java.time.Duration;

/**
 * How often a composition root runs its expiry + verification sweep. The
 * default is 30 seconds; {@code -Dlogaperture.sweep.seconds=<n>} overrides
 * it (clamped to 1..3600). The knob exists mainly so an operator can tighten
 * the window in which a management-console logging change sits before the
 * verification sweep corrects it, and so integration tests do not have to
 * wait 30s.
 */
public final class SweepPolicy {

    /** {@value}. */
    public static final String INTERVAL_PROPERTY = "logaperture.sweep.seconds";

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);
    private static final long MIN_SECONDS = 1;
    private static final long MAX_SECONDS = 3600;

    private SweepPolicy() {
    }

    /**
     * A short burst of fast verification sweeps right after a container installs
     * (doc/specs/quarkus-support.md Q2). A framework that finishes configuring logging after our
     * install replaces what we put on its handlers, and the periodic interval would leave that
     * unrepaired for up to 30 seconds. Sweeps are idempotent, so extra ticks only cost time.
     *
     * @param interval how often to sweep during the window
     * @param length   how long after install the window stays open
     */
    public record StartupWindow(Duration interval, Duration length) {
        public StartupWindow {
            if (interval.isZero() || interval.isNegative() || length.isNegative()) {
                throw new IllegalArgumentException("a startup window needs a positive interval and a non-negative length");
            }
        }
    }

    /** The window the Quarkus container switches on: every {@value #STARTUP_INTERVAL_MILLIS} ms for 15 s. */
    public static StartupWindow startupWindow() {
        return new StartupWindow(Duration.ofMillis(STARTUP_INTERVAL_MILLIS), Duration.ofSeconds(15));
    }

    private static final long STARTUP_INTERVAL_MILLIS = 250;

    /** The configured sweep interval — the {@code logaperture.sweep.seconds} property, or 30s. */
    public static Duration interval() {
        String raw = System.getProperty(INTERVAL_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return Duration.ofSeconds(Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds)));
        } catch (NumberFormatException e) {
            Diagnostics.warn("ignoring non-numeric " + INTERVAL_PROPERTY + "='" + raw
                    + "', using 30s");
            return DEFAULT_INTERVAL;
        }
    }
}
