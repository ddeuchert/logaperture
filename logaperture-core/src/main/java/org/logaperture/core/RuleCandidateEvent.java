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
package org.logaperture.core;

import org.logaperture.api.Level;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One candidate log event, reduced to exactly what {@link RuleService}'s
 * gate evaluation needs -- doc/specs/drop-rule.md "Evaluation". Mirrors
 * {@link StormObservation}'s split: the adapter's {@code Filter} does the
 * framework-specific extraction only, everything else (matching, hit
 * counting, sampling) lives in {@code core}.
 *
 * @param loggerName               the originating logger's name
 * @param level                    the record's level
 * @param thrown                   the event's throwable, or {@code null}
 *                                 when it carried none -- unlike {@link
 *                                 StormObservation}, the real object (not
 *                                 just its class name), since a {@code
 *                                 throwable} matcher walks the actual cause
 *                                 chain
 * @param formattedMessageSupplier supplies the formatted (post-substitution)
 *                                 message; invoked at most once per event,
 *                                 and only when some effective rule on this
 *                                 logger actually has a {@code message}
 *                                 matcher (doc/spikes/rule-pipeline.md
 *                                 finding 6/7: this is the one real cost a
 *                                 candidate logger pays, and it's the same
 *                                 formatting work the layout would have
 *                                 done anyway for a kept event). The
 *                                 compact constructor wraps whatever
 *                                 caller-supplied {@link Supplier} is given
 *                                 in a memoizing one, so this guarantee
 *                                 holds even when a logger has several
 *                                 effective rules with a message matcher
 *                                 (a code-review finding: the raw supplier
 *                                 on its own gets re-invoked once per rule
 *                                 tried, not once per event, the first time
 *                                 an earlier rule's message check misses)
 * @param timestamp                when the event occurred
 */
public record RuleCandidateEvent(
        String loggerName,
        Level level,
        Throwable thrown,
        Supplier<String> formattedMessageSupplier,
        Instant timestamp) {

    public RuleCandidateEvent {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(formattedMessageSupplier, "formattedMessageSupplier");
        Objects.requireNonNull(timestamp, "timestamp");
        formattedMessageSupplier = memoize(formattedMessageSupplier);
    }

    /**
     * Not thread-safe, deliberately: this event (and the supplier it wraps)
     * is only ever touched by the single thread that won {@link
     * RuleService}'s per-record decision-cache race -- every sibling
     * handler's filter reuses the already-computed {@link GateVerdict}
     * instead of touching this supplier again.
     */
    private static Supplier<String> memoize(Supplier<String> original) {
        boolean[] computed = {false};
        String[] cached = {null};
        return () -> {
            if (!computed[0]) {
                cached[0] = original.get();
                computed[0] = true;
            }
            return cached[0];
        };
    }
}
