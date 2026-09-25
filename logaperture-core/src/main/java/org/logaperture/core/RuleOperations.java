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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleChange;
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.api.SampleFullPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@code logctl list rules}/{@code reset rule}/{@code reset rules}/{@code
 * add rule drop}/{@code add rule trim}'s public contract — the {@link
 * StormOperations}/{@link TopOperations} counterpart for the rule pipeline
 * (doc/specs/rule-pipeline-foundation.md "Command surface", doc/specs/
 * drop-rule.md, doc/specs/trim-rule.md).
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
     * passed. Empty if there was nothing to reset (a no-op, not an error).
     * Returns the rule tagged with the context it was actually reset in,
     * since ids are only unique per context (doc/specs/
     * rule-pipeline-foundation.md "Rule identity").
     */
    default Optional<RuleView> resetRule(String id, boolean includeSticky) {
        return resetRule(id, includeSticky, false);
    }

    /**
     * {@link #resetRule(String, boolean)}, plus doc/specs/alter-rule.md "Reset" (A8): a vendor
     * defaults rule is reset to the vendor's definition (and switched back on), or with {@code
     * toNative} switched off until restart; it is never removed. On an operator rule {@code
     * toNative} changes nothing.
     */
    Optional<RuleView> resetRule(String id, boolean includeSticky, boolean toNative);

    /** {@code reset rules} — bulk, skip-and-report shape. */
    default RuleResetOutcome resetAllRules(boolean includeSticky) {
        return resetAllRules(includeSticky, false);
    }

    /** {@link #resetAllRules(boolean)}; vendor rules as in {@link #resetRule(String, boolean, boolean)}. */
    RuleResetOutcome resetAllRules(boolean includeSticky, boolean toNative);

    /**
     * The rules attached directly to {@code loggerName} — {@code reset
     * logger X}'s side effect (doc/specs/rule-pipeline-foundation.md
     * "Command surface").
     */
    default RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky) {
        return resetRulesForLogger(loggerName, includeSticky, false);
    }

    /** {@link #resetRulesForLogger(String, boolean)}; vendor rules as in {@link #resetRule(String, boolean, boolean)}. */
    RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky, boolean toNative);

    /**
     * {@code logctl alter rule <id>} — doc/specs/alter-rule.md. Requires {@link
     * Capability#RULES_AUTHOR} and {@link Capability#SUPPRESS} (and {@link Capability#PERSIST} if
     * the resulting tier isn't {@code SESSION}).
     *
     * @param tier      the new lifetime, or {@code null} to keep it (A6/A7)
     * @param expiresIn required iff {@code tier} is {@code FOR}
     * @return empty if no rule has this id
     */
    Optional<RuleAlteration> alterRule(String id, RuleChange change, PersistenceTier tier, Duration expiresIn);

    /**
     * {@code logctl add rule drop} — doc/specs/drop-rule.md "Command
     * surface". Requires {@link Capability#RULES_AUTHOR} and {@link
     * Capability#SUPPRESS} (and, for a non-{@code SESSION} {@code tier},
     * {@link Capability#PERSIST}).
     *
     * @implNote The {@link AggregateLevelControl} implementation attaches to
     * the first registered context only — multi-context fan-out and
     * leading-star pattern-target expansion (filtering-epic.md "Targeting
     * by pattern is shorthand") are deferred past this pass; see
     * doc/specs/drop-rule.md "Divergence from prior specs".
     */
    RuleView addRuleDrop(String loggerName, CompiledMatchers matchers, RuleAttachOptions options,
            SampleFullPolicy sampleFull);

    /**
     * {@code logctl add rule trim} — doc/specs/trim-rule.md "Command
     * surface". Requires {@link Capability#RULES_AUTHOR} and {@link
     * Capability#SUPPRESS} (and, for a non-{@code SESSION} {@code tier},
     * {@link Capability#PERSIST}) — same capability shape as {@link
     * #addRuleDrop}.
     *
     * @implNote The {@link AggregateLevelControl} implementation attaches to
     * the first registered context only, same scope reduction as {@link
     * #addRuleDrop} — see doc/specs/trim-rule.md "Explicitly out of scope
     * for this slice".
     */
    RuleView addRuleTrim(String loggerName, CompiledMatchers matchers, RuleAttachOptions options, int frames,
            boolean collapseCauses);
}
