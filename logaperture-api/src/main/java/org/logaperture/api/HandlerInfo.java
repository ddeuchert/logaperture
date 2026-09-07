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
package org.logaperture.api;

import java.time.Instant;

/**
 * One row of the handler catalog {@code logctl handlers} lists — the {@link
 * LoggerInfo} counterpart for handlers (doc/specs/handler-floor-control.md
 * "The handler catalog", issue #15). One per name the adapter advertises as
 * addressable ({@code knownHandlers()}): on WildFly that is {@code
 * ALL_HANDLERS} plus every handler whose configured name has resolved
 * (issue #14); on plain JUL every real handler plus {@code ALL_HANDLERS}.
 *
 * @param ref               the handler's addressable name ({@code CONSOLE},
 *                          {@code FILE}, {@code ALL_HANDLERS}, or an
 *                          identity token off WildFly)
 * @param level             the handler's own level right now (reflecting any
 *                          active override); {@code null} for {@code
 *                          ALL_HANDLERS}, which is not a live handler
 * @param persistent        whether this handler writes to a file (doc/specs/
 *                          doctor.md Decision #3's persistent-sink signal);
 *                          {@code false} for {@code ALL_HANDLERS} / a
 *                          console handler
 * @param targetPath        the file this handler writes to, or {@code null}
 * @param autoFlush         whether it flushes after every record, or {@code
 *                          null} if the framework doesn't expose the concept
 * @param overrideActive    whether a {@link HandlerLevelOverride} is applied
 * @param overrideLevel     the override's level; {@code null} unless {@code
 *                          overrideActive}
 * @param overrideTier      the override's durability tier; {@code null}
 *                          unless {@code overrideActive}
 * @param overrideExpiresAt the override's revert deadline; {@code null}
 *                          unless {@code overrideActive} and {@code
 *                          overrideTier} is {@link PersistenceTier#FOR}
 * @param context           the owning logging context's stable key, or
 *                          {@code null} on a single-context service's own
 *                          rows ({@code AggregateLevelControl} stamps it)
 */
public record HandlerInfo(
        String ref,
        Level level,
        boolean persistent,
        String targetPath,
        Boolean autoFlush,
        boolean overrideActive,
        Level overrideLevel,
        PersistenceTier overrideTier,
        Instant overrideExpiresAt,
        String context) {

    public HandlerInfo {
        if (ref == null || ref.isEmpty()) {
            throw new IllegalArgumentException("ref must not be null or empty");
        }
    }

    /** A single-context service builds rows with no context key; the aggregate fills it in. */
    public HandlerInfo(
            String ref, Level level, boolean persistent, String targetPath, Boolean autoFlush,
            boolean overrideActive, Level overrideLevel, PersistenceTier overrideTier, Instant overrideExpiresAt) {
        this(ref, level, persistent, targetPath, autoFlush, overrideActive, overrideLevel, overrideTier,
                overrideExpiresAt, null);
    }

    /** This same row, tagged with its owning context's stable key. */
    public HandlerInfo withContext(String context) {
        return new HandlerInfo(ref, level, persistent, targetPath, autoFlush, overrideActive, overrideLevel,
                overrideTier, overrideExpiresAt, context);
    }
}
