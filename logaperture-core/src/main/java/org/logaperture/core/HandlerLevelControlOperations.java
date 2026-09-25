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

import org.logaperture.api.HandlerInfo;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.HandlerResetOutcome;
import org.logaperture.api.Level;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SquelchedLogger;

import java.util.List;
import java.util.Optional;

/**
 * {@code logctl set handler}'s public contract — the {@link
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

    /**
     * Read-only pre-check for {@link #setHandlerLevel}'s "raise" direction
     * (doc/specs/handler-floor-control.md "Squelch warning", issue #16): if
     * {@code ref} were raised to {@code newLevel} right now, which currently-active
     * logger overrides would newly stop reaching it — ones getting through at
     * {@code ref}'s current level that {@code newLevel} would silence. Mutates
     * nothing; a caller runs this before the real {@link #setHandlerLevel} call
     * (using the same pre-mutation handler level this reads) and folds the answer
     * into that call's warning, the same "advice, never fails, never re-run to
     * suppress" contract {@code setLogger}'s own blocking-handler warning already
     * has. Empty for a lower/no-op direction, for the underlying adapter having no
     * handler levels of its own, or for a handler this context can't currently
     * resolve.
     */
    List<SquelchedLogger> squelchedByRaise(HandlerRef ref, Level newLevel);

    /**
     * {@code logctl set handler <name> AUTO} — puts {@code ref} into a
     * self-tracking mode whose applied level follows the lowest currently
     * active logger override, reverting to {@code ref}'s own baseline once
     * none remain (doc/specs/handler-floor-control.md "AUTO handler level",
     * issue #20).
     *
     * @return the created override, or empty if the underlying adapter's
     *         handlers have no level of their own, or (single-ref only)
     *         neither an active floor nor a captured baseline exists to
     *         track — a documented no-op, not an error
     */
    Optional<HandlerLevelOverride> setHandlerAuto(HandlerRef ref, SetHandlerLevelOptions options);

    /**
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place — new
     *                      default is to skip it (doc/specs/
     *                      reset-command-surface.md)
     * @return exactly what was reverted, and what was left alone for being
     *         sticky — see {@link HandlerResetOutcome}
     * @throws IllegalArgumentException if {@code ref}'s active override is
     *                                   {@code STICKY} and {@code
     *                                   includeSticky} is {@code false}
     *                                   (Decision #1 — a single named target
     *                                   refuses outright rather than
     *                                   silently skipping)
     */
    HandlerResetOutcome resetHandler(HandlerRef ref, boolean includeSticky);

    /**
     * {@link #resetHandler(HandlerRef, boolean)}, optionally to the native default -- doc/specs/
     * reset-to-native.md; on a group ref ({@code ALL_HANDLERS}, {@code DEFAULT_HANDLERS}) it
     * applies to each member. Implementations without a vendor layer need only the two-argument
     * form.
     */
    default HandlerResetOutcome resetHandler(HandlerRef ref, boolean includeSticky, boolean toNative) {
        if (toNative) {
            throw new UnsupportedOperationException("reset --to-native is not supported here");
        }
        return resetHandler(ref, includeSticky);
    }

    /**
     * Reverts every currently-overridden handler — the bulk counterpart to
     * {@link #resetHandler} (doc/specs/reset-command-surface.md).
     *
     * @param includeSticky whether a {@code STICKY}-tier override is
     *                      reverted too, instead of left in place
     * @return exactly what was reverted, and what was left alone for being
     *         sticky
     */
    HandlerResetOutcome resetAllHandlers(boolean includeSticky);

    /** {@link #resetAllHandlers(boolean)}, optionally to the native default -- as {@link #resetHandler(HandlerRef, boolean, boolean)}. */
    default HandlerResetOutcome resetAllHandlers(boolean includeSticky, boolean toNative) {
        if (toNative) {
            throw new UnsupportedOperationException("reset --to-native is not supported here");
        }
        return resetAllHandlers(includeSticky);
    }

    /**
     * {@code logctl reset default-handler [--to-native]} -- clears any explicit {@code
     * DEFAULT_HANDLERS} membership, so the vendor defaults file's list (or the automatic pick)
     * decides again; with {@code toNative}, the vendor list is also ignored until restart
     * (doc/specs/reset-to-native.md).
     *
     * @return the members now in effect
     */
    default List<HandlerRef> resetDefaultHandlerMembers(boolean toNative) {
        if (toNative) {
            throw new UnsupportedOperationException("reset --to-native is not supported here");
        }
        return setDefaultHandlerMembers(List.of());
    }

    /**
     * Every handler override currently active — the {@link
     * LevelControlOperations#listLoggers} counterpart for handlers, feeding
     * {@code logctl status} (doc/specs/handler-floor-control.md "logctl
     * status shows handler overrides too").
     */
    List<HandlerLevelOverride> listHandlerOverrides();

    /**
     * The full addressable handler catalog — one row per {@code
     * knownHandlers()} entry (doc/specs/handler-floor-control.md "The handler
     * catalog", issue #15), feeding {@code logctl set handlers}. Read-only.
     * Empty for an adapter whose handlers have no level of their own
     * (Logback, {@code none}).
     */
    List<HandlerInfo> listHandlers();

    /**
     * {@code logctl set default-handler <name>...} — assigns {@code
     * DEFAULT_HANDLERS}'s explicit membership. {@code logctl reset
     * default-handler} calls this with an empty {@code names} to clear it,
     * reverting to the deterministic selection rule (doc/specs/
     * handler-floor-control.md "Default handler group", issue #28). Each
     * name must resolve against this context's own {@code
     * realHandlers()}.
     *
     * @return the new explicit membership, or empty when cleared
     * @throws org.logaperture.core.spi.UnknownHandlerException if any name doesn't resolve
     */
    List<HandlerRef> setDefaultHandlerMembers(List<HandlerRef> names);
}
