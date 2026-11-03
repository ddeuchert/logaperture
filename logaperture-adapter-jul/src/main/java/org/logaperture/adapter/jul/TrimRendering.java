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

import org.logaperture.core.TrimDecision;
import org.logaperture.core.TrimmedThrowables;

/**
 * Builds the trimmed {@link Throwable} chain a matching {@link TrimDecision} produces -- doc/specs/trim-rule.md
 * "Text formatters only". The builder itself moved to {@link TrimmedThrowables} in {@code core}, where the
 * Log4j 2 adapter uses the same one; this keeps the names the JUL adapter and its tests already use.
 */
final class TrimRendering {

    /** See {@link TrimmedThrowables#MARKER_OPEN}; {@link ByteCountingFormatter} skips it to find a trimmed trace (doc/specs/top.md T5). */
    static final String MARKER_OPEN = TrimmedThrowables.MARKER_OPEN;
    static final String MARKER_CLOSE = TrimmedThrowables.MARKER_CLOSE;

    private TrimRendering() {
    }

    /** {@code original} itself is never mutated -- the caller is expected to set the result on a private record copy (see {@link ExtLogRecordCopier}), never on the shared record. */
    static Throwable buildTrimmed(Throwable original, TrimDecision decision) {
        return TrimmedThrowables.build(original, decision);
    }
}
