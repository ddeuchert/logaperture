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

/**
 * The result of {@link RuleGate#evaluate} -- doc/specs/drop-rule.md
 * "Evaluation", doc/specs/trim-rule.md "Evaluation". {@code deny() == true}
 * means a {@link org.logaperture.api.Drop} matched and this event is not a
 * kept {@code sampleFull} sample; an adapter's gate {@code Filter} returns
 * {@code isLoggable() == !deny()}. Hit counting and the {@code sampleFull}
 * decision have already happened by the time this is returned -- there is
 * nothing left for the adapter's {@code Filter} to do but obey the verdict.
 *
 * <p>{@code trim} carries the render-stage decision computed at the same
 * evaluation pass (doc/specs/trim-rule.md "Evaluation": "the gate stage
 * decides what to trim, the render stage does the trimming") -- {@code null}
 * when no {@link org.logaperture.api.Trim} rule matched, or when {@code deny
 * == true} (a dropped event is never trimmed). An adapter's render-stage
 * {@code Formatter} wrap reads this field off the same cached verdict its
 * sibling {@code Filter} already computed for this record.
 */
public record GateVerdict(boolean deny, String matchedRuleId, TrimDecision trim) {

    private static final GateVerdict ALLOW = new GateVerdict(false, null, null);

    /** No effective {@link org.logaperture.api.Drop} matched, or a match was let through as a {@code sampleFull} sample -- also true when no {@link org.logaperture.api.Trim} matched either. */
    public static GateVerdict allow() {
        return ALLOW;
    }

    /** A {@link org.logaperture.api.Drop} matched and this event is not a kept sample. */
    public static GateVerdict deny(String matchedRuleId) {
        return new GateVerdict(true, matchedRuleId, null);
    }

    /** The event survives the gate, and the most restrictive matching {@link org.logaperture.api.Trim} rule computed {@code trim} for it to carry to the render stage. */
    public static GateVerdict allowWithTrim(TrimDecision trim) {
        return new GateVerdict(false, null, trim);
    }
}
