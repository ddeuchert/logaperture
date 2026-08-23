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

import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.LevelControlOperations;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Wraps a {@link LevelControlOperations}, converting between the {@code
 * String}-based JMX boundary and {@code api} types. Any {@code
 * RuntimeException} thrown by the wrapped operations (a {@code
 * CapabilityDeniedException}, an adapter failure) is left to propagate --
 * JMX wraps these in a {@code RuntimeMBeanException} for the remote caller
 * automatically, satisfying "surfaces to the JMX caller as a failed
 * operation" (doc/specs/level-control.md's failure handling).
 */
public final class LevelControlMXBeanImpl implements LevelControlMXBean {

    private final LevelControlOperations operations;

    public LevelControlMXBeanImpl(LevelControlOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public List<LoggerInfoData> listLoggers(String filter) {
        return operations.listLoggers(filter).stream().map(LoggerInfoData::from).toList();
    }

    @Override
    public LevelOverrideData setLevel(String loggerName, String level, boolean includeChildren, String reason,
            String tier, long forSeconds) {
        Level parsedLevel = parseLevel(level);
        SetLevelOptions options = toOptions(includeChildren, reason, tier, forSeconds);
        var override = operations.setLevel(loggerName, parsedLevel, options);
        return LevelOverrideData.from(override);
    }

    @Override
    public void resetLevel(String loggerName) {
        operations.resetLevel(loggerName);
    }

    @Override
    public void resetAll() {
        operations.resetAll();
    }

    private static SetLevelOptions toOptions(boolean includeChildren, String reason, String tier, long forSeconds) {
        PersistenceTier parsedTier = parseTier(tier);
        Duration expiresIn = parsedTier == PersistenceTier.FOR ? Duration.ofSeconds(forSeconds) : null;
        return new SetLevelOptions(includeChildren, reason, expiresIn, parsedTier);
    }

    private static PersistenceTier parseTier(String tier) {
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        try {
            return PersistenceTier.valueOf(tier.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown tier: '" + tier + "' (expected one of "
                    + List.of(PersistenceTier.values()) + ")", e);
        }
    }

    private static Level parseLevel(String level) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        try {
            return Level.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown level: '" + level + "' (expected one of "
                    + List.of(Level.values()) + ")", e);
        }
    }
}
