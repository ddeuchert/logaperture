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

import org.logaperture.api.FollowedHandler;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * {@link FollowedHandler} as open data: a default handler a {@code set logger} let through
 * (doc/specs/default-handlers-follow.md). Levels, tier and deadline as strings, like {@link
 * HandlerLevelOverrideData}.
 */
public final class FollowedHandlerData {

    private final String handlerRef;
    private final String previousLevel;
    private final String level;
    private final List<String> follows;
    private final boolean carriesOn;
    private final String ownTier;
    private final String ownExpiresAt;

    @ConstructorProperties({"handlerRef", "previousLevel", "level", "follows", "carriesOn", "ownTier",
            "ownExpiresAt"})
    public FollowedHandlerData(String handlerRef, String previousLevel, String level, List<String> follows,
            boolean carriesOn, String ownTier, String ownExpiresAt) {
        this.handlerRef = handlerRef;
        this.previousLevel = previousLevel;
        this.level = level;
        this.follows = follows == null ? List.of() : follows;
        this.carriesOn = carriesOn;
        this.ownTier = ownTier;
        this.ownExpiresAt = ownExpiresAt;
    }

    public static FollowedHandlerData from(FollowedHandler followed) {
        return new FollowedHandlerData(followed.handlerRef().value(),
                followed.previousLevel() == null ? null : followed.previousLevel().name(),
                followed.level().name(), followed.follows(), followed.carriesOn(),
                followed.ownTier() == null ? null : followed.ownTier().name(),
                followed.ownExpiresAt() == null ? null : followed.ownExpiresAt().toString());
    }

    public String getHandlerRef() {
        return handlerRef;
    }

    public String getPreviousLevel() {
        return previousLevel;
    }

    public String getLevel() {
        return level;
    }

    /** The logger changes it follows, or will follow once its own override ends. */
    public List<String> getFollows() {
        return follows;
    }

    /** Whether the operator's own override is still in force and following starts when it ends. */
    public boolean isCarriesOn() {
        return carriesOn;
    }

    /** With {@link #isCarriesOn()}, that override's tier; else {@code null}. */
    public String getOwnTier() {
        return ownTier;
    }

    /** With {@link #isCarriesOn()}, that override's deadline for a {@code FOR} tier; else {@code null}. */
    public String getOwnExpiresAt() {
        return ownExpiresAt;
    }
}
