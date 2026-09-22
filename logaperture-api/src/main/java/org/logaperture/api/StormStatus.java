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

/**
 * A tracked {@link Storm}'s two-state machine — see doc/specs/
 * storm-detection.md "Detection algorithm". A fingerprint that reaches the
 * storm threshold again after {@code ENDED} starts a brand-new {@link Storm},
 * not a revival of the ended one.
 */
public enum StormStatus {
    /** Still receiving matching events inside the quiet window. */
    ONGOING,
    /** No matching event observed for longer than the quiet period. */
    ENDED
}
