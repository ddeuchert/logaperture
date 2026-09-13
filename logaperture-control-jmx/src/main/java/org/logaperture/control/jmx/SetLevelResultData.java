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

    @ConstructorProperties({"overrides", "blockingHandlers"})
    public SetLevelResultData(List<LevelOverrideData> overrides, List<HandlerFloorData> blockingHandlers) {
        this.overrides = overrides;
        this.blockingHandlers = blockingHandlers;
    }

    public static SetLevelResultData from(SetLevelResult result) {
        return new SetLevelResultData(
                result.overrides().stream().map(LevelOverrideData::from).toList(),
                result.blockingHandlers().stream().map(HandlerFloorData::from).toList());
    }

    public List<LevelOverrideData> getOverrides() {
        return overrides;
    }

    public List<HandlerFloorData> getBlockingHandlers() {
        return blockingHandlers;
    }
}
