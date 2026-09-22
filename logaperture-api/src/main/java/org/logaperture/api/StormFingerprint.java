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

import java.util.List;

/**
 * The §7.1 two-tier storm identity — see doc/specs/storm-detection.md "Data
 * model". The cheap key ({@code loggerName}, {@code level}, {@code
 * throwableClass}, {@code normalizedMessage}) is computed on every candidate
 * event; {@code topFrames} is filled in only once that cheap key already
 * looks like a storm, from one sampled {@code getStackTrace()} — never on the
 * per-event hot path.
 *
 * @param loggerName        the originating logger
 * @param level             the record's level
 * @param throwableClass    the throwable's class name, or {@code null} when
 *                          the event carried none
 * @param normalizedMessage the message after the Decision #3 normalization
 *                          transform (digit/hex/UUID runs collapsed,
 *                          whitespace collapsed, trimmed, length-capped)
 * @param topFrames         the sampled top 3-5 stack frames that split this
 *                          fingerprint from a same-exception-different-site
 *                          sibling, or {@code null} if frame escalation never
 *                          ran for this fingerprint
 */
public record StormFingerprint(
        String loggerName,
        Level level,
        String throwableClass,
        String normalizedMessage,
        List<String> topFrames) {

    public StormFingerprint {
        if (loggerName == null) {
            throw new IllegalArgumentException("loggerName must not be null");
        }
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        if (normalizedMessage == null) {
            throw new IllegalArgumentException("normalizedMessage must not be null");
        }
        topFrames = topFrames == null ? null : List.copyOf(topFrames);
    }
}
