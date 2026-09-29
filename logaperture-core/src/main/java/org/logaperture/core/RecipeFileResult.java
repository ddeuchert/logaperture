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
import java.util.Objects;

/**
 * One recipe file, read and validated -- doc/specs/recipes.md "The recipe file". All or nothing
 * (#11): a file with any {@code errors} contributes no recipes. {@code notes} are diagnostics that
 * don't reject the file -- a library file's other top-level keys, ignored (epic #15).
 */
public record RecipeFileResult(RecipeSource source, List<Recipe> recipes, List<String> errors, List<String> notes) {

    public RecipeFileResult {
        Objects.requireNonNull(source, "source");
        recipes = List.copyOf(recipes);
        errors = List.copyOf(errors);
        notes = List.copyOf(notes);
        if (!errors.isEmpty() && !recipes.isEmpty()) {
            throw new IllegalArgumentException("a file with errors contributes no recipes");
        }
    }

    static RecipeFileResult failed(RecipeSource source, String error) {
        return new RecipeFileResult(source, List.of(), List.of(error), List.of());
    }

    public boolean broken() {
        return !errors.isEmpty();
    }
}
