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

import org.logaperture.core.RuleGate;
import org.logaperture.core.StormObserver;

import java.util.logging.Filter;
import java.util.logging.Handler;

/**
 * The one shape LogAperture's handler filters may take --
 * doc/specs/rule-pipeline-foundation.md "Canonical filter layering" (issue
 * #145): {@link JulRuleFilter} outermost, {@link JulStormFilter} inside it,
 * then whatever filter was on the handler before LogAperture (the
 * <em>base</em>, never inspected). Either of ours may be absent while its
 * context hasn't armed it yet.
 *
 * <p>Both {@code install*} methods go through {@link #ensure}, which reads
 * the whole leading run of LogAperture filters rather than just the outermost
 * one. Checking only the outermost one let each install see the
 * <em>other's</em> filter there and wrap again on every sweep tick, growing
 * the chain without bound.
 */
final class FilterLayering {

    private FilterLayering() {
    }

    /**
     * Leaves {@code handler}'s filter alone if its leading run is already
     * canonical and already holds the filter type being installed (exactly
     * one of {@code stormObserver}/{@code ruleGate} is non-null); otherwise
     * rebuilds the run in one {@code setFilter}. The rebuild keeps the base
     * and the other type's filter, if the run held one, reusing its observer
     * or gate.
     */
    static void ensure(Handler handler, StormObserver stormObserver, RuleGate ruleGate) {
        Run run = Run.of(handler.getFilter());
        boolean installingStorm = stormObserver != null;
        boolean present = installingStorm ? run.storm != null : run.rule != null;
        if (present && run.canonical) {
            return;
        }
        StormObserver observer = installingStorm ? stormObserver
                : run.storm != null ? run.storm.observer() : null;
        RuleGate gate = installingStorm ? (run.rule != null ? run.rule.gate() : null) : ruleGate;
        handler.setFilter(build(run.base, observer, gate));
    }

    private static Filter build(Filter base, StormObserver observer, RuleGate gate) {
        Filter filter = base;
        if (observer != null) {
            filter = new JulStormFilter(filter, observer);
        }
        if (gate != null) {
            filter = new JulRuleFilter(filter, gate);
        }
        return filter;
    }

    /**
     * The leading run of LogAperture filters on a handler: the outermost
     * {@link JulRuleFilter} and {@link JulStormFilter} found in it (each
     * {@code null} if absent), the first non-LogAperture filter that ends it
     * ({@code null} if none), and whether the run is exactly canonical -- at
     * most one of each type, rule outside storm.
     */
    static final class Run {
        final JulRuleFilter rule;
        final JulStormFilter storm;
        final Filter base;
        final boolean canonical;
        final int depth;

        private Run(JulRuleFilter rule, JulStormFilter storm, Filter base, boolean canonical, int depth) {
            this.rule = rule;
            this.storm = storm;
            this.base = base;
            this.canonical = canonical;
            this.depth = depth;
        }

        static Run of(Filter outermost) {
            JulRuleFilter rule = null;
            JulStormFilter storm = null;
            boolean canonical = true;
            int depth = 0;
            Filter current = outermost;
            while (true) {
                if (current instanceof JulRuleFilter ruleFilter) {
                    // A rule filter is canonical only as the very first layer.
                    canonical &= depth == 0;
                    if (rule == null) {
                        rule = ruleFilter;
                    }
                    current = ruleFilter.delegate();
                } else if (current instanceof JulStormFilter stormFilter) {
                    canonical &= storm == null;
                    if (storm == null) {
                        storm = stormFilter;
                    }
                    current = stormFilter.delegate();
                } else {
                    return new Run(rule, storm, current, canonical, depth);
                }
                depth++;
            }
        }
    }
}
