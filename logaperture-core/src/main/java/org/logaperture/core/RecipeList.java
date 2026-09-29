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
import java.util.Map;

/**
 * {@code logctl list recipes}'s result -- doc/specs/recipes.md.
 *
 * @param recipes      every listing, shadowed ones included (flagged), sorted by id then source
 * @param brokenFiles  recipe files that failed validation and contribute nothing (#11)
 * @param applied      by recipe id, the live changes still carrying it (B6); an id here with no
 *                     listing is applied but no longer offered (B7)
 */
public record RecipeList(List<RecipeListing> recipes, List<RecipeFileResult> brokenFiles,
        Map<String, RecipeApplication> applied) {

    public RecipeList {
        recipes = List.copyOf(recipes);
        brokenFiles = List.copyOf(brokenFiles);
        applied = Map.copyOf(applied);
    }

    /** What's on offer, before the live changes are looked at. */
    public RecipeList(List<RecipeListing> recipes, List<RecipeFileResult> brokenFiles) {
        this(recipes, brokenFiles, Map.of());
    }

    /** Recipe ids live changes carry that no source offers any more (B7), sorted. */
    public List<String> noLongerOffered() {
        return applied.keySet().stream()
                .filter(id -> recipes.stream().noneMatch(listing -> listing.id().equals(id)))
                .sorted().toList();
    }
}
