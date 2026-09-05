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

import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.SetHandlerLevelOptions;

import java.util.Optional;

/**
 * {@code logctl handler}'s public contract — the {@link
 * LevelControlOperations} counterpart for handlers (doc/specs/
 * handler-floor-control.md "Operations impact"). Every control surface is a
 * client of this interface, same as {@link LevelControlOperations}.
 */
public interface HandlerLevelControlOperations {

    /**
     * @return the created override, or empty if the underlying adapter's
     *         handlers have no level of their own (doc/specs/
     *         handler-floor-control.md "Logback / none") — a documented
     *         no-op, not an error; nothing is tracked or persisted
     */
    Optional<HandlerLevelOverride> setHandlerLevel(HandlerRef ref, Level level, SetHandlerLevelOptions options);

    void resetHandler(HandlerRef ref);
}
