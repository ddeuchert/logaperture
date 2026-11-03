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

/**
 * The seam an adapter's gate-stage {@code Filter} evaluates every candidate
 * event against -- doc/specs/drop-rule.md "Evaluation". {@link RuleService}
 * is the only production implementation.
 *
 * <p>{@code recordIdentity} is the adapter's own framework record object
 * (e.g. a JUL {@code LogRecord}), used purely as an identity key so a
 * verdict computed for one handler's filter is reused, not recomputed, by
 * every sibling handler's filter evaluating the very same event -- doc/specs/
 * rule-pipeline-foundation.md "Evaluation": hit counts are per event, not
 * per handler. Never inspected, only used as a {@link java.util.WeakHashMap}
 * key, so it is safe to pass an adapter-specific framework type here without
 * this interface depending on it.
 */
@FunctionalInterface
public interface RuleGate {

    GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event);

    /**
     * {@link #evaluate} for a gate that runs <em>once per event</em>, so there is no sibling filter
     * to share a verdict with and nothing to remember it for: the same verdict, counted the same
     * way, without the per-record decision cache (doc/specs/log4j2-adapter.md A3; Log4j 2's
     * context-wide filter sees each call once, before any record exists). The default evaluates
     * against a fresh identity; {@link RuleService} skips the cache.
     */
    default GateVerdict evaluateOnce(RuleCandidateEvent event) {
        return evaluate(new Object(), event);
    }

    /**
     * doc/specs/rule-pipeline-foundation.md "Evaluation cost", R3: whether any rule can apply to
     * an event from this logger. Asked before building a {@link RuleCandidateEvent}, so an event
     * no rule can touch (the normal case) allocates nothing and takes no lock. {@code false}
     * guarantees {@link #evaluate} would return {@link GateVerdict#allow()}.
     */
    default boolean appliesTo(String loggerName) {
        return true;
    }

    /**
     * doc/specs/log4j2-adapter.md A3: the trim a render stage should apply to {@code event}, chosen
     * the same way {@link #evaluate} chooses it, but <em>never counted</em>. For a backend whose
     * gate runs before the event exists and whose render stage sees the event on another thread
     * (Log4j 2): the gate's {@link #evaluate} counts the hit once; each render stage that needs
     * the decision calls this instead of {@link #evaluate}, so a hit is never counted twice.
     *
     * <p>Considers {@link org.logaperture.api.LogRule trim} rules only. A drop rule that matched
     * has already stopped the event at the gate, and one that sampled it through leaves it a
     * survivor, which is exactly what this answers for. The result can differ from what the gate
     * chose if a rule changed in between; that split is accepted (A3, "As built").
     *
     * <p><strong>A gate that wraps or decorates another must override this</strong> and delegate:
     * the default answers "no trim", which silently stops trimming at the render stage while the
     * gate has already counted the hit.
     *
     * @return the decision, or {@code null} if no trim applies (including an event with no
     *         throwable)
     */
    default TrimDecision trimChoice(RuleCandidateEvent event) {
        return null;
    }
}
