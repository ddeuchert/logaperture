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

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A default handler a {@code set logger} let through (doc/specs/default-handlers-follow.md): either
 * it now follows the change in {@code AUTO}, or ({@code carriesOn}) the operator's own override on
 * it already lets the records through but ends sooner, and it follows the change from then on.
 *
 * @param handlerRef    the handler
 * @param previousLevel its level before this call
 * @param level         its level now
 * @param follows       the logger changes it follows (or will follow), in name order
 * @param carriesOn     whether the operator's own override is still in force and following starts
 *                      when it ends
 * @param ownTier       with {@code carriesOn}, that override's tier; else {@code null}
 * @param ownExpiresAt  with {@code carriesOn}, that override's deadline for a {@code FOR} tier; else
 *                      {@code null}
 */
public record FollowedHandler(HandlerRef handlerRef, Level previousLevel, Level level, List<String> follows,
        boolean carriesOn, PersistenceTier ownTier, Instant ownExpiresAt) {

    public FollowedHandler {
        Objects.requireNonNull(handlerRef, "handlerRef");
        Objects.requireNonNull(level, "level");
        follows = follows == null ? List.of() : List.copyOf(follows);
    }
}
