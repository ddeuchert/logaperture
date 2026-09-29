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
 * {@code logctl show recipe}'s result -- doc/specs/recipes.md "logctl show recipe <id>": the
 * recipe and each change it would make, against the live levels.
 */
public record RecipeDetail(RecipeListing listing, List<Change> changes) {

    /**
     * One change.
     *
     * @param kind         {@code logger}, {@code handler}, {@code rule drop} or {@code rule trim}
     * @param target       the logger or handler name
     * @param currentLevel the live effective level ({@code null} for a rule, or a logger or handler
     *                     not known yet)
     * @param newLevel     the recipe's level ({@code AUTO} possible for a handler; {@code null} for
     *                     a rule)
     * @param detail       a rule's options as {@code add rule} takes them, else the entry's reason,
     *                     or {@code null}
     * @param note         why this change would be skipped or refused, or {@code null} (#4, #19)
     */
    public record Change(String kind, String target, String currentLevel, String newLevel, String detail,
            String note) {
        public Change {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
        }
    }

    public RecipeDetail {
        Objects.requireNonNull(listing, "listing");
        changes = List.copyOf(changes);
    }
}
