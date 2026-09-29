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

import org.logaperture.api.PersistenceTier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code logctl apply recipe}'s result -- doc/specs/recipes.md B10.
 *
 * @param applied   each change made, in order: loggers, handlers, rules
 * @param skipped   each entry left out, with why (#4, B8)
 * @param ruleIds   the ids the recipe's rules were added under, in the order of its {@code rules}
 * @param expiresAt when a {@code FOR} tier ends, else {@code null}
 */
public record RecipeApplyResult(RecipeListing listing, PersistenceTier tier, Instant expiresAt,
        List<RecipeDetail.Change> applied, List<RecipeDetail.Change> skipped, List<String> ruleIds) {

    public RecipeApplyResult {
        Objects.requireNonNull(listing, "listing");
        Objects.requireNonNull(tier, "tier");
        applied = List.copyOf(applied);
        skipped = List.copyOf(skipped);
        ruleIds = List.copyOf(ruleIds);
    }
}
