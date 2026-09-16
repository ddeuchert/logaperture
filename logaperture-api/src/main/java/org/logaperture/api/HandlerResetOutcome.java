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
 * {@link ResetOutcome}'s counterpart for {@code resetHandler}/{@code
 * resetAllHandlers} (doc/specs/reset-command-surface.md) — same shape, same
 * reasoning, one entry per {@link HandlerRef} instead of a logger name.
 *
 * @param revertedHandlerRefs       the handlers whose override was actually
 *                                  reverted by this call — at most one entry
 *                                  for a single-ref target, zero or more for
 *                                  {@code resetAllHandlers}
 * @param skippedStickyHandlerRefs  handlers whose override was left in place
 *                                  because it is {@code STICKY} and {@code
 *                                  includeSticky} wasn't passed — always
 *                                  empty for a single-ref target, which
 *                                  refuses outright instead of skipping (see
 *                                  that spec's Decision #1)
 */
public record HandlerResetOutcome(List<HandlerRef> revertedHandlerRefs, List<HandlerRef> skippedStickyHandlerRefs) {

    public HandlerResetOutcome {
        Objects.requireNonNull(revertedHandlerRefs, "revertedHandlerRefs");
        Objects.requireNonNull(skippedStickyHandlerRefs, "skippedStickyHandlerRefs");
        revertedHandlerRefs = List.copyOf(revertedHandlerRefs);
        skippedStickyHandlerRefs = List.copyOf(skippedStickyHandlerRefs);
    }

    private static final HandlerResetOutcome NOTHING_RESET = new HandlerResetOutcome(List.of(), List.of());

    /** No override existed to revert, and none was skipped either. */
    public static HandlerResetOutcome nothingReset() {
        return NOTHING_RESET;
    }
}
