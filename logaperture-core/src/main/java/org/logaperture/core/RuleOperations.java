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

import org.logaperture.api.RuleResetOutcome;

import java.util.List;
import java.util.Optional;

/**
 * {@code logctl list rules}/{@code reset rule}/{@code reset rules}'s public
 * contract — the {@link StormOperations}/{@link TopOperations} counterpart
 * for the rule pipeline (doc/specs/rule-pipeline-foundation.md "Command
 * surface"). No {@code attach} here: this slice has no concrete rule type
 * to attach yet (see that spec's "Explicitly out of scope") — {@link
 * RuleService#attach} is called directly by whichever issue (#72's {@code
 * Drop}, #34's {@code Trim}) adds a CLI verb for it.
 */
public interface RuleOperations {

    /**
     * Every attached rule, across every logger, each tagged with its owning
     * context — see {@link RuleView}. {@link LogRule} is an interface with
     * no {@code context} field of its own (unlike {@code Storm}/{@code
     * LoggerByteCount}), so this wrapper carries it instead.
     */
    List<RuleView> listRules();

    /**
     * {@code reset rule <id>} — a single named id is "one specific thing":
     * refuses if it's {@code STICKY} and {@code includeSticky} wasn't
     * passed. Empty if no rule with this id exists (a no-op, not an error).
     * Returns the removed rule tagged with the context it was actually
     * removed from, since ids are only unique per context (doc/specs/
     * rule-pipeline-foundation.md "Rule identity").
     */
    Optional<RuleView> resetRule(String id, boolean includeSticky);

    /** {@code reset rules} — bulk, skip-and-report shape. */
    RuleResetOutcome resetAllRules(boolean includeSticky);

    /**
     * The rules attached directly to {@code loggerName} — {@code reset
     * logger X}'s side effect (doc/specs/rule-pipeline-foundation.md
     * "Command surface").
     */
    RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky);
}
