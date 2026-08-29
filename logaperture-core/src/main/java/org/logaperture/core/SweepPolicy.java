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
            System.err.println("[logaperture] ignoring non-numeric " + INTERVAL_PROPERTY + "='" + raw
                    + "', using 30s");
            return DEFAULT_INTERVAL;
        }
    }
}
