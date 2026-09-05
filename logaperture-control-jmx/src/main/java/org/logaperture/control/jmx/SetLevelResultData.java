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
 * MXBean-friendly mirror of {@link SetLevelResult} — {@code setLevel}'s new
 * return type (doc/specs/handler-floor-control.md "Warning on level
 * commands"). {@code blockingHandlers} is empty on every {@code setLevel}
 * call that isn't a raise past a handler floor, which is the common case.
 */
public final class SetLevelResultData {

    private final LevelOverrideData override;
    private final List<HandlerFloorData> blockingHandlers;

    @ConstructorProperties({"override", "blockingHandlers"})
    public SetLevelResultData(LevelOverrideData override, List<HandlerFloorData> blockingHandlers) {
        this.override = override;
        this.blockingHandlers = blockingHandlers;
    }

    public static SetLevelResultData from(SetLevelResult result) {
        return new SetLevelResultData(
                LevelOverrideData.from(result.override()),
                result.blockingHandlers().stream().map(HandlerFloorData::from).toList());
    }

    public LevelOverrideData getOverride() {
        return override;
    }

    public List<HandlerFloorData> getBlockingHandlers() {
        return blockingHandlers;
    }
}
