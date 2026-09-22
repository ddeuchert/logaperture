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

import org.logaperture.api.RuleResetOutcome;

import java.beans.ConstructorProperties;
import java.util.List;

/** MXBean-friendly mirror of {@link RuleResetOutcome} — same shape as {@link ResetOutcomeData}, keyed by rule id. */
public final class RuleResetOutcomeData {

    private final List<String> removedIds;
    private final List<String> skippedStickyIds;

    @ConstructorProperties({"removedIds", "skippedStickyIds"})
    public RuleResetOutcomeData(List<String> removedIds, List<String> skippedStickyIds) {
        this.removedIds = removedIds;
        this.skippedStickyIds = skippedStickyIds;
    }

    public static RuleResetOutcomeData from(RuleResetOutcome outcome) {
        return new RuleResetOutcomeData(outcome.removedIds(), outcome.skippedStickyIds());
    }

    public List<String> getRemovedIds() {
        return removedIds;
    }

    public List<String> getSkippedStickyIds() {
        return skippedStickyIds;
    }
}
