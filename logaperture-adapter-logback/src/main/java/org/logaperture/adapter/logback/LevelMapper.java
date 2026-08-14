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
package org.logaperture.adapter.logback;

/**
 * Bidirectional, exhaustive mapping between {@link org.logaperture.api.Level}
 * and {@link ch.qos.logback.classic.Level}. Both enums cover the same seven
 * values, so every branch below should be unreachable in the default case —
 * defensive, not decorative.
 */
final class LevelMapper {

    private LevelMapper() {
    }

    static ch.qos.logback.classic.Level toLogback(org.logaperture.api.Level level) {
        return switch (level) {
            case ALL -> ch.qos.logback.classic.Level.ALL;
            case TRACE -> ch.qos.logback.classic.Level.TRACE;
            case DEBUG -> ch.qos.logback.classic.Level.DEBUG;
            case INFO -> ch.qos.logback.classic.Level.INFO;
            case WARN -> ch.qos.logback.classic.Level.WARN;
            case ERROR -> ch.qos.logback.classic.Level.ERROR;
            case OFF -> ch.qos.logback.classic.Level.OFF;
        };
    }

    static org.logaperture.api.Level toApi(ch.qos.logback.classic.Level level) {
        if (level == null) {
            return null;
        }
        // Switch on levelInt, not the class instance or its toString() --
        // Level isn't a Java enum, so reference/string comparison would work
        // too, but the int constants are the part Logback documents as stable.
        return switch (level.levelInt) {
            case ch.qos.logback.classic.Level.ALL_INT -> org.logaperture.api.Level.ALL;
            case ch.qos.logback.classic.Level.TRACE_INT -> org.logaperture.api.Level.TRACE;
            case ch.qos.logback.classic.Level.DEBUG_INT -> org.logaperture.api.Level.DEBUG;
            case ch.qos.logback.classic.Level.INFO_INT -> org.logaperture.api.Level.INFO;
            case ch.qos.logback.classic.Level.WARN_INT -> org.logaperture.api.Level.WARN;
            case ch.qos.logback.classic.Level.ERROR_INT -> org.logaperture.api.Level.ERROR;
            case ch.qos.logback.classic.Level.OFF_INT -> org.logaperture.api.Level.OFF;
            default -> throw new IllegalArgumentException("unmapped Logback level: " + level);
        };
    }
}
