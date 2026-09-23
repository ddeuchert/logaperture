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
import org.logaperture.core.RuleGate;
import org.logaperture.core.TrimDecision;

import java.util.Optional;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * The rule-pipeline's render-stage seam wearing a {@link Formatter}'s
 * clothes -- doc/specs/trim-rule.md "Evaluation". Installed on a real
 * {@link java.util.logging.Handler}, wrapping whatever {@link Formatter}
 * was already there (see {@link JulLoggingAdapter#installTrimRendering}
 * for the layering relative to {@link ByteCountingFormatter}).
 *
 * <p>Reads the same cached {@link GateVerdict} the sibling {@link
 * JulRuleFilter} installed on the very same handler already computed for
 * this record via {@code gate.evaluate} -- one evaluation per event, shared
 * across both seams (doc/specs/rule-pipeline-foundation.md "Evaluation").
 * Never mutates the shared {@link LogRecord}: a matching event is formatted
 * from a private copy (see {@link ExtLogRecordCopier}), or, if no safe copy
 * can be made, this fails open and formats the record untouched
 * (doc/spikes/rule-pipeline.md "Design consequences" #5).
 */
final class JulTrimFormatter extends Formatter {

    private final Formatter delegate;
    private final RuleGate gate;

    JulTrimFormatter(Formatter delegate, RuleGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    /** The formatter this instance wraps -- lets {@link JulLoggingAdapter} detect and re-layer an already-wrapped handler. */
    Formatter delegate() {
        return delegate;
    }

    @Override
    public String format(LogRecord record) {
        TrimDecision trim = record.getThrown() == null ? null : decisionFor(record);
        if (trim == null) {
            return delegate.format(record);
        }
        Optional<LogRecord> copy = ExtLogRecordCopier.copy(record);
        if (copy.isEmpty()) {
            // Fail open -- doc/spikes/rule-pipeline.md "Design consequences" #5: no safe copy
            // (not JBoss LogManager, or the copy constructor isn't reachable), format untouched.
            return delegate.format(record);
        }
        LogRecord trimmedCopy = copy.get();
        trimmedCopy.setThrown(TrimRendering.buildTrimmed(record.getThrown(), trim));
        return delegate.format(trimmedCopy);
    }

    private TrimDecision decisionFor(LogRecord record) {
        try {
            GateVerdict verdict = gate.evaluate(record, RuleCandidateEvents.of(record));
            // A denied event never reaches this formatter in practice (its own handler's Filter
            // already blocked publish()) -- checked anyway, doc/specs/trim-rule.md "Evaluation":
            // "a dropped event is never trimmed", not assumed true by construction.
            return verdict.deny() ? null : verdict.trim();
        } catch (RuntimeException e) {
            // Fail open -- doc/logaperture-spec.md §9's fail-open discipline.
            System.err.println(
                    "[logaperture-adapter-jul] trim evaluation failed, event formatted unaffected: " + e);
            return null;
        }
    }

    @Override
    public String getHead(Handler handler) {
        return delegate.getHead(handler);
    }

    @Override
    public String getTail(Handler handler) {
        return delegate.getTail(handler);
    }
}
