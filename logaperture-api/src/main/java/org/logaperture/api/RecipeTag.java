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
package org.logaperture.api;

import java.util.Objects;

/**
 * Marks a change as made by applying a recipe -- doc/specs/recipes.md #6, #13 and B4/B5.
 *
 * @param id       the recipe id, {@code <namespace>:<name>}, recorded on the override or rule
 * @param location where the recipe was read from, for the audit record only
 */
public record RecipeTag(String id, String location) {

    public RecipeTag {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(location, "location");
    }

    /** The audit record's {@code origin} (B5), e.g. {@code recipe io.undertow:sessions from jar:file:...}. */
    public String origin() {
        return "recipe " + id + " from " + location;
    }
}
