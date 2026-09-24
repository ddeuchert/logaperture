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

import org.logaperture.core.TrimDecision;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Builds the trimmed {@link Throwable} chain a matching {@link
 * TrimDecision} produces -- doc/specs/trim-rule.md "Text formatters only".
 * Each level of the original cause chain is replaced with a frameless (or
 * top-N-frame) synthetic {@link Throwable} whose {@code toString()} is that
 * level's own original {@code toString()} plus the trim marker; a text
 * formatter's ordinary {@code printStackTrace} rendering (or a
 * pattern-formatter token that calls it) then produces the one-liner plus
 * marker at every level, recursively, for free -- no formatter-specific
 * code needed here beyond building the replacement chain.
 */
final class TrimRendering {

    private TrimRendering() {
    }

    /** {@code original} itself is never mutated -- the caller is expected to set the result on a private record copy (see {@link ExtLogRecordCopier}), never on the shared record. */
    static Throwable buildTrimmed(Throwable original, TrimDecision decision) {
        return buildLevel(original, decision.frames(), decision.collapseCauses(),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Throwable buildLevel(Throwable original, int frames, boolean collapseCauses,
            Set<Throwable> visited) {
        if (original == null || !visited.add(original)) {
            // No cause at this level, or a cycle back to an already-rendered one -- stop here,
            // same guard java.lang.Throwable#printEnclosedStackTrace's own "dejaVu" set uses.
            return null;
        }
        Throwable cause = collapseCauses ? null : buildLevel(original.getCause(), frames, false, visited);
        StackTraceElement[] originalFrames = original.getStackTrace();
        int kept = Math.max(0, Math.min(frames, originalFrames.length));
        int omitted = originalFrames.length - kept;
        // Decision #1 (doc/specs/trim-rule.md "Open decisions for sign-off"): the marker always
        // prints, reading "0 frames omitted" when frames is at or above the trace's actual depth.
        String rendered = original + " [stack trace trimmed: " + omitted + " frames omitted]";
        // getStackTrace() already handed back a fresh defensive copy -- no need for a second one
        // when every frame is kept (a code-review finding against the first cut, which copied
        // unconditionally).
        StackTraceElement[] keptFrames = kept == originalFrames.length ? originalFrames
                : Arrays.copyOf(originalFrames, kept);
        return new SyntheticThrowable(rendered, keptFrames, cause);
    }

    /**
     * A throwable whose only job is to render {@code rendered} as its own
     * {@code toString()}, with {@code frames} printed beneath it and {@code
     * cause} chained on for {@code printStackTrace}'s own recursive
     * "Caused by:" handling -- never thrown, never inspected beyond
     * formatting.
     */
    private static final class SyntheticThrowable extends Throwable {

        private final String rendered;

        SyntheticThrowable(String rendered, StackTraceElement[] frames, Throwable cause) {
            // enableSuppression=false (never thrown/caught, nothing suppresses into it).
            // writableStackTrace=true, even though the real fillInStackTrace() capture it
            // triggers here is immediately discarded by the explicit setStackTrace() below --
            // empirically, java.lang.Throwable's own getStackTrace() keeps returning empty for a
            // writableStackTrace=false instance even after setStackTrace() is called with a
            // non-empty array, so false is not the free optimization it looks like.
            super(null, cause, false, true);
            this.rendered = rendered;
            setStackTrace(frames);
        }

        @Override
        public String toString() {
            return rendered;
        }
    }
}
