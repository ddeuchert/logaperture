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
package org.logaperture.adapter.jbosslogmanager;

import org.logaperture.api.Level;

/**
 * Maps between {@link org.logaperture.api.Level} and {@link
 * java.util.logging.Level} — see doc/specs/wildfly-support.md, "Level
 * mapping".
 *
 * <p><b>Writing is exact.</b> Each of LogAperture's seven levels maps to
 * exactly one JUL level.
 *
 * <p><b>Reading is lossy-but-defined.</b> JUL has levels LogAperture does
 * not model (notably {@code FINER} and {@code CONFIG}); so does JBoss
 * LogManager ({@code TRACE}=400, {@code DEBUG}=500, … all share intValues
 * with JUL levels). Read-back is resolved by {@link
 * java.util.logging.Level#intValue()} against the canonical thresholds:
 * {@code FINER} and {@code FINEST} both read as {@code TRACE}; {@code
 * CONFIG} reads as {@code INFO}. The adapter separately retains the real
 * captured {@code java.util.logging.Level} so a reset is exact even when
 * the display was an approximation.
 */
final class LevelMapper {

    private static final int SEVERE = java.util.logging.Level.SEVERE.intValue();   // 1000
    private static final int WARNING = java.util.logging.Level.WARNING.intValue(); // 900
    private static final int CONFIG = java.util.logging.Level.CONFIG.intValue();   // 700
    private static final int FINE = java.util.logging.Level.FINE.intValue();       // 500
    private static final int OFF = java.util.logging.Level.OFF.intValue();         // Integer.MAX_VALUE
    private static final int ALL = java.util.logging.Level.ALL.intValue();         // Integer.MIN_VALUE

    private LevelMapper() {
    }

    static java.util.logging.Level toJul(Level level) {
        return switch (level) {
            case ALL -> java.util.logging.Level.ALL;
            case TRACE -> java.util.logging.Level.FINEST;
            case DEBUG -> java.util.logging.Level.FINE;
            case INFO -> java.util.logging.Level.INFO;
            case WARN -> java.util.logging.Level.WARNING;
            case ERROR -> java.util.logging.Level.SEVERE;
            case OFF -> java.util.logging.Level.OFF;
        };
    }

    static Level toApi(java.util.logging.Level level) {
        return level == null ? null : fromIntValue(level.intValue());
    }

    /** Shared by {@link #toApi} and by {@code effectiveLevel}'s int-valued fallback. */
    static Level fromIntValue(int value) {
        if (value == OFF) {
            return Level.OFF;
        }
        if (value == ALL) {
            return Level.ALL;
        }
        if (value >= SEVERE) {
            return Level.ERROR;
        }
        if (value >= WARNING) {
            return Level.WARN;
        }
        if (value >= CONFIG) { // INFO (800) and CONFIG (700) both land here
            return Level.INFO;
        }
        if (value >= FINE) { // FINE (500), JBoss DEBUG (500)
            return Level.DEBUG;
        }
        return Level.TRACE; // FINER (400), FINEST (300), JBoss TRACE (400), anything above ALL
    }
}
