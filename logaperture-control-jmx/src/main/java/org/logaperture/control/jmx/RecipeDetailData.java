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

import org.logaperture.core.RecipeDetail;

import java.beans.ConstructorProperties;
import java.util.List;

/** {@code logctl show recipe}'s result -- doc/specs/recipes.md "logctl show recipe". */
public final class RecipeDetailData {

    private final RecipeData recipe;
    private final String description;
    private final List<RecipeChangeData> changes;

    @ConstructorProperties({"recipe", "description", "changes"})
    public RecipeDetailData(RecipeData recipe, String description, List<RecipeChangeData> changes) {
        this.recipe = recipe;
        this.description = description;
        this.changes = changes;
    }

    public static RecipeDetailData from(RecipeDetail detail) {
        return new RecipeDetailData(RecipeData.from(detail.listing()), detail.listing().recipe().description(),
                detail.changes().stream().map(RecipeChangeData::from).toList());
    }

    public RecipeData getRecipe() {
        return recipe;
    }

    /** Multi-line text, or {@code null}. */
    public String getDescription() {
        return description;
    }

    public List<RecipeChangeData> getChanges() {
        return changes;
    }
}
