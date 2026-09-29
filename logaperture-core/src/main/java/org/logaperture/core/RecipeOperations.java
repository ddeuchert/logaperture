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

import java.util.List;

/**
 * {@code logctl list recipes} and {@code show recipe} -- doc/specs/recipes.md, slice (a). Both
 * read only; each call discovers afresh (epic #16), so a library loaded since the last call
 * shows up.
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
        };
    }
}
