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
package org.logaperture.adapter.jul;

import org.logaperture.core.RulePlanSource;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * The rule-pipeline's gate-stage seam wearing a {@link Filter}'s clothes —
 * doc/specs/rule-pipeline-foundation.md "Relationship to the storm-detection
 * filter". Installed on a real {@link java.util.logging.Handler}, capturing
 * whatever {@link Filter} was already there and delegating to it. This
 * slice's own {@link #isLoggable} never denies an event on its own — there
 * is no concrete rule type yet whose verdict it could apply — but it is a
 * <b>separate instance</b> from {@link JulStormFilter}, deliberately, so
 * that filter's own "never denies" contract stays literally true forever
 * even once a future rule here starts denying events.
 */
final class JulRuleFilter implements Filter {

    private final Filter delegate;
    private final RulePlanSource plan;

    JulRuleFilter(Filter delegate, RulePlanSource plan) {
        this.delegate = delegate;
        this.plan = plan;
    }

    /** The filter this instance captured — restored by {@link JulLoggingAdapter} on teardown. */
    Filter delegate() {
        return delegate;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        // Reading the current plan proves the seam is live and swappable;
        // nothing in it is evaluated yet -- there is no concrete rule type
        // (#72's Drop, #34's Trim) to evaluate a verdict with. Every event
        // passes through untouched.
        plan.currentPlan();
        return delegate == null || delegate.isLoggable(record);
    }
}
