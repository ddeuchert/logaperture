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
import org.logaperture.api.LevelOverride;
import org.logaperture.core.spi.StateStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hand-written {@link StateStore} test double — no filesystem, no
 * locking, so {@link LevelControlService}'s own tests can exercise the
 * persistence seam without depending on {@link FileStateStore}'s I/O.
 */
final class InMemoryStateStore implements StateStore {

    private final Map<String, LevelOverride> saved = new LinkedHashMap<>();
    private final Map<HandlerRef, HandlerLevelOverride> savedHandlers = new LinkedHashMap<>();
    private RuntimeException throwOnSave;

    /** Makes every subsequent {@link #save} call throw, to exercise chaos-case behavior. */
    void throwOnSave(RuntimeException exception) {
        this.throwOnSave = exception;
    }

    @Override
    public List<LevelOverride> loadAll() {
        return List.copyOf(saved.values());
    }

    @Override
    public void save(LevelOverride override) {
        if (throwOnSave != null) {
            throw throwOnSave;
        }
        saved.put(override.loggerName(), override);
    }

    @Override
    public void remove(String loggerName) {
        saved.remove(loggerName);
    }

    @Override
    public List<HandlerLevelOverride> loadAllHandlers() {
        return List.copyOf(savedHandlers.values());
    }

    @Override
    public void saveHandler(HandlerLevelOverride override) {
        if (throwOnSave != null) {
            throw throwOnSave;
        }
        savedHandlers.put(override.handlerRef(), override);
    }

    @Override
    public void removeHandler(HandlerRef ref) {
        savedHandlers.remove(ref);
    }

    @Override
    public void clear() {
        saved.clear();
        savedHandlers.clear();
    }
}
