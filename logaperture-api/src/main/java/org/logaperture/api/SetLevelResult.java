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
 * {@code setLevel}'s result: every override it created, plus the union of
 * every handler on any of their target loggers' paths that will still
 * swallow records at the new level — doc/specs/handler-floor-control.md
 * "Warning on level commands". Advice, not an error: {@code
 * blockingHandlers} being non-empty never means the mutation failed.
 *
 * <p>{@code overrides} has exactly one entry for an exact-name target; for
 * a leading-star pattern target (doc/specs/pattern-selection-semantics.md)
 * it has one entry per currently-matched logger — every match is
 * unconditionally overwritten, none skipped (Precedence is retired) — and a
 * pattern matching no currently-known logger at all yields an
 * <em>empty</em> {@code overrides} list: nothing happened, and nothing is
 * left standing to happen later either. Always describes a call that
 * actually reached the mutation step: an unconfirmed pattern call never
 * produces one of these at all, it throws {@code
 * ConfirmationRequiredException} instead.
 *
 * @param overrides        the {@link LevelOverride}s this call created or
 *                         replaced — empty only for a pattern target with
 *                         no current match, never for an exact-name target
 * @param blockingHandlers handlers whose own level is below the level any
 *                         entry in {@code overrides} was just raised to, in
 *                         the order the adapter reported them, deduplicated
 *                         by handler across every target; empty if none, or
 *                         if the change wasn't a raise
 */
public record SetLevelResult(List<LevelOverride> overrides, List<HandlerFloor> blockingHandlers) {

    public SetLevelResult {
        Objects.requireNonNull(overrides, "overrides");
        overrides = List.copyOf(overrides);
        blockingHandlers = blockingHandlers == null ? List.of() : List.copyOf(blockingHandlers);
    }
}
