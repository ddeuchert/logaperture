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

import org.logaperture.api.PersistenceTier;

import java.time.Instant;
import java.util.Objects;

/**
 * A storm-detection setting that outlives the JVM: one made with {@code for <duration>} or {@code
 * sticky} -- doc/specs/storm-detection-toggle.md "Slice 2: timed and sticky toggles". At most one
 * is saved, in the state file's {@code stormDetection:} entry; a {@code session} setting is never
 * saved.
 *
 * @param enabled   the position set
 * @param tier      {@link PersistenceTier#FOR} or {@link PersistenceTier#STICKY}
 * @param expiresAt when a {@code FOR} setting switches to the other position; {@code null} for {@code STICKY}
 * @param reason    the {@code --reason}, or {@code null}
 * @param appliedAt when it was set
 * @param source    who set it, as in an audit record ({@code jmx})
 */
public record StormDetectionSetting(boolean enabled, PersistenceTier tier, Instant expiresAt, String reason,
        Instant appliedAt, String source) {

    public StormDetectionSetting {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(source, "source");
        if (tier == PersistenceTier.SESSION) {
            throw new IllegalArgumentException("a session setting is never saved");
        }
        if ((tier == PersistenceTier.FOR) != (expiresAt != null)) {
            throw new IllegalArgumentException("expiresAt is set exactly when the tier is FOR");
        }
    }
}
