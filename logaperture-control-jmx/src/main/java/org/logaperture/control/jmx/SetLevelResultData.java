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

import org.logaperture.api.SetLevelResult;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * MXBean-friendly mirror of {@link SetLevelResult} — {@code setLevel}'s
 * return type (doc/specs/handler-floor-control.md "Warning on level
 * commands"). {@code overrides} has one entry for an exact-name target;
 * for a pattern target (doc/specs/pattern-level-targeting.md) it has one
 * entry per currently-matched logger the call actually mutated, possibly
 * empty if the pattern matches no currently-known logger yet.
 * {@code blockingHandlers} is empty on every {@code setLevel} call that
 * isn't a raise past a handler floor, which is the common case.
 */
public final class SetLevelResultData {

    private final List<LevelOverrideData> overrides;
    private final List<HandlerFloorData> blockingHandlers;
    private final List<DescendantLevelData> descendants;
    private final List<FollowedHandlerData> following;
    private final String followDenied;
    private final List<LevelFloorData> belowFloor;

    /**
     * Every field, including {@code belowFloor} (doc/specs/quarkus-support.md). Additive: a result
     * from an agent that predates a field still converts, through the shorter constructors below.
     */
    @ConstructorProperties({"overrides", "blockingHandlers", "descendants", "following", "followDenied",
            "belowFloor"})
    public SetLevelResultData(List<LevelOverrideData> overrides, List<HandlerFloorData> blockingHandlers,
            List<DescendantLevelData> descendants, List<FollowedHandlerData> following, String followDenied,
            List<LevelFloorData> belowFloor) {
        this.overrides = overrides;
        this.blockingHandlers = blockingHandlers;
        this.descendants = descendants == null ? List.of() : descendants;
        this.following = following == null ? List.of() : following;
        this.followDenied = followDenied;
        this.belowFloor = belowFloor == null ? List.of() : belowFloor;
    }

    /** Every field but {@code belowFloor} (doc/specs/default-handlers-follow.md). */
    @ConstructorProperties({"overrides", "blockingHandlers", "descendants", "following", "followDenied"})
    public SetLevelResultData(List<LevelOverrideData> overrides, List<HandlerFloorData> blockingHandlers,
            List<DescendantLevelData> descendants, List<FollowedHandlerData> following, String followDenied) {
        this(overrides, blockingHandlers, descendants, following, followDenied, List.of());
    }

    /** Every field but {@code following}, {@code followDenied} and {@code belowFloor} (doc/specs/set-logger-force.md). */
    @ConstructorProperties({"overrides", "blockingHandlers", "descendants"})
    public SetLevelResultData(List<LevelOverrideData> overrides, List<HandlerFloorData> blockingHandlers,
            List<DescendantLevelData> descendants) {
        this(overrides, blockingHandlers, descendants, List.of(), null, List.of());
    }

    @ConstructorProperties({"overrides", "blockingHandlers"})
    public SetLevelResultData(List<LevelOverrideData> overrides, List<HandlerFloorData> blockingHandlers) {
        this(overrides, blockingHandlers, List.of());
    }

    public static SetLevelResultData from(SetLevelResult result) {
        return new SetLevelResultData(
                result.overrides().stream().map(LevelOverrideData::from).toList(),
                result.blockingHandlers().stream().map(HandlerFloorData::from).toList(),
                result.descendants().stream().map(DescendantLevelData::from).toList(),
                result.following().stream().map(FollowedHandlerData::from).toList(),
                result.followDenied(),
                result.belowFloor().stream().map(LevelFloorData::from).toList());
    }

    /** The default handlers this change let through -- see {@link SetLevelResult#following()}. */
    public List<FollowedHandlerData> getFollowing() {
        return following;
    }

    /** The capability that kept the default handlers from following, or {@code null} -- see {@link SetLevelResult#followDenied()}. */
    public String getFollowDenied() {
        return followDenied;
    }

    /** Levels set that the backend won't emit -- see {@link SetLevelResult#belowFloor()}. */
    public List<LevelFloorData> getBelowFloor() {
        return belowFloor;
    }

    /** Loggers under an exact target that didn't follow its new level -- see {@link SetLevelResult#descendants()}. */
    public List<DescendantLevelData> getDescendants() {
        return descendants;
    }

    public List<LevelOverrideData> getOverrides() {
        return overrides;
    }

    public List<HandlerFloorData> getBlockingHandlers() {
        return blockingHandlers;
    }
}
