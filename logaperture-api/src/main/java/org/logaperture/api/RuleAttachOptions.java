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
 * Options for attaching a {@link LogRule} — mirrors {@link
 * SetLevelOptions}'s shape and validation exactly, per doc/specs/
 * rule-pipeline-foundation.md "Persistence" ("a rule's tier/expiry follow
 * persistence.md's existing grammar... exactly").
 *
 * @param reason    propagated to the audit log; {@code null} if none given
 * @param expiresIn required, positive, and only meaningful when {@code
 *                  tier} is {@link PersistenceTier#FOR}; {@code null}
 *                  otherwise
 * @param tier      the durability tier (default {@link
 *                  PersistenceTier#SESSION})
 */
public record RuleAttachOptions(String reason, Duration expiresIn, PersistenceTier tier) {

    public RuleAttachOptions {
        Objects.requireNonNull(tier, "tier");
        if (tier == PersistenceTier.FOR) {
            if (expiresIn == null || expiresIn.isZero() || expiresIn.isNegative()) {
                throw new IllegalArgumentException("tier FOR requires a positive expiresIn");
            }
        } else if (expiresIn != null) {
            throw new IllegalArgumentException("expiresIn must be null unless tier is FOR");
        }
    }

    /** Defaults: no reason, {@code SESSION}. */
    public static RuleAttachOptions defaults() {
        return new RuleAttachOptions(null, null, PersistenceTier.SESSION);
    }

    /** {@code for <duration>}: no reason. */
    public static RuleAttachOptions forDuration(Duration duration) {
        return new RuleAttachOptions(null, duration, PersistenceTier.FOR);
    }

    /** {@code sticky}: no reason. */
    public static RuleAttachOptions sticky() {
        return new RuleAttachOptions(null, null, PersistenceTier.STICKY);
    }
}
