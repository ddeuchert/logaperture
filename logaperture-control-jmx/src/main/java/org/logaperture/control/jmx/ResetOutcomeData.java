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

import org.logaperture.api.ResetOutcome;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link ResetOutcome} — {@code resetLevel}'s
 * return type. Reports exactly what the server reverted, so a caller (the
 * CLI's {@code reset} command) never needs to reconstruct that by diffing
 * {@code listLoggers} before and after the call.
 */
public final class ResetOutcomeData {

    private final List<String> revertedLoggerNames;
    private final boolean patternRuleRetired;

    @ConstructorProperties({"revertedLoggerNames", "patternRuleRetired"})
    public ResetOutcomeData(List<String> revertedLoggerNames, boolean patternRuleRetired) {
        this.revertedLoggerNames = revertedLoggerNames;
        this.patternRuleRetired = patternRuleRetired;
    }

    public static ResetOutcomeData from(ResetOutcome outcome) {
        return new ResetOutcomeData(outcome.revertedLoggerNames(), outcome.patternRuleRetired());
    }

    public List<String> getRevertedLoggerNames() {
        return revertedLoggerNames;
    }

    public boolean isPatternRuleRetired() {
        return patternRuleRetired;
    }
}
