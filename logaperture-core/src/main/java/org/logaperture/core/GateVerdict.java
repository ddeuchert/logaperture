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
 * "Evaluation". {@code deny() == true} means a {@link org.logaperture.api.Drop}
 * matched and this event is not a kept {@code sampleFull} sample; an
 * adapter's gate {@code Filter} returns {@code isLoggable() == !deny()}.
 * Hit counting and the {@code sampleFull} decision have already happened by
 * the time this is returned -- there is nothing left for the adapter to do
 * but obey the verdict.
 */
public record GateVerdict(boolean deny, String matchedRuleId) {

    private static final GateVerdict ALLOW = new GateVerdict(false, null);

    /** No effective rule matched, or a match was let through as a {@code sampleFull} sample. */
    public static GateVerdict allow() {
        return ALLOW;
    }

    /** A {@link org.logaperture.api.Drop} matched and this event is not a kept sample. */
    public static GateVerdict deny(String matchedRuleId) {
        return new GateVerdict(true, matchedRuleId);
    }
}
