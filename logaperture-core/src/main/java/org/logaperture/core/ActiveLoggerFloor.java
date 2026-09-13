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

import org.logaperture.api.Level;

import java.util.Optional;

/**
 * Supplies the most verbose level among this context's currently-active
 * logger overrides — the value an {@code AUTO} handler override tracks
 * (doc/specs/handler-floor-control.md "AUTO handler level", issue #20,
 * "Scope of 'lowest active override'": every active override in the same
 * logging context, no per-logger routing). Empty when none are active,
 * meaning an {@code AUTO} handler should be at its own baseline.
 *
 * <p>Constructor-injected into {@link HandlerLevelControlService} so it can
 * ask this at both {@code setHandlerAuto} activation time and on every
 * reactive recompute, without referencing {@link LevelControlService} or
 * {@link OverrideRegistry} directly — the composition root (each container's
 * {@code installContext}) supplies the real implementation, closing over the
 * same context's {@link OverrideRegistry} it already builds for {@link
 * LevelControlService}. Default: {@link #NONE}, for every context that
 * doesn't need this.
 */
public interface ActiveLoggerFloor {

    Optional<Level> lowestActive();

    ActiveLoggerFloor NONE = Optional::empty;

    /**
     * The most verbose ({@link Level#compareTo} minimum — {@code Level} is
     * declared verbose-to-quiet, so its natural ordering doubles as
     * verbosity) level among {@code overrides}, or empty if it's empty.
     */
    static Optional<Level> lowestOf(java.util.Collection<org.logaperture.api.LevelOverride> overrides) {
        return overrides.stream().map(org.logaperture.api.LevelOverride::level).min(Level::compareTo);
    }
}
