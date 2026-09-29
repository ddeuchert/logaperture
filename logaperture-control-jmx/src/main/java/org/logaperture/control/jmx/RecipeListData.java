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

import org.logaperture.core.RecipeList;
import org.logaperture.core.RecipeListing;

import java.beans.ConstructorProperties;
import java.util.ArrayList;
import java.util.List;

/** {@code logctl list recipes}'s result -- doc/specs/recipes.md. */
public final class RecipeListData {

    private final List<RecipeData> recipes;
    private final List<RecipeFileProblemData> brokenFiles;

    @ConstructorProperties({"recipes", "brokenFiles"})
    public RecipeListData(List<RecipeData> recipes, List<RecipeFileProblemData> brokenFiles) {
        this.recipes = recipes;
        this.brokenFiles = brokenFiles;
    }

    public static RecipeListData from(RecipeList list) {
        List<RecipeData> rows = new ArrayList<>();
        for (RecipeListing listing : list.recipes()) {
            rows.add(RecipeData.from(listing, list.applied().get(listing.id())));
        }
        for (String id : list.noLongerOffered()) {
            rows.add(RecipeData.noLongerOffered(list.applied().get(id)));
        }
        return new RecipeListData(rows, list.brokenFiles().stream().map(RecipeFileProblemData::from).toList());
    }

    /** Every recipe, shadowed ones included and flagged, then any applied but no longer offered (B7). */
    public List<RecipeData> getRecipes() {
        return recipes;
    }

    /** Recipe files that couldn't be read (#11). */
    public List<RecipeFileProblemData> getBrokenFiles() {
        return brokenFiles;
    }
}
