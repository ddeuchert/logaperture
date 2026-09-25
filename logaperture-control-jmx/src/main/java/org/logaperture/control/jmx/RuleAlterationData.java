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

import org.logaperture.api.RuleExpression;
import org.logaperture.core.RuleAlteration;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link RuleAlteration} -- {@code logctl alter rule}'s result
 * (doc/specs/alter-rule.md "Command surface"): the rule as it is now, plus what it was.
 */
public final class RuleAlterationData {

    /**
     * {@code previousTier} for a vendor rule that was at its vendor definition: it had no lifetime
     * of its own (the vendor defaults file is its persistence).
     */
    public static final String VENDOR_BASELINE_TIER = "vendor-defaults";

    private final RuleData rule;
    private final String previousExpression;
    private final String previousTier;
    private final String previousExpiresAt;
    private final boolean changed;

    @ConstructorProperties({"rule", "previousExpression", "previousTier", "previousExpiresAt", "changed"})
    public RuleAlterationData(RuleData rule, String previousExpression, String previousTier,
            String previousExpiresAt, boolean changed) {
        this.rule = rule;
        this.previousExpression = previousExpression;
        this.previousTier = previousTier;
        this.previousExpiresAt = previousExpiresAt;
        this.changed = changed;
    }

    public static RuleAlterationData from(RuleAlteration alteration) {
        var before = alteration.before().rule();
        boolean vendorBaseline = alteration.before().origin() != null && !alteration.before().altered();
        return new RuleAlterationData(RuleData.from(alteration.after()), RuleExpression.of(before),
                vendorBaseline ? VENDOR_BASELINE_TIER : before.tier().name(),
                vendorBaseline || before.expiresAt() == null ? null : before.expiresAt().toString(),
                alteration.changed());
    }

    /** The rule after the alteration. */
    public RuleData getRule() {
        return rule;
    }

    /** The rule's definition before, in {@code list rules --verbose} form. */
    public String getPreviousExpression() {
        return previousExpression;
    }

    public String getPreviousTier() {
        return previousTier;
    }

    public String getPreviousExpiresAt() {
        return previousExpiresAt;
    }

    /** {@code false} if the alteration changed nothing (doc/specs/alter-rule.md A4). */
    public boolean isChanged() {
        return changed;
    }
}
