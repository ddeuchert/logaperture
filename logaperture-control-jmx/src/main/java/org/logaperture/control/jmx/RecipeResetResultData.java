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

import org.logaperture.core.RecipeResetResult;

import java.beans.ConstructorProperties;
import java.util.List;

/** {@code logctl reset recipe}'s result -- doc/specs/recipes.md B11. */
public final class RecipeResetResultData {

    private final String recipeId;
    private final List<String> loggers;
    private final List<String> handlers;
    private final List<String> rules;
    private final List<String> keptSticky;

    @ConstructorProperties({"recipeId", "loggers", "handlers", "rules", "keptSticky"})
    public RecipeResetResultData(String recipeId, List<String> loggers, List<String> handlers, List<String> rules, List<String> keptSticky) {
        this.recipeId = recipeId;
        this.loggers = loggers;
        this.handlers = handlers;
        this.rules = rules;
        this.keptSticky = keptSticky;
    }

    public static RecipeResetResultData from(RecipeResetResult result) {
        return new RecipeResetResultData(result.recipeId(), result.loggers(), result.handlers(), result.rules(),
                result.keptSticky());
    }

    public String getRecipeId() {
        return recipeId;
    }

    /** Logger names put back. */
    public List<String> getLoggers() {
        return loggers;
    }

    /** Handler names put back. */
    public List<String> getHandlers() {
        return handlers;
    }

    /** Rule ids removed. */
    public List<String> getRules() {
        return rules;
    }

    /** Sticky changes left in place: {@code logger <name>}, {@code handler <name>} or {@code rule <id>}. */
    public List<String> getKeptSticky() {
        return keptSticky;
    }
}
