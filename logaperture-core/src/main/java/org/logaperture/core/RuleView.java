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

/**
 * A {@link LogRule} tagged with its owning context's stable key and its
 * current hit count -- {@link AggregateLevelControl#listRules()}'s row
 * shape. {@link LogRule} is an interface implemented by varying concrete
 * types (unlike {@code Storm}/{@code LoggerByteCount}, which are records
 * with their own {@code withContext} copy method), so context-stamping
 * happens at this wrapper level instead of on the domain object itself.
 * {@code context} is {@code null} only when produced directly by a
 * single-context {@link RuleService} -- {@code AggregateLevelControl}
 * always stamps the real key, same convention as every other multi-context
 * row in this codebase.
 *
 * @param hitCount how many candidate events this rule has matched so far --
 *                 doc/specs/drop-rule.md "Safety set", filtering-epic.md
 *                 Decision #9 ("hit counts are per event"). {@code 0} for a
 *                 rule that has never matched.
 * @param origin   {@code "vendor-defaults"} for a rule from the vendor defaults
 *                 file, {@code null} for one an operator added --
 *                 doc/specs/vendor-defaults.md "Rules"
 * @param toNative a vendor rule switched off until restart with {@code reset rule … --to-native}
 *                 (doc/specs/alter-rule.md "Reset", A8); always {@code false} for an operator rule
 * @param altered  a vendor rule with an {@code alter rule} override on top of the vendor's
 *                 definition (doc/specs/alter-rule.md "Vendor rules", A7); always {@code false}
 *                 for an operator rule, whose alterations change it in place
 */
public record RuleView(LogRule rule, String context, long hitCount, String origin, boolean toNative,
        boolean altered) {

    /** {@code hitCount} defaults to {@code 0} -- most call sites outside {@link RuleService} itself just tag a context. */
    public RuleView(LogRule rule, String context) {
        this(rule, context, 0L);
    }

    /** An operator rule: no origin, never switched off or altered-over-a-baseline. */
    public RuleView(LogRule rule, String context, long hitCount) {
        this(rule, context, hitCount, null, false, false);
    }

    /** This same row, stamped with its owning context's stable key. */
    public RuleView withContext(String context) {
        return new RuleView(rule, context, hitCount, origin, toNative, altered);
    }
}
