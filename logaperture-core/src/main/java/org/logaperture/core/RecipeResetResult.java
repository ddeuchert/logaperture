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
 * {@code logctl reset recipe}'s result -- doc/specs/recipes.md B11.
 *
 * @param loggers     logger names put back
 * @param handlers    handler names put back
 * @param rules       rule ids removed
 * @param keptSticky  sticky changes left in place, as {@code logger <name>}, {@code handler <name>} or
 *                    {@code rule <id>} (no {@code --include-sticky}, #9)
 */
public record RecipeResetResult(String recipeId, List<String> loggers, List<String> handlers, List<String> rules,
        List<String> keptSticky) {

    public RecipeResetResult {
        Objects.requireNonNull(recipeId, "recipeId");
        loggers = List.copyOf(loggers);
        handlers = List.copyOf(handlers);
        rules = List.copyOf(rules);
        keptSticky = List.copyOf(keptSticky);
    }

    public int count() {
        return loggers.size() + handlers.size() + rules.size();
    }
}
