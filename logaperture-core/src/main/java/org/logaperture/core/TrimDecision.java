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
 * The render-stage decision {@link GateVerdict#trim()} carries -- doc/specs/
 * trim-rule.md "Evaluation". Computed once, at gate time, from whichever
 * effective {@link org.logaperture.api.Trim} rule matched and was most
 * restrictive (fewest {@code frames}, doc/specs/filtering-epic.md
 * "Evaluation"); an adapter's render-stage {@code Formatter} wrap applies it
 * without re-evaluating any matcher.
 *
 * @param matchedRuleId  the winning {@link org.logaperture.api.Trim} rule's
 *                        id -- for hit-counting/audit-adjacent bookkeeping
 *                        only, never inspected by the render wrap itself
 * @param frames          top frames to keep, per {@link
 *                        org.logaperture.api.Trim#frames()}; {@code 0} is
 *                        the bare one-liner
 * @param collapseCauses  whether the cause chain is removed entirely rather
 *                        than reduced to one-liners each
 */
public record TrimDecision(String matchedRuleId, int frames, boolean collapseCauses) {
}
