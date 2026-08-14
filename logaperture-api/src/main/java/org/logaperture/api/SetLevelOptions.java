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

/**
 * Options for {@code setLevel} — see doc/specs/level-control.md's
 * "Operations" section.
 *
 * @param includeChildren also apply to loggers already known to be
 *                        descendants at call time (default {@code false})
 * @param reason          propagated to the audit log; {@code null} if none
 *                        given
 * @param expiresIn       reserved for Feature 2's {@code --for} tier
 *                        (doc/logaperture-spec.md §6.1). Carried on this
 *                        type but not read by any code in this slice — the
 *                        field exists now so Feature 2 is additive, not a
 *                        breaking change to this type.
 */
public record SetLevelOptions(boolean includeChildren, String reason, Duration expiresIn) {

    /** Defaults: no fan-out, no reason, no expiry (this slice's only supported tier, {@code --session}). */
    public static SetLevelOptions defaults() {
        return new SetLevelOptions(false, null, null);
    }

    /** Defaults, but with a reason attached — the common case for a deliberate operator change. */
    public static SetLevelOptions withReason(String reason) {
        return new SetLevelOptions(false, reason, null);
    }
}
