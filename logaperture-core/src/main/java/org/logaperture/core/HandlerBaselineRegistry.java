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
 *
 * <p>Deliberately does <em>not</em> follow {@link BaselineRegistry}'s
 * {@code computeIfAbsent} shortcut: for a logger, {@code
 * adapter.configuredLevel(name)} returning empty is a stable fact ("no
 * explicit level of its own") worth caching permanently. For a handler, an
 * empty {@code adapter.handlerLevel(ref)} means "doesn't resolve right now"
 * — a real {@code java.util.logging.Handler} always has a level once it
 * resolves at all, so empty here is transient (the ref simply hasn't been
 * seen by this adapter yet, e.g. a multi-context capability pre-check
 * running against a context that lacks the handler). Caching that {@code
 * empty} would wrongly lock a ref out of ever getting a real baseline, and
 * {@code resetHandler} would later hand the adapter a bare {@code null} —
 * which, unlike a logger's "clear to inherited", a handler has no concept
 * of ({@code Handler#setLevel} throws on {@code null}). So only a genuinely
 * resolved level is ever cached; an empty result is returned but not
 * remembered, and the next call tries again.
 */
public final class HandlerBaselineRegistry {

    private final Map<HandlerRef, Optional<Level>> captured = new ConcurrentHashMap<>();

    /**
     * Captures {@code ref}'s baseline from {@code adapter} the first time it
     * resolves to a real level; subsequent calls return the already-captured
     * value without touching the adapter again. A {@code ref} that doesn't
     * resolve yet returns empty every time, uncached, until it does.
     */
    public Optional<Level> captureIfAbsent(HandlerRef ref, LoggingAdapter adapter) {
        Optional<Level> existing = captured.get(ref);
        if (existing != null) {
            return existing;
        }
        Optional<Level> resolved = adapter.handlerLevel(ref);
        if (resolved.isPresent()) {
            captured.putIfAbsent(ref, resolved);
        }
        return resolved;
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
