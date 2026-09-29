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

import java.util.List;

/**
 * {@code logctl list recipes}'s result -- doc/specs/recipes.md.
 *
 * @param recipes      every listing, shadowed ones included (flagged), sorted by id then source
 * @param brokenFiles  recipe files that failed validation and contribute nothing (#11)
 */
public record RecipeList(List<RecipeListing> recipes, List<RecipeFileResult> brokenFiles) {

    public RecipeList {
        recipes = List.copyOf(recipes);
        brokenFiles = List.copyOf(brokenFiles);
    }
}
