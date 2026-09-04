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

import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures each handler's baseline (pre-LogAperture) level once, lazily —
 * the {@link BaselineRegistry} counterpart for handlers (doc/specs/
 * handler-floor-control.md "Baseline capture"). Without this, {@code
 * resetHandler} is undefined, exactly the reasoning {@link BaselineRegistry}
 * documents for loggers.
 */
public final class HandlerBaselineRegistry {

    private final Map<HandlerRef, Optional<Level>> captured = new ConcurrentHashMap<>();

    /**
     * Captures {@code ref}'s baseline from {@code adapter} the first time
     * it's asked about; subsequent calls return the already-captured value
     * without touching the adapter again.
     */
    public Optional<Level> captureIfAbsent(HandlerRef ref, LoggingAdapter adapter) {
        return captured.computeIfAbsent(ref, adapter::handlerLevel);
    }

    public boolean isCaptured(HandlerRef ref) {
        return captured.containsKey(ref);
    }

    /**
     * @throws IllegalStateException if {@code ref}'s baseline was never captured
     */
    public Optional<Level> get(HandlerRef ref) {
        Optional<Level> value = captured.get(ref);
        if (value == null) {
            throw new IllegalStateException("baseline not captured for " + ref);
        }
        return value;
    }
}
