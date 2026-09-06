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

import java.nio.file.Path;

/**
 * Best-effort static facts about a handler's own configuration — never a
 * level, see {@code handler-floor-control.md}'s level-only SPI for that.
 * Doc/specs/doctor.md "Adapter SPI": every field is {@code null} where a
 * framework or handler type doesn't expose the concept (a console handler
 * has no file size cap; plain JUL's base {@code Handler} has no rotation
 * concept at all), so an adapter that can answer nothing here still returns
 * {@link #EMPTY} rather than throwing.
 *
 * @param maxFileSizeBytes the configured size-based rotation threshold, or
 *                         {@code null} if unknown/not applicable
 * @param backupCount      the configured backup/retention count, or {@code
 *                         null} if unknown/not applicable — {@code 0} or
 *                         negative conventionally means "unlimited" where a
 *                         framework allows that value
 * @param autoFlush        whether this handler flushes after every record,
 *                         or {@code null} if the framework doesn't expose
 *                         the concept
 * @param targetPath       the file this handler writes to, or {@code null}
 *                         if it doesn't write to one at all — a handler
 *                         with no {@code targetPath} (a console/stdout
 *                         handler, most concretely) is not a persistent
 *                         sink, per doc/specs/doctor.md Decision #3
 */
public record HandlerDiagnostics(Long maxFileSizeBytes, Integer backupCount, Boolean autoFlush, Path targetPath) {

    /** Every field {@code null} — the default for a handler/framework this can't introspect at all. */
    public static final HandlerDiagnostics EMPTY = new HandlerDiagnostics(null, null, null, null);

    /** Whether this handler persists to disk — the signal doc/specs/doctor.md Decision #3 keys the duplicate-output check on. */
    public boolean isPersistent() {
        return targetPath != null;
    }
}
