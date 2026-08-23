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

import org.logaperture.api.LevelOverride;

import java.util.List;

/**
 * Pluggable persistence for {@code --for}/{@code --sticky} overrides — see
 * doc/specs/persistence.md "{@code StateStore} SPI". {@code core} ships one
 * implementation ({@code FileStateStore}); a shared/external store for
 * containerized or clustered deployments is a later implementation of this
 * same interface, per doc/logaperture-spec.md §18.6.
 */
public interface StateStore {

    /** Every persisted override, in no particular order. */
    List<LevelOverride> loadAll();

    /** Upserts by {@code override.loggerName()} — one entry per logger, same as {@code OverrideRegistry}. */
    void save(LevelOverride override);

    /** No-op if {@code loggerName} was never persisted. */
    void remove(String loggerName);

    /** Removes every persisted entry. */
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
            public void clear() {
                // nothing to clear
            }
        };
    }
}
