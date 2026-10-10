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

import org.logaperture.core.RuleCandidateEvent;

import java.util.logging.LogRecord;

/**
 * Builds a {@link RuleCandidateEvent} off a JUL {@link LogRecord} -- shared
 * by {@link JulRuleFilter} (gate stage) and {@link JulTrimFormatter} (render
 * stage, doc/specs/trim-rule.md "Evaluation"): both seams evaluate the same
 * {@code RuleGate} against the same record shape.
 */
final class RuleCandidateEvents {

    private RuleCandidateEvents() {
    }

    /** The name every rule lookup for this record uses; {@code ""} for an anonymous logger. */
    static String loggerName(LogRecord record) {
        return record.getLoggerName() != null ? record.getLoggerName() : "";
    }

    static RuleCandidateEvent of(LogRecord record) {
        String loggerName = loggerName(record);
        org.logaperture.api.Level level = LevelMapper.toApi(record.getLevel());
        Throwable thrown = record.getThrown();
        return new RuleCandidateEvent(loggerName, level, thrown,
                () -> FormattedMessages.of(record), record.getInstant());
    }
}
