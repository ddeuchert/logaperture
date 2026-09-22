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

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One candidate log event, reduced to exactly what {@link StormDetector}
 * needs — see doc/specs/storm-detection.md "Adapter SPI". The adapter's
 * {@code Filter} does the framework-specific extraction and nothing else;
 * all fingerprinting, normalization, the tally counter, and the state
 * machine live in {@code core}.
 *
 * @param loggerName             the originating logger's name
 * @param level                  the record's level
 * @param throwableClassName     the throwable's class name, or {@code null}
 *                               when the event carried none
 * @param rawMessage             the record's raw (unformatted) message, never
 *                               {@code null} (an empty string when the
 *                               framework has none)
 * @param hasThrown              whether the event carried a {@code Throwable}
 * @param topFramesSampler       supplies the sampled top 3-5 stack frames for
 *                               the §7.1 frame-escalation sub-key; invoked at
 *                               most once per fingerprint, only when {@code
 *                               StormDetector} has already decided the cheap
 *                               key looks like a storm, and always outside
 *                               its per-entry lock. {@code null} when {@code
 *                               hasThrown} is {@code false}.
 * @param firstOccurrenceSupplier supplies the rendered first-occurrence text
 *                               (message + trace); invoked at most once per
 *                               fingerprint, only when a new storm is first
 *                               recorded, and always outside {@code
 *                               StormDetector}'s per-entry lock
 * @param timestamp              when the event occurred
 */
public record StormObservation(
        String loggerName,
        Level level,
        String throwableClassName,
        String rawMessage,
        boolean hasThrown,
        Supplier<List<String>> topFramesSampler,
        Supplier<String> firstOccurrenceSupplier,
        Instant timestamp) {

    public StormObservation {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(rawMessage, "rawMessage");
        Objects.requireNonNull(firstOccurrenceSupplier, "firstOccurrenceSupplier");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
