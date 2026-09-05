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

import org.logaperture.api.HandlerFloor;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link HandlerFloor} — see {@link
 * LoggerInfoData} for the pattern's rationale. One of these names a handler
 * that will still swallow the level a {@code setLevel} call just applied
 * (doc/specs/handler-floor-control.md "Warning on level commands").
 */
public final class HandlerFloorData {

    private final String handlerRef;
    private final String currentLevel;

    @ConstructorProperties({"handlerRef", "currentLevel"})
    public HandlerFloorData(String handlerRef, String currentLevel) {
        this.handlerRef = handlerRef;
        this.currentLevel = currentLevel;
    }

    public static HandlerFloorData from(HandlerFloor floor) {
        return new HandlerFloorData(floor.handlerRef().value(), floor.currentLevel().name());
    }

    public String getHandlerRef() {
        return handlerRef;
    }

    public String getCurrentLevel() {
        return currentLevel;
    }
}
