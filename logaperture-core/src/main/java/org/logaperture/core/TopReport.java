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

import org.logaperture.api.LoggerByteCount;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@link TopOperations#topLoggers}'s return shape — the rows, plus when
 * measurement began. A wrapper (rather than returning the bare list, as
 * {@link DoctorOperations#diagnose()} does) because rate is a rendering-time
 * derivation (doc/specs/top.md "Data model") that needs the elapsed duration
 * alongside the raw counts — every control surface computes it from these
 * same two fields, rather than each carrying its own formula.
 *
 * @param loggers               sorted worst-first by {@code totalBytes},
 *                              already truncated to the caller's requested
 *                              limit
 * @param measurementStartedAt when this window's counting began, or {@code
 *                              null} if measurement hasn't started yet (no
 *                              persistent handler exists to count against)
 * @param trackedCount          the true count of loggers being tracked --
 *                              i.e. {@code loggers.size()} <em>before</em>
 *                              {@code limit} truncated it. "Tracked" means
 *                              "has emitted at least one byte through a
 *                              counted handler" (doc/specs/top.md "The
 *                              operation"), a fact independent of how many
 *                              rows a caller asked to see.
 */
public record TopReport(List<LoggerByteCount> loggers, Instant measurementStartedAt, int trackedCount) {

    public TopReport {
        Objects.requireNonNull(loggers, "loggers");
        if (trackedCount < loggers.size()) {
            throw new IllegalArgumentException(
                    "trackedCount (" + trackedCount + ") cannot be less than loggers.size() (" + loggers.size() + ")");
        }
    }
}
