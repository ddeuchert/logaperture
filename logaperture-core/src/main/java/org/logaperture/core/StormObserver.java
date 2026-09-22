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
}
