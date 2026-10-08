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
package org.logaperture.adapter.jul;

/**
 * Lets the benchmarks in {@code org.logaperture.bench} check, outside the
 * measured call, that {@code top} found a trace in a formatter's output
 * (doc/specs/top.md T1, T5) instead of falling back to a second render.
 */
public final class TraceLookup {

    private TraceLookup() {
    }

    /** Whether {@link ByteCountingFormatter} finds {@code thrown}'s trace in {@code formatted}. */
    public static boolean found(String formatted, Throwable thrown) {
        return ByteCountingFormatter.traceStart(formatted, thrown) >= 0;
    }
}
