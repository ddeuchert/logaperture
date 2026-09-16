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
package org.logaperture.control.jmx;

import org.logaperture.api.SquelchedLogger;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link SquelchedLogger} — see {@link
 * LoggerInfoData} for the pattern's rationale. One of these names a logger
 * override that a {@code logctl handler <name> <stricter-level>} call is
 * about to newly silence (doc/specs/handler-floor-control.md "Squelch
 * warning", issue #16).
 */
public final class SquelchedLoggerData {

    private final String loggerName;
    private final String level;

    @ConstructorProperties({"loggerName", "level"})
    public SquelchedLoggerData(String loggerName, String level) {
        this.loggerName = loggerName;
        this.level = level;
    }

    public static SquelchedLoggerData from(SquelchedLogger squelched) {
        return new SquelchedLoggerData(squelched.loggerName(), squelched.level().name());
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getLevel() {
        return level;
    }
}
