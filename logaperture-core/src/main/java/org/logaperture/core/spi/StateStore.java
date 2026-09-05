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

import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.LevelOverride;

import java.util.List;

/**
 * Pluggable persistence for {@code --for}/{@code --sticky} overrides — see
 * doc/specs/persistence.md "{@code StateStore} SPI". {@code core} ships one
 * implementation ({@code FileStateStore}); a shared/external store for
 * containerized or clustered deployments is a later implementation of this
 * same interface, per doc/logaperture-spec.md §18.6.
 *
 * <p>Handler-level overrides (doc/specs/handler-floor-control.md) are
 * persisted through the same store, in their own namespace — one file per
 * instance stays the unit of "everything this JVM has active", per
 * persistence.md's "single hand-editable file" bar.
 */
public interface StateStore {

    /** Every persisted logger override, in no particular order. */
    List<LevelOverride> loadAll();

    /** Upserts by {@code override.loggerName()} — one entry per logger, same as {@code OverrideRegistry}. */
    void save(LevelOverride override);

    /** No-op if {@code loggerName} was never persisted. */
    void remove(String loggerName);

    /** Every persisted handler override, in no particular order. */
    List<HandlerLevelOverride> loadAllHandlers();

    /** Upserts by {@code override.handlerRef()} — one entry per handler, same as {@code HandlerOverrideRegistry}. */
    void saveHandler(HandlerLevelOverride override);

    /** No-op if {@code ref} was never persisted. */
    void removeHandler(HandlerRef ref);

    /** Removes every persisted entry, logger and handler alike. */
    void clear();

    /**
     * A {@link StateStore} that persists nothing — the degraded-mode target
     * when this JVM's instance identity collides with another live process
     * (doc/specs/persistence.md "The same-working-directory collision"),
     * and a convenient collaborator for tests that don't exercise
     * persistence at all.
     */
    static StateStore noOp() {
        return new StateStore() {
            @Override
            public List<LevelOverride> loadAll() {
                return List.of();
            }

            @Override
            public void save(LevelOverride override) {
                // discarded, deliberately
            }

            @Override
            public void remove(String loggerName) {
                // nothing to remove
            }

            @Override
            public List<HandlerLevelOverride> loadAllHandlers() {
                return List.of();
            }

            @Override
            public void saveHandler(HandlerLevelOverride override) {
                // discarded, deliberately
            }

            @Override
            public void removeHandler(HandlerRef ref) {
                // nothing to remove
            }

            @Override
            public void clear() {
                // nothing to clear
            }
        };
    }
}
