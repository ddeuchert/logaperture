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

import org.logaperture.api.HandlerResetOutcome;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link HandlerResetOutcome} — {@code
 * resetHandler}/{@code resetAllHandlers}'s return type ({@link
 * ResetOutcomeData}'s counterpart for handlers).
 */
public final class HandlerResetOutcomeData {

    private final List<String> revertedHandlerRefs;
    private final List<String> skippedStickyHandlerRefs;

    @ConstructorProperties({"revertedHandlerRefs", "skippedStickyHandlerRefs"})
    public HandlerResetOutcomeData(List<String> revertedHandlerRefs, List<String> skippedStickyHandlerRefs) {
        this.revertedHandlerRefs = revertedHandlerRefs;
        this.skippedStickyHandlerRefs = skippedStickyHandlerRefs;
    }

    public static HandlerResetOutcomeData from(HandlerResetOutcome outcome) {
        return new HandlerResetOutcomeData(
                outcome.revertedHandlerRefs().stream().map(ref -> ref.value()).toList(),
                outcome.skippedStickyHandlerRefs().stream().map(ref -> ref.value()).toList());
    }

    public List<String> getRevertedHandlerRefs() {
        return revertedHandlerRefs;
    }

    /** Handlers whose {@code STICKY} override was left in place (doc/specs/reset-command-surface.md). */
    public List<String> getSkippedStickyHandlerRefs() {
        return skippedStickyHandlerRefs;
    }
}
