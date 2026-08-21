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

import java.time.Instant;

/**
 * One audit entry — fields per doc/logaperture-spec.md §9.7, scoped down
 * per doc/specs/level-control.md (no hash-chaining, no syslog/Event Log
 * mirroring in this slice; see {@link StderrAuditLog}).
 *
 * @param previousValue rendered as a display string ({@code Level.toString()}
 *                       or {@code "<inherited>"}), not the {@code Level}
 *                       type itself, so the record stays trivially loggable
 * @param newValue      same rendering convention as {@code previousValue}
 * @param action        {@link Action#MUTATION} for {@code setLevel},
 *                      {@link Action#REVERSION} for {@code resetLevel}/
 *                      {@code resetAll} — "records the revert as well as
 *                      the change" (§9.7)
 */
public record AuditRecord(
        Instant timestamp,
        String principal,
        String source,
        String loggerName,
        String previousValue,
        String newValue,
        String reason,
        Action action) {

    public enum Action {
        MUTATION,
        REVERSION
    }
}
