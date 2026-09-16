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
 * {@code resetLogger}'s result — what actually happened, reported by the
 * server that did the reverting rather than reconstructed by a caller
 * diffing two point-in-time reads of {@code listLoggers} around a {@code
 * void} call (a code-review finding against the original slice: that diff
 * was racy against concurrent mutation).
 *
 * @param revertedLoggerNames       the loggers whose override was actually
 *                                  reverted by this call — for an exact-name
 *                                  target, at most one entry (itself, if it
 *                                  carried an override and wasn't skipped for
 *                                  being {@code STICKY}); for a pattern or
 *                                  bulk target, every currently-matched
 *                                  logger that carried an active,
 *                                  non-skipped override (doc/specs/
 *                                  pattern-selection-semantics.md); empty if
 *                                  there was nothing to revert
 * @param skippedStickyLoggerNames  loggers whose override was left in place
 *                                  because it is {@code STICKY} and {@code
 *                                  includeSticky} wasn't passed (doc/specs/
 *                                  reset-command-surface.md) — always empty
 *                                  for an exact-name target, which refuses
 *                                  outright instead of skipping (see that
 *                                  spec's Decision #1)
 */
public record ResetOutcome(List<String> revertedLoggerNames, List<String> skippedStickyLoggerNames) {

    public ResetOutcome {
        Objects.requireNonNull(revertedLoggerNames, "revertedLoggerNames");
        Objects.requireNonNull(skippedStickyLoggerNames, "skippedStickyLoggerNames");
        revertedLoggerNames = List.copyOf(revertedLoggerNames);
        skippedStickyLoggerNames = List.copyOf(skippedStickyLoggerNames);
    }

    private static final ResetOutcome NOTHING_RESET = new ResetOutcome(List.of(), List.of());

    /** No override existed to revert, and none was skipped either. */
    public static ResetOutcome nothingReset() {
        return NOTHING_RESET;
    }
}
