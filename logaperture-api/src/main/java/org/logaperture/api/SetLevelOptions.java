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

import java.time.Duration;
import java.util.Objects;

/**
 * Options for {@code setLevel} — see doc/specs/level-control.md's
 * "Operations" section and doc/specs/persistence.md's "Operations" section
 * for {@code tier}.
 *
 * @param includeChildren also apply to loggers already known to be
 *                        descendants at call time (default {@code false})
 * @param reason          propagated to the audit log; {@code null} if none
 *                        given
 * @param expiresIn       required, positive, and only meaningful when
 *                        {@code tier} is {@link PersistenceTier#FOR}; {@code
 *                        null} otherwise
 * @param tier             the durability tier (default {@link
 *                        PersistenceTier#SESSION})
 */
public record SetLevelOptions(boolean includeChildren, String reason, Duration expiresIn, PersistenceTier tier) {

    public SetLevelOptions {
        Objects.requireNonNull(tier, "tier");
        if (tier == PersistenceTier.FOR) {
            if (expiresIn == null || expiresIn.isZero() || expiresIn.isNegative()) {
                throw new IllegalArgumentException("tier FOR requires a positive expiresIn");
            }
        } else if (expiresIn != null) {
            throw new IllegalArgumentException("expiresIn must be null unless tier is FOR");
        }
    }

    /** Defaults: no fan-out, no reason, {@code --session}. */
    public static SetLevelOptions defaults() {
        return new SetLevelOptions(false, null, null, PersistenceTier.SESSION);
    }

    /** Defaults, but with a reason attached — the common case for a deliberate operator change. */
    public static SetLevelOptions withReason(String reason) {
        return new SetLevelOptions(false, reason, null, PersistenceTier.SESSION);
    }

    /** {@code --for <duration>}: no fan-out, no reason. */
    public static SetLevelOptions forDuration(Duration duration) {
        return new SetLevelOptions(false, null, duration, PersistenceTier.FOR);
    }

    /** {@code --sticky}: no fan-out, no reason. */
    public static SetLevelOptions sticky() {
        return new SetLevelOptions(false, null, null, PersistenceTier.STICKY);
    }
}
