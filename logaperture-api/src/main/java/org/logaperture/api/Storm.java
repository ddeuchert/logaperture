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
import java.util.Objects;

/**
 * One tracked log storm — see doc/specs/storm-detection.md "Data model".
 * Framework-independent (lives in {@code api}, alongside {@link
 * DoctorFinding} and {@link LoggerByteCount}). Rate is deliberately not a
 * stored field: it is a rendering-time derivation from {@code eventCount} and
 * the elapsed {@code firstEventAt}..{@code lastEventAt|now} span, the same
 * raw-counts-on-the-wire discipline {@code top} uses.
 *
 * @param fingerprint     this storm's identity
 * @param status          {@code ONGOING} or {@code ENDED}
 * @param firstEventAt    when the fingerprint first crossed the storm
 *                        threshold
 * @param lastEventAt     the most recent matching event observed
 * @param endedAt         set iff {@code status == ENDED}; equal to {@code
 *                        lastEventAt} at the moment the quiet period was
 *                        observed to have elapsed
 * @param eventCount      total matching events since {@code firstEventAt}
 *                        (the lifetime count, distinct from the internal
 *                        burst tally that declared the storm)
 * @param firstOccurrence the rendered first message + stack trace, size-capped,
 *                        or {@code null} if it could not be captured
 * @param context         the owning logging context's stable key, or {@code
 *                        null} on a row produced by a single-context service
 *                        directly; {@code AggregateLevelControl} stamps the
 *                        real key ({@link #withContext}) on every row it
 *                        returns
 */
public record Storm(
        StormFingerprint fingerprint,
        StormStatus status,
        Instant firstEventAt,
        Instant lastEventAt,
        Instant endedAt,
        long eventCount,
        String firstOccurrence,
        String context) {

    public Storm {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstEventAt, "firstEventAt");
        Objects.requireNonNull(lastEventAt, "lastEventAt");
        if (status == StormStatus.ENDED && endedAt == null) {
            throw new IllegalArgumentException("endedAt must be set when status == ENDED");
        }
        if (status == StormStatus.ONGOING && endedAt != null) {
            throw new IllegalArgumentException("endedAt must be null when status == ONGOING");
        }
        if (eventCount < 1) {
            throw new IllegalArgumentException("eventCount must be at least 1");
        }
    }

    /**
     * A single-context service builds storms without a context key; {@code
     * AggregateLevelControl} fills it in afterwards. Keeps every existing
     * {@code new Storm(...)} call site unchanged.
     */
    public Storm(StormFingerprint fingerprint, StormStatus status, Instant firstEventAt, Instant lastEventAt,
            Instant endedAt, long eventCount, String firstOccurrence) {
        this(fingerprint, status, firstEventAt, lastEventAt, endedAt, eventCount, firstOccurrence, null);
    }

    /** This same storm, tagged with its owning context's stable key. */
    public Storm withContext(String context) {
        return new Storm(fingerprint, status, firstEventAt, lastEventAt, endedAt, eventCount, firstOccurrence,
                context);
    }
}
