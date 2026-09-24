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

import org.junit.jupiter.api.Test;
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleGate;
import org.logaperture.core.TrimDecision;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * doc/specs/trim-rule.md "Evaluation". This module has no compile-time
 * dependency on JBoss LogManager ({@link JulLoggingAdapter}'s class doc), so
 * a real {@code ExtLogRecord} copy succeeding is proven only by {@code
 * WildFlyContainerIT} against a real server, doc/specs/trim-rule.md "Open
 * decisions for sign-off" #5 -- these tests cover the fail-open paths a
 * plain {@link LogRecord} always takes here, and the gate-reuse contract.
 */
class JulTrimFormatterTest {

    private static final RuleGate ALWAYS_ALLOW = (recordIdentity, event) -> GateVerdict.allow();

    @Test
    void format_noTrimDecision_delegatesUnchanged() {
        JulTrimFormatter formatter = new JulTrimFormatter(new SimpleFormatter(), ALWAYS_ALLOW);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(new RuntimeException("simulated failure"));

        String formatted = formatter.format(record);

        assertEquals(new SimpleFormatter().format(record), formatted);
    }

    @Test
    void format_recordWithNoThrowable_neverEvaluatesTheGate() {
        RuleGate explodingIfCalled = (recordIdentity, event) -> {
            throw new AssertionError("should never be evaluated -- nothing to trim without a throwable");
        };
        JulTrimFormatter formatter = new JulTrimFormatter(new SimpleFormatter(), explodingIfCalled);
        LogRecord record = new LogRecord(Level.INFO, "no throwable here");
        record.setLoggerName("com.acme.Worker");

        String formatted = formatter.format(record);

        assertEquals(new SimpleFormatter().format(record), formatted);
    }

    @Test
    void format_structuredFormatterDelegate_neverTrims_neverEvaluatesTheGate() {
        // doc/specs/trim-rule.md "Text formatters only" -- a Trim rule is a no-op on a
        // structured-formatter handler; a code-review finding against the first cut, which
        // applied the synthetic-throwable substitution unconditionally, reproducing the
        // spike-observed JSON defect this slice is supposed to avoid.
        RuleGate explodingIfCalled = (recordIdentity, event) -> {
            throw new AssertionError("must never evaluate the gate for a structured-formatter handler");
        };
        class JsonFormatter extends SimpleFormatter {
        }
        JsonFormatter delegate = new JsonFormatter();
        JulTrimFormatter formatter = new JulTrimFormatter(delegate, explodingIfCalled);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(new RuntimeException("simulated failure"));

        String formatted = formatter.format(record);

        assertEquals(delegate.format(record), formatted);
    }

    @Test
    void format_trimDecisionButNotAnExtLogRecord_failsOpen_formatsUntouched() {
        RuleGate alwaysTrim = (recordIdentity, event) -> GateVerdict.allowWithTrim(new TrimDecision("r1", 0, false));
        JulTrimFormatter formatter = new JulTrimFormatter(new SimpleFormatter(), alwaysTrim);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(new RuntimeException("simulated failure"));

        // A plain java.util.logging.LogRecord (this module's test classpath carries no JBoss
        // LogManager) can never be safely copied -- ExtLogRecordCopier.copy returns empty, and
        // this formatter falls back to formatting the original, untouched record.
        String formatted = formatter.format(record);

        assertEquals(new SimpleFormatter().format(record), formatted);
    }

    @Test
    void format_gateEvaluationThrows_failsOpen_formatsUntouched() {
        RuleGate explodes = (recordIdentity, event) -> {
            throw new RuntimeException("boom");
        };
        JulTrimFormatter formatter = new JulTrimFormatter(new SimpleFormatter(), explodes);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(new RuntimeException("simulated failure"));

        String formatted = formatter.format(record);

        assertEquals(new SimpleFormatter().format(record), formatted);
    }

    @Test
    void format_deniedVerdict_neverTrimmed_evenIfATrimDecisionSomehowCameBack() {
        // Defensive: a denied event should never reach this formatter in practice (its handler's
        // Filter already blocked publish()), but the formatter itself must not trust that by
        // construction -- doc/specs/trim-rule.md "Evaluation".
        RuleGate deniedWithTrim = (recordIdentity, event) -> GateVerdict.deny("r1");
        JulTrimFormatter formatter = new JulTrimFormatter(new SimpleFormatter(), deniedWithTrim);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(new RuntimeException("simulated failure"));

        String formatted = formatter.format(record);

        assertEquals(new SimpleFormatter().format(record), formatted);
    }

    @Test
    void getHeadAndGetTail_delegateToTheWrappedFormatter() {
        SimpleFormatter delegate = new SimpleFormatter();
        JulTrimFormatter formatter = new JulTrimFormatter(delegate, ALWAYS_ALLOW);

        assertEquals(delegate.getHead(null), formatter.getHead(null));
        assertEquals(delegate.getTail(null), formatter.getTail(null));
    }

    @Test
    void delegate_exposesTheWrappedFormatter_forReWrapDetection() {
        SimpleFormatter delegate = new SimpleFormatter();
        JulTrimFormatter formatter = new JulTrimFormatter(delegate, ALWAYS_ALLOW);

        assertEquals(delegate, formatter.delegate());
    }
}
