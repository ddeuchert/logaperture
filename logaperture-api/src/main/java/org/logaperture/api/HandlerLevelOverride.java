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
package org.logaperture.api;

import java.time.Instant;

/**
 * A recorded handler-level override — the {@link LevelOverride} counterpart
 * for {@code logctl handler <name> <level>} (doc/specs/
 * handler-floor-control.md "Data model"). Independent lifetime: nothing
 * about a {@link LevelOverride} creates, extends, or reverts one of these,
 * and vice versa (see the spec's "Independent lifetime" semantics note). The
 * handler's pre-LogAperture baseline is tracked separately, in a {@code
 * HandlerBaselineRegistry} — this record mirrors {@link LevelOverride}'s own
 * shape, which does the same for logger baselines.
 *
 * @param handlerRef the handler this override targets
 * @param level      the level applied
 * @param reason     human-readable justification; {@code null} if none was given
 * @param appliedAt  when this override was created
 * @param source     the control surface that created it, or the internal
 *                   source that reinstated it (e.g. {@code "resume"})
 * @param tier       the durability tier this override was set at
 * @param expiresAt  the absolute deadline this override reverts at; {@code
 *                   null} unless {@code tier} is {@link PersistenceTier#FOR}
 */
public record HandlerLevelOverride(
        HandlerRef handlerRef,
        Level level,
        String reason,
        Instant appliedAt,
        String source,
        PersistenceTier tier,
        Instant expiresAt) {

    public HandlerLevelOverride {
        if (handlerRef == null) {
            throw new IllegalArgumentException("handlerRef must not be null");
        }
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        if (appliedAt == null) {
            throw new IllegalArgumentException("appliedAt must not be null");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source must not be null or empty");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        if (tier == PersistenceTier.FOR) {
            if (expiresAt == null) {
                throw new IllegalArgumentException("tier FOR requires a non-null expiresAt");
            }
        } else if (expiresAt != null) {
            throw new IllegalArgumentException("expiresAt must be null unless tier is FOR");
        }
    }
}
