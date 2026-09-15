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
 * MXBean-friendly mirror of {@link ResetOutcome} — shared by every
 * reset-shaped operation (doc/specs/reset-command-surface.md "Operations").
 * Reports exactly what the server reverted, retired, excluded, and skipped,
 * so a caller (the CLI's {@code reset} command) never needs to reconstruct
 * that by diffing {@code listLoggers} before and after the call.
 */
public final class ResetOutcomeData {

    private final List<String> revertedNames;
    private final List<String> retiredPatterns;
    private final List<String> excludedFrom;
    private final List<String> skippedStickyNames;

    @ConstructorProperties({"revertedNames", "retiredPatterns", "excludedFrom", "skippedStickyNames"})
    public ResetOutcomeData(List<String> revertedNames, List<String> retiredPatterns, List<String> excludedFrom,
            List<String> skippedStickyNames) {
        this.revertedNames = revertedNames;
        this.retiredPatterns = retiredPatterns;
        this.excludedFrom = excludedFrom;
        this.skippedStickyNames = skippedStickyNames;
    }

    public static ResetOutcomeData from(ResetOutcome outcome) {
        return new ResetOutcomeData(outcome.revertedNames(), outcome.retiredPatterns(), outcome.excludedFrom(),
                outcome.skippedStickyNames());
    }

    public List<String> getRevertedNames() {
        return revertedNames;
    }

    public List<String> getRetiredPatterns() {
        return retiredPatterns;
    }

    public List<String> getExcludedFrom() {
        return excludedFrom;
    }

    public List<String> getSkippedStickyNames() {
        return skippedStickyNames;
    }
}
