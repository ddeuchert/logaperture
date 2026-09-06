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
 * One logger's measured byte volume — see doc/specs/top.md "Data model".
 * Framework-independent (lives in {@code api}, alongside {@link
 * DoctorFinding}), so both {@code core} (to run the measurement) and every
 * control surface (to render it) depend on it without depending on any one
 * adapter.
 *
 * <p>Rate ({@code bytesPerHour}) and the stack-trace percentage are
 * rendering-time derivations from {@code totalBytes}/{@code stackTraceBytes}
 * and the elapsed measurement duration — deliberately not stored fields, so
 * both the text renderer and {@code --json} compute them the same way
 * instead of each carrying (and risking drifting) their own formula.
 *
 * @param loggerName      the record's originating logger, e.g. {@code
 *                        "org.apache.http.impl.conn"} — never a package
 *                        prefix; this slice does no hierarchy rollup
 *                        (doc/specs/top.md Decision #2)
 * @param totalBytes      bytes measured since the owning service's
 *                        measurement window began, across every persistent
 *                        handler this logger's records reached
 * @param stackTraceBytes the portion of {@code totalBytes} attributable to a
 *                        {@code Throwable}'s formatted stack trace; never
 *                        greater than {@code totalBytes}
 * @param context         the owning logging context's stable key, or {@code
 *                        null} on a row produced by a single-context service
 *                        directly. Mirrors {@link DoctorFinding#context()};
 *                        {@code AggregateLevelControl} stamps the real key
 *                        ({@link #withContext}) on every row it returns.
 */
public record LoggerByteCount(String loggerName, long totalBytes, long stackTraceBytes, String context) {

    public LoggerByteCount {
        if (loggerName == null || loggerName.isEmpty()) {
            throw new IllegalArgumentException("loggerName must not be null or empty");
        }
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must not be negative");
        }
        if (stackTraceBytes < 0 || stackTraceBytes > totalBytes) {
            throw new IllegalArgumentException("stackTraceBytes must be between 0 and totalBytes");
        }
    }

    /**
     * A single-context service builds its rows without a context key;
     * {@code AggregateLevelControl} fills it in afterwards. Keeps every
     * existing {@code new LoggerByteCount(...)} call site unchanged.
     */
    public LoggerByteCount(String loggerName, long totalBytes, long stackTraceBytes) {
        this(loggerName, totalBytes, stackTraceBytes, null);
    }

    /** This same row, tagged with its owning context's stable key. */
    public LoggerByteCount withContext(String context) {
        return new LoggerByteCount(loggerName, totalBytes, stackTraceBytes, context);
    }
}
