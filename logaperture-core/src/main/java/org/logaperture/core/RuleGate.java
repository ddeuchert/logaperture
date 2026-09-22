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
}
