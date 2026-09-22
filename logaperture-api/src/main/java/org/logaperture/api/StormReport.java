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
import java.util.List;
import java.util.Objects;

/**
 * {@code logctl storms}'s return shape — see doc/specs/storm-detection.md
 * "Data model" and "The operation". Framework-independent, alongside {@link
 * Storm} itself.
 *
 * @param storms                already sorted worst-first (ongoing before
 *                              ended, then by event count), already truncated
 *                              to the caller's requested {@code --limit}
 * @param trackedCount          storms that reached storm state at least once,
 *                              pre-{@code --limit} -- i.e. {@code
 *                              storms.size()} before truncation
 * @param ongoingCount          the subset of the pre-truncation set still
 *                              {@code ONGOING}
 * @param measurementStartedAt when detection began; {@code null} only if no
 *                              context has installed it yet
 * @param notRetainedCount      storms that reached the threshold but could not
 *                              be retained because the bounded history was
 *                              full of {@code ONGOING} entries at the moment
 *                              they engaged (doc/specs/storm-detection.md
 *                              "Bounded state") -- disclosed rather than
 *                              silently dropped; {@code 0} in the common case
 */
public record StormReport(List<Storm> storms, int trackedCount, int ongoingCount, Instant measurementStartedAt,
        int notRetainedCount) {

    public StormReport {
        Objects.requireNonNull(storms, "storms");
        storms = List.copyOf(storms);
        if (trackedCount < storms.size()) {
            throw new IllegalArgumentException(
                    "trackedCount (" + trackedCount + ") cannot be less than storms.size() (" + storms.size() + ")");
        }
        if (ongoingCount < 0 || ongoingCount > trackedCount) {
            throw new IllegalArgumentException("ongoingCount must be between 0 and trackedCount");
        }
        if (notRetainedCount < 0) {
            throw new IllegalArgumentException("notRetainedCount must not be negative");
        }
    }

    /** Convenience constructor for the common case of nothing dropped. */
    public StormReport(List<Storm> storms, int trackedCount, int ongoingCount, Instant measurementStartedAt) {
        this(storms, trackedCount, ongoingCount, measurementStartedAt, 0);
    }
}
