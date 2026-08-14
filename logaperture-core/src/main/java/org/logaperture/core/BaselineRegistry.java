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
import org.logaperture.core.spi.LoggingAdapter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures each logger's baseline (framework-configured) level once,
 * lazily — doc/specs/level-control.md: "Without this, {@code
 * resetLevel}/{@code resetAll} are undefined, and there is no way to
 * distinguish 'the operator set INFO' from 'the app was already INFO'."
 *
 * <p>Presence of a key distinguishes "captured" from "not yet captured";
 * the stored {@link Optional} distinguishes "captured as an explicit
 * level" from "captured as inherited" (no explicit level at capture time).
 */
public final class BaselineRegistry {

    private final Map<String, Optional<Level>> captured = new ConcurrentHashMap<>();

    /**
     * Captures {@code name}'s baseline from {@code adapter} the first time
     * it's asked about; subsequent calls return the already-captured value
     * without touching the adapter again.
     */
    public Optional<Level> captureIfAbsent(String name, LoggingAdapter adapter) {
        return captured.computeIfAbsent(name, adapter::configuredLevel);
    }

    public boolean isCaptured(String name) {
        return captured.containsKey(name);
    }

    /**
     * @throws IllegalStateException if {@code name}'s baseline was never captured
     */
    public Optional<Level> get(String name) {
        Optional<Level> value = captured.get(name);
        if (value == null) {
            throw new IllegalStateException("baseline not captured for " + name);
        }
        return value;
    }
}
