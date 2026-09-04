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

import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;

import java.util.List;
import java.util.Optional;

/**
 * The one extension point {@code core} defines into a real logging
 * framework — see doc/logaperture-spec.md §4.6. Everything in {@code
 * logaperture-core} is written and tested against this interface alone;
 * {@code logaperture-adapter-logback} and {@code logaperture-adapter-jul}
 * are the modules allowed to implement it with real framework calls.
 *
 * <p>Deliberately four logger methods, no {@code includeChildren} parameter
 * here: fan-out to descendants is {@code core}'s job (pure string logic over
 * already-known names via {@code LoggerHierarchy}), not the adapter's —
 * keeps the adapter a dumb per-logger getter/setter. The handler methods
 * below (doc/specs/handler-floor-control.md "Adapter SPI") follow the same
 * discipline: no tier/reason/lifetime knowledge here, that's {@code core}'s.
 */
public interface LoggingAdapter {

    /** Every logger name the underlying framework currently knows about. */
    List<String> knownLoggerNames();

    /**
     * The framework's own configured level for this logger, as a
     * side-effecting "observe" (matching frameworks like Logback, where
     * asking about a logger by name creates it if it doesn't exist yet —
     * see doc/specs/level-control.md's "Known" state).
     *
     * @return the explicit level, or empty if the logger inherits (has no
     *         explicit level of its own)
     */
    Optional<Level> configuredLevel(String loggerName);

    /** The level actually in effect right now, after hierarchy resolution. */
    Level effectiveLevel(String loggerName);

    /**
     * Applies a level to this logger. {@code null} clears back to
     * inherited. Must be safely re-invokable — see the re-appliability note
     * in doc/specs/level-control.md.
     */
    void applyLevel(String loggerName, Level level);

    /**
     * Registers {@code listener} to run after this adapter's underlying
     * framework completes a reconfiguration that may have discarded
     * previously-applied levels (doc/specs/persistence.md's "Reconfiguration
     * re-application"; doc/logaperture-spec.md §4.3's "Reconfiguration
     * hook" row). Default no-op — the {@code none} container's own baseline
     * has no such event of its own (confirmed by the M0 spike), and a
     * future adapter for a framework with no reconfiguration hook of its
     * own (e.g. JUL, per §4.3) can rely on this default rather than
     * throwing.
     *
     * <p>Callers may register at most one listener; a second call replaces
     * the first rather than fanning out to both, since this slice has
     * exactly one caller (the composition root, once, at install time).
     */
    default void onReset(Runnable listener) {
        // no-op by default
    }

    /**
     * Unregisters the listener most recently passed to {@link #onReset}, if
     * any. Default no-op. Called by the composition root when an {@code
     * Installation} is closed, so a torn-down installation's listener
     * doesn't keep firing against a shared static context that a later
     * {@code install()} call reuses (doc/specs/persistence.md's review:
     * repeated install()/close() cycles must not accumulate listeners).
     */
    default void clearResetListener() {
        // no-op by default
    }

    /**
     * The handlers on {@code loggerName}'s path to the root whose own level
     * is stricter than {@code target} — doc/specs/handler-floor-control.md
     * "Adapter SPI". Default empty, for a framework whose handlers have no
     * level of their own (Logback, {@code none}). A {@code null} {@code
     * target} ("back to inherited") is not a raise, so it yields an empty
     * list.
     */
    default List<HandlerFloor> handlerFloorsBelow(String loggerName, Level target) {
        return List.of();
    }

    /**
     * The identified handler's current level, or empty if this adapter's
     * handlers have no level of their own, or {@code ref} no longer resolves
     * to a live handler. Default empty.
     */
    default Optional<Level> handlerLevel(HandlerRef ref) {
        return Optional.empty();
    }

    /**
     * Sets the identified handler's level, returning its prior level —
     * empty if it had none, or if this adapter's handlers have no level of
     * their own (in which case nothing is changed). Default no-op.
     *
     * @throws UnknownHandlerException if {@code ref} does not resolve to a
     *                                 live handler in any managed context,
     *                                 for an adapter whose handlers do have
     *                                 a level
     */
    default Optional<Level> setHandlerLevel(HandlerRef ref, Level level) {
        return Optional.empty();
    }

    /** Every handler currently resolvable. Default empty. */
    default List<HandlerRef> knownHandlers() {
        return List.of();
    }
}
