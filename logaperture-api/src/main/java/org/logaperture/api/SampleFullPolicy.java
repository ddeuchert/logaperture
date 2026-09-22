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

import java.time.Duration;
import java.util.Objects;

/**
 * {@link Drop}'s keep-one-in-N escape hatch — doc/specs/drop-rule.md "The
 * keep-one-in-N escape hatch" (top-level §7.4). Time-based, not count-based
 * (Decision #1): the very first matching event a newly-attached {@code Drop}
 * would otherwise deny is always let through in full; after that, one fully
 * kept event per {@link #every()} interval, with the clock resetting from
 * the kept event — nothing is ever more than {@code every} stale.
 *
 * @param enabled on by default; {@code false} is the explicit opt-out §7.4
 *                requires to be spelled out rather than defaulted to
 * @param every   the interval between kept samples; still carried (and
 *                round-tripped) even when {@code enabled} is {@code false},
 *                so re-enabling later doesn't lose a previously-chosen
 *                interval
 */
public record SampleFullPolicy(boolean enabled, Duration every) {

    /** doc/specs/drop-rule.md Decision #5 — shared with the periodic drop-summary line's own default. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    public SampleFullPolicy {
        Objects.requireNonNull(every, "every");
        if (every.isZero() || every.isNegative()) {
            throw new IllegalArgumentException("every must be positive");
        }
    }

    /** On, at the default 5-minute interval. */
    public static SampleFullPolicy defaults() {
        return new SampleFullPolicy(true, DEFAULT_INTERVAL);
    }

    /** {@code --sample-full <duration>}. */
    public static SampleFullPolicy every(Duration duration) {
        return new SampleFullPolicy(true, duration);
    }

    /** {@code --no-sample-full}: the explicit, required-to-be-spelled-out opt-out. */
    public static SampleFullPolicy disabled() {
        return new SampleFullPolicy(false, DEFAULT_INTERVAL);
    }
}
