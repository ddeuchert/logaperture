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
package org.logaperture.api;

import java.util.List;
import java.util.Objects;

/**
 * What a reset operation actually did, reported by the server that did the
 * reverting rather than reconstructed by a caller diffing two point-in-time
 * reads of {@code listLoggers} around a {@code void} call (a code-review
 * finding against the original slice: that diff was racy against concurrent
 * mutation, and had no way to tell "no standing rule existed under that
 * exact string" from "the rule existed but matched nothing"). Shared by
 * every reset-shaped operation (doc/specs/reset-command-surface.md
 * "Operations") — {@code resetLevel}, {@code resetHandler}, {@code
 * resetAllLoggers}, {@code resetAllHandlers}, and {@code resetAll} all
 * return this same shape; a field that doesn't apply to a given operation
 * (e.g. {@code retiredPatterns} for a handler reset) is simply empty.
 *
 * @param revertedNames    the loggers or handlers whose override was
 *                         actually reverted by this call
 * @param retiredPatterns  pattern strings of any standing rule(s) fully
 *                         retired by this call — a single {@code
 *                         resetLevel(target, ...)} can retire more than one
 *                         rule when {@code target} spans loggers currently
 *                         owned by different active rules (doc/specs/
 *                         reset-command-surface.md "Partial reset — scoped
 *                         exclusions"); always empty for a non-pattern
 *                         operation
 * @param excludedFrom     pattern strings of any standing rule(s) this call
 *                         carved {@code target} out of without retiring the
 *                         whole rule; always empty for a non-pattern
 *                         operation, or when {@code target} exhausted a
 *                         rule's entire coverage (that rule is reported in
 *                         {@code retiredPatterns} instead)
 * @param skippedStickyNames names that would otherwise have been reverted
 *                         but were left alone because they (or the standing
 *                         rule governing them) are {@code STICKY}-tier and
 *                         the call did not pass {@code includeSticky}
 */
public record ResetOutcome(
        List<String> revertedNames,
        List<String> retiredPatterns,
        List<String> excludedFrom,
        List<String> skippedStickyNames) {

    public ResetOutcome {
        Objects.requireNonNull(revertedNames, "revertedNames");
        Objects.requireNonNull(retiredPatterns, "retiredPatterns");
        Objects.requireNonNull(excludedFrom, "excludedFrom");
        Objects.requireNonNull(skippedStickyNames, "skippedStickyNames");
        revertedNames = List.copyOf(revertedNames);
        retiredPatterns = List.copyOf(retiredPatterns);
        excludedFrom = List.copyOf(excludedFrom);
        skippedStickyNames = List.copyOf(skippedStickyNames);
    }

    private static final ResetOutcome NOTHING_RESET = new ResetOutcome(List.of(), List.of(), List.of(), List.of());

    /** Nothing was overridden, no standing rule was touched, and nothing was skipped for being sticky. */
    public static ResetOutcome nothingReset() {
        return NOTHING_RESET;
    }
}
