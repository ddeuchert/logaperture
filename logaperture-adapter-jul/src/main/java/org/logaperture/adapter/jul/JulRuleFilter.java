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

import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;

import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * The rule-pipeline's gate-stage seam wearing a {@link Filter}'s clothes —
 * doc/specs/rule-pipeline-foundation.md "Relationship to the storm-detection
 * filter", doc/specs/drop-rule.md "Evaluation". Installed on a real {@link
 * java.util.logging.Handler}, capturing whatever {@link Filter} was already
 * there and delegating to it. A <b>separate instance</b> from {@link
 * JulStormFilter}, deliberately, so that filter's own "never denies"
 * contract stays literally true forever even now that this one does deny.
 */
final class JulRuleFilter implements Filter {

    /**
     * A plain, stateless message-substitution helper — {@code
     * formatMessage} does no framework-specific rendering (no date, no
     * level, no layout), just {@code MessageFormat}/resource-bundle
     * substitution, so one shared instance is safe to reuse across threads
     * and handlers. This adapter has no compile-time JBoss LogManager
     * dependency, so it can't reach {@code ExtLogRecord.getFormattedMessage()}'s
     * own per-record cache the way the spike measured
     * (doc/spikes/rule-pipeline.md finding 6/7) — this recomputes per
     * candidate event instead of reusing a cache already on the record; see
     * doc/specs/drop-rule.md "Divergence from prior specs".
     */
    private static final Formatter MESSAGE_FORMATTER = new SimpleFormatter();

    private final Filter delegate;
    private final RuleGate gate;

    JulRuleFilter(Filter delegate, RuleGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    /** The filter this instance captured — restored by {@link JulLoggingAdapter} on teardown. */
    Filter delegate() {
        return delegate;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        boolean allowed = true;
        try {
            GateVerdict verdict = gate.evaluate(record, toEvent(record));
            allowed = !verdict.deny();
        } catch (RuntimeException e) {
            // Fail open -- doc/logaperture-spec.md §9's fail-open discipline: a gate-evaluation
            // bug must never itself become a reason to lose an event.
            System.err.println("[logaperture-adapter-jul] rule evaluation failed, event passes through unaffected: "
                    + e);
        }
        // The delegate is always evaluated, deny or not (no short-circuit on `allowed`) -- doc/specs/
        // drop-rule.md "Interaction with storm detection", Decision #3: with storm detection
        // installed first (inner delegate) and this filter installed second (outer), a denied event
        // must still reach storm detection's own observer. Only the final verdict differs.
        boolean delegateAllows = delegate == null || delegate.isLoggable(record);
        return allowed && delegateAllows;
    }

    private static RuleCandidateEvent toEvent(LogRecord record) {
        String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "";
        org.logaperture.api.Level level = LevelMapper.toApi(record.getLevel());
        Throwable thrown = record.getThrown();
        return new RuleCandidateEvent(loggerName, level, thrown,
                () -> MESSAGE_FORMATTER.formatMessage(record), record.getInstant());
    }
}
