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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Wraps a handler's real {@link Formatter} to measure what it produces —
 * doc/specs/top.md "Adapter SPI". Calls the real formatter exactly once per
 * record (no extra formatting work — "nearly free" per the top-level spec's
 * §16.1) and measures the UTF-8 byte length of what it returns, attributed to
 * {@link LogRecord#getLoggerName()}.
 *
 * <p>A record carrying a {@link Throwable} has its stack-trace share found in
 * what the real formatter returned (doc/specs/top.md "The stack-trace share",
 * T1–T4, issue #23): the bytes from the trace's first line to the end. Only
 * when that line can't be found is the trace rendered separately, via a
 * throwaway {@link PrintWriter}/{@link StringWriter}, and clamped to the
 * record's total, since the real formatter may render the throwable
 * differently or not at all.
 */
final class ByteCountingFormatter extends Formatter {

    private final Formatter delegate;
    private final TopCounters counters;

    ByteCountingFormatter(Formatter delegate, TopCounters counters) {
        this.delegate = delegate;
        this.counters = counters;
    }

    @Override
    public String format(LogRecord record) {
        String formatted = delegate.format(record);
        long totalBytes = formatted.getBytes(StandardCharsets.UTF_8).length; // T4: faster than counting chars
        Throwable thrown = record.getThrown();
        long stackTraceBytes = thrown == null ? 0L : stackTraceBytes(formatted, totalBytes, thrown);
        String loggerName = record.getLoggerName();
        if (loggerName != null) {
            counters.record(loggerName, totalBytes, stackTraceBytes);
        }
        return formatted;
    }

    @Override
    public String getHead(Handler handler) {
        return delegate.getHead(handler);
    }

    @Override
    public String getTail(Handler handler) {
        return delegate.getTail(handler);
    }

    /** The formatter this instance wraps — lets {@link JulLoggingAdapter} detect an already-wrapped handler. */
    Formatter delegate() {
        return delegate;
    }

    /** T1–T3: the formatted record's bytes from the trace's first line on, else T2's separate render. */
    static long stackTraceBytes(String formatted, long totalBytes, Throwable thrown) {
        try {
            int start = traceStart(formatted, thrown);
            if (start >= 0) {
                return Math.max(0L, Math.min(totalBytes, totalBytes - utf8Length(formatted, 0, start)));
            }
            return Math.min(totalBytes, traceBytes(thrown));
        } catch (RuntimeException e) {
            return 0L; // a throwable whose toString or printStackTrace fails must not cost the record itself
        }
    }

    /**
     * T1: where the standard rendering of {@code thrown} starts in {@code formatted} -- its {@code
     * toString()} at the start of a line, followed by a line break and a tab-indented line -- or
     * {@code -1}. A throwable with no frames has no tab-indented line, so for it the header on its
     * own line (or ending the output) is enough. A {@code null} or empty header is never searched
     * for: T2's render measures it.
     */
    static int traceStart(String formatted, Throwable thrown) {
        String header = thrown.toString();
        if (header == null || header.isEmpty()) {
            return -1;
        }
        int bare = -1;
        for (int at = formatted.indexOf(header); at >= 0; at = formatted.indexOf(header, at + 1)) {
            if (at > 0 && formatted.charAt(at - 1) != '\n') {
                continue; // not on its own line: a copy inside the message
            }
            int end = at + header.length();
            int next = afterLineBreak(formatted, end);
            if (next >= 0 && next < formatted.length() && formatted.charAt(next) == '\t') {
                return at;
            }
            if (bare < 0 && (next >= 0 || end == formatted.length())) {
                bare = at;
            }
        }
        return bare >= 0 && thrown.getStackTrace().length == 0 ? bare : -1;
    }

    /** The index just past a {@code \n} or {@code \r\n} at {@code at}, or {@code -1} if there is none. */
    private static int afterLineBreak(String text, int at) {
        if (at < text.length() && text.charAt(at) == '\n') {
            return at + 1;
        }
        if (at + 1 < text.length() && text.charAt(at) == '\r' && text.charAt(at + 1) == '\n') {
            return at + 2;
        }
        return -1;
    }

    static long traceBytes(Throwable thrown) {
        StringWriter sink = new StringWriter();
        try (PrintWriter writer = new PrintWriter(sink)) {
            thrown.printStackTrace(writer);
        }
        return sink.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * T4: what {@code text.substring(from, to).getBytes(UTF_8).length} would be, without the copy;
     * for the short text before a trace only (the encoder is faster over a whole record). A lone
     * surrogate counts one byte, as the encoder replaces it with {@code '?'}.
     */
    static long utf8Length(String text, int from, int to) {
        long bytes = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                bytes++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
