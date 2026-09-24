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
 * How long a container holds back its <em>handler-level</em> installs (trim
 * rendering, top's byte counting, storm detection, the rule pipeline) after
 * the readiness gate passes. Default 20 seconds;
 * {@code -Dlogaperture.handlerInstallDelaySeconds=<n>} overrides it
 * ({@code 0} = no deferral; above 600 is clamped to 600; a negative or non-numeric value falls back
 * to the default, with a message -- never silently to 0).
 *
 * <p>doc/specs/wildfly-deferred-handler-install.md "What triggers phase 2,
 * and the floor" (D2, D6): installing any of those on the JBoss LogManager's
 * boot handlers during {@code premain} aborted a real WildFly launch, and 20s
 * is the only delay observed to boot cleanly.
 */
public final class HandlerInstallPolicy {

    /** {@value}. */
    public static final String DELAY_PROPERTY = "logaperture.handlerInstallDelaySeconds";

    private static final Duration DEFAULT_DELAY = Duration.ofSeconds(20);
    private static final long MIN_SECONDS = 0;
    private static final long MAX_SECONDS = 600;

    private HandlerInstallPolicy() {
    }

    /** The configured delay — the {@code logaperture.handlerInstallDelaySeconds} property, or 20s. */
    public static Duration delay() {
        String raw = System.getProperty(DELAY_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DELAY;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds < MIN_SECONDS) {
                // Not clamped to 0: 0 switches the safety deferral off, which a mistyped negative
                // must never do silently.
                System.err.println("[logaperture] ignoring negative " + DELAY_PROPERTY + "='" + raw
                        + "', using " + DEFAULT_DELAY.toSeconds() + "s");
                return DEFAULT_DELAY;
            }
            if (seconds > MAX_SECONDS) {
                System.err.println("[logaperture] " + DELAY_PROPERTY + "='" + raw + "' is above the maximum, using "
                        + MAX_SECONDS + "s");
                return Duration.ofSeconds(MAX_SECONDS);
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            System.err.println("[logaperture] ignoring non-numeric " + DELAY_PROPERTY + "='" + raw
                    + "', using " + DEFAULT_DELAY.toSeconds() + "s");
            return DEFAULT_DELAY;
        }
    }
}
