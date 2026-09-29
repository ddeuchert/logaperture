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

import org.logaperture.core.RecipeApplyResult;

import java.beans.ConstructorProperties;
import java.util.List;

/** {@code logctl apply recipe}'s result -- doc/specs/recipes.md B10. */
public final class RecipeApplyResultData {

    private final RecipeData recipe;
    private final String tier;
    private final String expiresAt;
    private final List<RecipeChangeData> applied;
    private final List<RecipeChangeData> skipped;
    private final List<String> ruleIds;

    @ConstructorProperties({"recipe", "tier", "expiresAt", "applied", "skipped", "ruleIds"})
    public RecipeApplyResultData(RecipeData recipe, String tier, String expiresAt, List<RecipeChangeData> applied, List<RecipeChangeData> skipped, List<String> ruleIds) {
        this.recipe = recipe;
        this.tier = tier;
        this.expiresAt = expiresAt;
        this.applied = applied;
        this.skipped = skipped;
        this.ruleIds = ruleIds;
    }

    public static RecipeApplyResultData from(RecipeApplyResult result) {
        return new RecipeApplyResultData(RecipeData.from(result.listing(), null), result.tier().name(),
                result.expiresAt() == null ? null : result.expiresAt().toString(),
                result.applied().stream().map(RecipeChangeData::from).toList(),
                result.skipped().stream().map(RecipeChangeData::from).toList(), result.ruleIds());
    }

    public RecipeData getRecipe() {
        return recipe;
    }

    /** {@code SESSION}, {@code FOR} or {@code STICKY}. */
    public String getTier() {
        return tier;
    }

    /** When a {@code FOR} tier ends (ISO-8601), else {@code null}. */
    public String getExpiresAt() {
        return expiresAt;
    }

    /** Each change made: loggers, handlers, rules. */
    public List<RecipeChangeData> getApplied() {
        return applied;
    }

    /** Each entry left out, with why in its note (#4, B8). */
    public List<RecipeChangeData> getSkipped() {
        return skipped;
    }

    /** The ids the recipe's rules were added under, in the order of its rules. */
    public List<String> getRuleIds() {
        return ruleIds;
    }
}
