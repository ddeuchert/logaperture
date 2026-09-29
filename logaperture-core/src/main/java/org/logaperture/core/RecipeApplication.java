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

import org.logaperture.api.PersistenceTier;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * The live changes that still carry one recipe's id -- doc/specs/recipes.md #7, B6.
 *
 * @param loggers   logger names with an override the recipe made
 * @param handlers  handler names with an override the recipe made
 * @param rules     how many rules the recipe added are still attached
 * @param tier      the tier of the change that ends soonest: {@code FOR} (earliest expiry), then
 *                  {@code SESSION}, then {@code STICKY}
 * @param expiresAt that change's expiry when {@code tier} is {@code FOR}, else {@code null}
 */
public record RecipeApplication(String recipeId, Set<String> loggers, Set<String> handlers, int rules,
        PersistenceTier tier, Instant expiresAt) {

    public RecipeApplication {
        Objects.requireNonNull(recipeId, "recipeId");
        loggers = Set.copyOf(loggers);
        handlers = Set.copyOf(handlers);
        Objects.requireNonNull(tier, "tier");
    }

    /** Every change still carrying the id. */
    public int count() {
        return loggers.size() + handlers.size() + rules;
    }

    /** How many of {@code recipe}'s entries still carry its id (B6's "N of M"). */
    public int countOf(Recipe recipe) {
        long loggerCount = recipe.loggers().stream().filter(logger -> loggers.contains(logger.name())).count();
        long handlerCount = recipe.handlers().stream().filter(handler -> handlers.contains(handler.ref().value()))
                .count();
        return (int) (loggerCount + handlerCount + Math.min(rules, recipe.rules().size()));
    }

    /** How many entries {@code recipe} has (B6's "M"). */
    public static int entriesOf(Recipe recipe) {
        return recipe.loggers().size() + recipe.handlers().size() + recipe.rules().size();
    }
}
