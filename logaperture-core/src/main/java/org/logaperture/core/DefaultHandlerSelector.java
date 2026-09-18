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

import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerRef;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.List;
import java.util.Optional;

/**
 * {@code DEFAULT_HANDLERS}'s deterministic initial-membership rule —
 * doc/specs/handler-floor-control.md "Deterministic initial-membership
 * rule", issue #28. Stateless: evaluated fresh against {@code adapter}'s
 * current facts every time {@link DefaultHandlerGroupRegistry#members} needs
 * it, nothing cached here. Always picks exactly one handler (or none, if
 * {@code adapter.realHandlers()} is itself empty) — never a set of
 * unrelated handlers, since the whole point is picking "the" one an
 * operator most likely means.
 */
final class DefaultHandlerSelector {

    private DefaultHandlerSelector() {
    }

    /**
     * @return the selected handler, per the four-step rule below; empty
     *         only when the adapter has no real handlers at all yet
     */
    static Optional<HandlerRef> select(LoggingAdapter adapter) {
        List<HandlerRef> reals = adapter.realHandlers();
        if (reals.isEmpty()) {
            return Optional.empty();
        }
        if (reals.size() == 1) {
            return Optional.of(reals.get(0)); // Step 1
        }

        List<HandlerRef> onRoot = adapter.handlersOnRoot();
        if (onRoot.size() == 1) {
            return Optional.of(onRoot.get(0)); // Step 2
        }
        if (!onRoot.isEmpty()) {
            return Optional.of(bestOfRoot(onRoot, adapter)); // Step 3
        }
        return Optional.of(reals.get(0)); // Step 4 -- multiple reals, none on root
    }

    /**
     * Step 3: ranks {@code onRoot} by tier (most to least specific console
     * match), first candidate in {@code onRoot}'s own attachment order wins
     * both within a tier and when no tier matches at all -- the strict
     * {@code <} comparison below means a later same-tier (or same-no-match)
     * candidate never displaces an earlier one.
     */
    private static HandlerRef bestOfRoot(List<HandlerRef> onRoot, LoggingAdapter adapter) {
        HandlerRef best = onRoot.get(0);
        int bestTier = tierOf(best, adapter);
        for (int i = 1; i < onRoot.size(); i++) {
            HandlerRef candidate = onRoot.get(i);
            int tier = tierOf(candidate, adapter);
            if (tier < bestTier) {
                best = candidate;
                bestTier = tier;
            }
        }
        return best;
    }

    /**
     * Lower is better: 1) a console handler named {@code CONSOLE}, 2) a
     * console handler (any name), 3) any handler named {@code CONSOLE}, or
     * {@link Integer#MAX_VALUE} for no match at all.
     */
    private static int tierOf(HandlerRef ref, LoggingAdapter adapter) {
        HandlerDiagnostics diagnostics = adapter.handlerDiagnostics(ref);
        boolean namedConsole = "CONSOLE".equals(ref.value());
        if (diagnostics.isConsole()) {
            return namedConsole ? 1 : 2;
        }
        return namedConsole ? 3 : Integer.MAX_VALUE;
    }
}
