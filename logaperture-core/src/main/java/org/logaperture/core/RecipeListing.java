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
 * One row of {@code logctl list recipes} -- doc/specs/recipes.md, collisions per epic #20.
 *
 * @param recipe    the recipe; its {@link Recipe#source() source} is the first of {@code sources}
 * @param sources   every source offering this exact recipe (identical same-id recipes merge)
 * @param ambiguous another listed recipe has the same id with different content: {@code show} and
 *                  {@code apply} need {@code --from}
 * @param shadowed  a vendor defaults file or recipes folder recipe has the same id, and wins; listed
 *                  only by {@code --verbose}
 */
public record RecipeListing(Recipe recipe, List<RecipeSource> sources, boolean ambiguous, boolean shadowed) {

    public RecipeListing {
        Objects.requireNonNull(recipe, "recipe");
        sources = List.copyOf(sources);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("a listing has at least one source");
        }
    }

    public String id() {
        return recipe.id();
    }

    /** Whether {@code from} names one of its sources, by label or by full location. */
    boolean offeredBy(String from) {
        return sources.stream().anyMatch(source -> source.label().equals(from) || source.location().equals(from));
    }
}
