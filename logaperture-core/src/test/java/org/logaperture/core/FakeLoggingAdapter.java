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

import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A hand-written {@link LoggingAdapter} test double, with no Logback or
 * agent involvement — this is what proves {@code core} is genuinely
 * framework-agnostic, per the implementation plan's build sequencing.
 *
 * <p>Models a simple dot-hierarchy, mimicking Logback's own resolution:
 * {@code effectiveLevel} walks up from the queried name to {@code ROOT},
 * returning the nearest explicit level.
 */
final class FakeLoggingAdapter implements LoggingAdapter {

    private final Map<String, Level> explicitLevels = new LinkedHashMap<>();
    private final Set<String> knownNames = new LinkedHashSet<>();
    private String throwOnApplyFor;

    FakeLoggingAdapter(Level rootLevel) {
        knownNames.add("ROOT");
        explicitLevels.put("ROOT", rootLevel);
    }

    void addKnownLogger(String name) {
        knownNames.add(name);
    }

    void setConfiguredLevel(String name, Level level) {
        knownNames.add(name);
        explicitLevels.put(name, level);
    }

    /** Makes the next {@link #applyLevel} call for this logger throw, to exercise chaos-case behavior. */
    void throwOnApply(String loggerName) {
        this.throwOnApplyFor = loggerName;
    }

    @Override
    public List<String> knownLoggerNames() {
        return List.copyOf(knownNames);
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        knownNames.add(loggerName); // matches Logback's getLogger()-creates-as-side-effect behavior
        return Optional.ofNullable(explicitLevels.get(loggerName));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        String name = loggerName;
        while (true) {
            Level explicit = explicitLevels.get(name);
            if (explicit != null) {
                return explicit;
            }
            int dot = name.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            name = name.substring(0, dot);
        }
        return explicitLevels.get("ROOT");
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        knownNames.add(loggerName);
        if (loggerName.equals(throwOnApplyFor)) {
            throwOnApplyFor = null; // one-shot
            throw new RuntimeException("simulated adapter failure for " + loggerName);
        }
        if (level == null) {
            explicitLevels.remove(loggerName);
        } else {
            explicitLevels.put(loggerName, level);
        }
    }
}
