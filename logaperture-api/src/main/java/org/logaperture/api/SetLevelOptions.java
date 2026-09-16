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
 * @param reason    propagated to the audit log; {@code null} if none given
 * @param expiresIn required, positive, and only meaningful when {@code
 *                  tier} is {@link PersistenceTier#FOR}; {@code null}
 *                  otherwise
 * @param tier      the durability tier (default {@link
 *                  PersistenceTier#SESSION})
 * @param confirmed whether a leading-star pattern {@code target} is
 *                  confirmed to apply (doc/specs/pattern-selection-semantics.md
 *                  "Confirmation and CLI behavior"); ignored for an
 *                  exact-name target, which carries none of a batch
 *                  mutation's risk regardless of tier
 */
public record SetLevelOptions(String reason, Duration expiresIn, PersistenceTier tier, boolean confirmed) {

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

    /** Defaults: no reason, {@code --session}, unconfirmed. */
    public static SetLevelOptions defaults() {
        return new SetLevelOptions(null, null, PersistenceTier.SESSION, false);
    }

    /** Defaults, but with a reason attached — the common case for a deliberate operator change. */
    public static SetLevelOptions withReason(String reason) {
        return new SetLevelOptions(reason, null, PersistenceTier.SESSION, false);
    }

    /** {@code --for <duration>}: no reason. */
    public static SetLevelOptions forDuration(Duration duration) {
        return new SetLevelOptions(null, duration, PersistenceTier.FOR, false);
    }

    /** {@code --sticky}: no reason. */
    public static SetLevelOptions sticky() {
        return new SetLevelOptions(null, null, PersistenceTier.STICKY, false);
    }

    /** A copy of this options value with {@code confirmed} set — the CLI's own preview-then-apply step. */
    public SetLevelOptions withConfirmed(boolean confirmed) {
        return new SetLevelOptions(reason, expiresIn, tier, confirmed);
    }
}
