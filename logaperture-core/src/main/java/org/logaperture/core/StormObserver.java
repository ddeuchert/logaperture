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
import java.util.function.Supplier;

/**
 * The seam an adapter's gate-stage {@code Filter} feeds — see doc/specs/
 * storm-detection.md "Adapter SPI". {@link StormDetector} is the only
 * production implementation; a fake in adapter tests lets those tests assert
 * exactly what was observed without pulling {@code core}'s real detection
 * logic into scope.
 *
 * <p>Implementations must never throw: doc/specs/storm-detection.md "Failure
 * handling" requires a detector bug to be swallowed, not to break the
 * application's logging or drop a line. {@link StormDetector} guarantees
 * this itself; a fake used in a test should too.
 */
@FunctionalInterface
public interface StormObserver {

    /** Feeds one candidate event to the detector. Must never throw. */
    void observe(StormObservation observation);

    /**
     * The same event as its fields, for the per-event path: nothing has to be
     * allocated to describe it (issue #147). {@code source} is handed back to
     * {@code details} only when this event engages a storm, during this call.
     * The default builds a {@link StormObservation}, so a test fake written
     * as a lambda sees every event; {@link StormDetector} overrides it.
     */
    default <S> void observe(String loggerName, Level level, String throwableClassName, String rawMessage,
            Instant timestamp, S source, Details<S> details) {
        Supplier<List<String>> topFrames = () -> details.topFrames(source);
        Supplier<String> firstOccurrence = () -> details.firstOccurrence(source);
        observe(new StormObservation(loggerName, level, throwableClassName, rawMessage, throwableClassName != null,
                throwableClassName != null ? topFrames : null, firstOccurrence, timestamp));
    }

    /**
     * What a detector asks of an event only when it engages a storm: the
     * expensive parts of {@link StormObservation}, rendered from the
     * adapter's own record. One instance per adapter, never per event.
     */
    interface Details<S> {

        /** The throwable's top frames, or {@code null} if the event has none. */
        List<String> topFrames(S source);

        /** doc/specs/storm-detection.md Decision #6: the original message and trace, uncapped. */
        String firstOccurrence(S source);
    }
}
