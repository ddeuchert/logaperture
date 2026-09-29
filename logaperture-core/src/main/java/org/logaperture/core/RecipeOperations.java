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

import org.logaperture.api.DoctorFinding;
import org.logaperture.api.PersistenceTier;

import java.time.Duration;
import java.util.List;

/**
 * {@code logctl list recipes}, {@code show recipe}, {@code apply recipe} and {@code reset recipe}
 * -- doc/specs/recipes.md. Each call discovers afresh (epic #16), so a library loaded since the
 * last call shows up.
 */
public interface RecipeOperations {

    /** Every recipe on offer, plus the recipe files that couldn't be read. Requires {@link Capability#VIEW}. */
    RecipeList listRecipes();

    /**
     * One recipe with what it would change, against the live levels. Requires {@link
     * Capability#VIEW}.
     *
     * @param from a source label or location, to pick among same-id recipes (epic #20); {@code null}
     *             when there's only one
     * @throws IllegalArgumentException if no recipe has {@code id}, or several do and {@code from}
     *                                  doesn't pick one
     */
    RecipeDetail showRecipe(String id, String from);

    /**
     * {@code logctl doctor}'s recipe-files check (doc/specs/recipes.md #11): one {@code INFO}
     * finding per recipe file that can't be read, an {@code OK} row when every file reads cleanly,
     * and nothing when there are no recipe files at all.
     */
    List<DoctorFinding> recipeFindings();

    /**
     * Switches a recipe on -- doc/specs/recipes.md "logctl apply recipe", #5, B1-B3. Every change is
     * checked before any is made; a library's entry that would lower a level is skipped.
     *
     * @param fingerprint what {@link #showRecipe} returned, so a recipe changed since then applies
     *                    nothing (B1); {@code null} when nothing was shown ({@code --yes})
     * @param expiresIn   for {@code FOR}, else {@code null}
     * @throws IllegalArgumentException if the recipe can't be found or picked, changed since shown, or
     *                                  any change would be refused -- nothing is applied
     * @throws IllegalStateException    if a change fails part-way through; what applied stays (B2)
     */
    RecipeApplyResult applyRecipe(String id, String from, String fingerprint, String reason, PersistenceTier tier,
            Duration expiresIn);

    /**
     * Switches a recipe off -- doc/specs/recipes.md "logctl reset recipe", #9: puts back every change
     * still carrying its id. Sticky ones are kept unless {@code includeSticky}. Works for a recipe no
     * longer on offer (B7).
     */
    RecipeResetResult resetRecipe(String id, boolean includeSticky);

    /** No recipes anywhere -- a control surface built without a catalog. */
    static RecipeOperations none() {
        return new RecipeOperations() {
            @Override
            public RecipeList listRecipes() {
                return new RecipeList(List.of(), List.of());
            }

            @Override
            public RecipeDetail showRecipe(String id, String from) {
                throw new IllegalArgumentException("no recipe named '" + id + "'");
            }

            @Override
            public List<DoctorFinding> recipeFindings() {
                return List.of();
            }

            @Override
            public RecipeApplyResult applyRecipe(String id, String from, String fingerprint, String reason,
                    PersistenceTier tier, Duration expiresIn) {
                throw new IllegalArgumentException("no recipe named '" + id + "'");
            }

            @Override
            public RecipeResetResult resetRecipe(String id, boolean includeSticky) {
                return new RecipeResetResult(id, List.of(), List.of(), List.of(), List.of());
            }
        };
    }
}
