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

import org.logaperture.api.DescendantLevel;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link DescendantLevel} (doc/specs/set-logger-force.md) -- see {@link
 * LoggerInfoData} for the pattern's rationale. {@code kind} is the enum's name: {@code OWN_LEVEL},
 * {@code FORCED_EARLIER} or {@code OPERATOR_OVERRIDE}.
 */
public final class DescendantLevelData {

    private final String loggerName;
    private final String level;
    private final String kind;

    @ConstructorProperties({"loggerName", "level", "kind"})
    public DescendantLevelData(String loggerName, String level, String kind) {
        this.loggerName = loggerName;
        this.level = level;
        this.kind = kind;
    }

    public static DescendantLevelData from(DescendantLevel descendant) {
        return new DescendantLevelData(descendant.loggerName(), descendant.level().name(), descendant.kind().name());
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getLevel() {
        return level;
    }

    public String getKind() {
        return kind;
    }
}
