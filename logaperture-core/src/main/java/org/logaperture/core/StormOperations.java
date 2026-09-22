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

import org.logaperture.api.StormReport;

/**
 * {@code logctl storms}'s public contract — the {@link TopOperations}
 * counterpart for storm detection (doc/specs/storm-detection.md). Every
 * control surface is a client of this interface, same as {@link
 * TopOperations}/{@link DoctorOperations}.
 */
public interface StormOperations {

    /**
     * Every storm the detector has recorded, sorted worst-first (ongoing
     * before ended, then by event count).
     *
     * @param limit keep only the {@code limit} worst storms; {@code limit <=
     *              0} means "every tracked storm" (mirrors {@link
     *              TopOperations#topLoggers}'s convention)
     */
    StormReport activeStorms(int limit);
}
