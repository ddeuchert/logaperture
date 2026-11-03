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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;

/**
 * {@link org.logaperture.api.Level} to and from Log4j 2's {@link Level}. Log4j has one level
 * LogAperture does not: {@code FATAL} reads back as {@code ERROR} (the nearest, and the one level
 * name {@code logctl} would offer), and a custom level reads back as the nearest standard one by
 * its {@code intLevel}.
 */
final class LevelMapper {

    private LevelMapper() {
    }

    static Level toLog4j(org.logaperture.api.Level level) {
        return switch (level) {
            case ALL -> Level.ALL;
            case TRACE -> Level.TRACE;
            case DEBUG -> Level.DEBUG;
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
            case OFF -> Level.OFF;
        };
    }

    static org.logaperture.api.Level toApi(Level level) {
        if (level == null) {
            return null;
        }
        int value = level.intLevel();
        if (value <= 0) {
            return org.logaperture.api.Level.OFF;
        }
        if (value <= Level.ERROR.intLevel()) {
            return org.logaperture.api.Level.ERROR; // FATAL and ERROR
        }
        if (value <= Level.WARN.intLevel()) {
            return org.logaperture.api.Level.WARN;
        }
        if (value <= Level.INFO.intLevel()) {
            return org.logaperture.api.Level.INFO;
        }
        if (value <= Level.DEBUG.intLevel()) {
            return org.logaperture.api.Level.DEBUG;
        }
        if (value <= Level.TRACE.intLevel()) {
            return org.logaperture.api.Level.TRACE;
        }
        return org.logaperture.api.Level.ALL;
    }
}
