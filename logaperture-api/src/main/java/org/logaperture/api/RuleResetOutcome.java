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

import java.util.List;
import java.util.Objects;

/**
 * {@code reset rules}' result — mirrors {@link ResetOutcome}'s shape
 * exactly, keyed by rule id instead of logger name (doc/specs/
 * rule-pipeline-foundation.md "Command surface").
 *
 * @param removedIds       every rule id actually removed by this call
 * @param skippedStickyIds rule ids left in place because they're {@code
 *                         STICKY} and {@code includeSticky} wasn't passed —
 *                         always empty for a single-id {@code reset rule},
 *                         which refuses outright instead (mirrors {@link
 *                         ResetOutcome}'s Decision #1)
 */
public record RuleResetOutcome(List<String> removedIds, List<String> skippedStickyIds) {

    public RuleResetOutcome {
        Objects.requireNonNull(removedIds, "removedIds");
        Objects.requireNonNull(skippedStickyIds, "skippedStickyIds");
        removedIds = List.copyOf(removedIds);
        skippedStickyIds = List.copyOf(skippedStickyIds);
    }

    private static final RuleResetOutcome NOTHING_RESET = new RuleResetOutcome(List.of(), List.of());

    public static RuleResetOutcome nothingReset() {
        return NOTHING_RESET;
    }
}
