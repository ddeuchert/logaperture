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

import org.logaperture.api.LogRule;

import java.util.List;

/**
 * An immutable snapshot of every attached rule, swapped atomically on every
 * attach/detach (§7.3, doc/specs/rule-pipeline-foundation.md "Evaluation").
 * This slice's own gate filter never evaluates a plan's contents — there is
 * no concrete action yet to decide a verdict with (see doc/specs/
 * rule-pipeline-foundation.md "Relationship to the storm-detection
 * filter") — but the swap-a-whole-immutable-plan discipline is built and
 * tested here so #72/#34 add only the evaluation logic, not the seam.
 */
public record RulePlan(List<LogRule> rules) {

    private static final RulePlan EMPTY = new RulePlan(List.of());

    public RulePlan {
        rules = List.copyOf(rules);
    }

    public static RulePlan empty() {
        return EMPTY;
    }
}
