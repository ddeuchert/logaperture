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

import org.logaperture.api.LevelFloor;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link LevelFloor} (doc/specs/quarkus-support.md, "Min-level
 * honesty") -- see {@link LoggerInfoData} for the pattern's rationale.
 */
public final class LevelFloorData {

    private final String loggerName;
    private final String requested;
    private final String lowest;
    private final String hint;

    @ConstructorProperties({"loggerName", "requested", "lowest", "hint"})
    public LevelFloorData(String loggerName, String requested, String lowest, String hint) {
        this.loggerName = loggerName;
        this.requested = requested;
        this.lowest = lowest;
        this.hint = hint;
    }

    public static LevelFloorData from(LevelFloor floor) {
        return new LevelFloorData(floor.loggerName(), floor.requested().name(), floor.lowest().name(), floor.hint());
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getRequested() {
        return requested;
    }

    public String getLowest() {
        return lowest;
    }

    /** What lowers the floor, or {@code null} when the backend can't say. */
    public String getHint() {
        return hint;
    }
}
