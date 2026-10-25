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
package org.logaperture.core.spi;

import org.logaperture.api.Level;

import java.util.Objects;

/**
 * What {@link LoggingAdapter#levelFloor} reports when a backend will not emit a logger's
 * effective level: the most verbose level it will emit, and, where it knows, how to lower the floor.
 *
 * @param lowest the most verbose level the backend actually emits for the logger
 * @param hint   what to change to lower the floor, or {@code null}
 */
public record LevelFloorInfo(Level lowest, String hint) {

    public LevelFloorInfo {
        Objects.requireNonNull(lowest, "lowest");
    }
}
