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

import java.util.List;
import java.util.Objects;

/**
 * {@code setLevel}'s result: the override it created, plus every handler on
 * the target logger's path that will still swallow records at the new level
 * — doc/specs/handler-floor-control.md "Warning on level commands". Advice,
 * not an error: {@code blockingHandlers} being non-empty never means {@code
 * override} is {@code null} or the mutation failed.
 *
 * @param override         the {@link LevelOverride} {@code setLevel} created
 * @param blockingHandlers handlers whose own level is below {@code
 *                         override.level()}, in the order the adapter
 *                         reported them; empty if none, or if the change
 *                         wasn't a raise
 */
public record SetLevelResult(LevelOverride override, List<HandlerFloor> blockingHandlers) {

    public SetLevelResult {
        Objects.requireNonNull(override, "override");
        blockingHandlers = blockingHandlers == null ? List.of() : List.copyOf(blockingHandlers);
    }
}
