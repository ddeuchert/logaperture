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
 * <p>A record carrying a {@link Throwable} additionally has its trace
 * measured on its own, via a throwaway {@link PrintWriter}/{@link
 * StringWriter} — a second, cheap format of just the trace, not a second
 * call into the handler's real formatter. Clamped to never exceed the
 * record's total measured bytes: the real formatter may render the
 * throwable differently (or not at all), so the two measurements are only
 * ever combined defensively, never assumed consistent.
 *
 * <p>Known limitation (issue #23): when {@code delegate} already renders the
 * same trace internally (e.g. {@link java.util.logging.SimpleFormatter}),
 * this still formats it a second time to measure it.
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
        long totalBytes = utf8Length(formatted);
        long stackTraceBytes = record.getThrown() == null ? 0L : Math.min(totalBytes, traceBytes(record.getThrown()));
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

    private static long traceBytes(Throwable thrown) {
        StringWriter sink = new StringWriter();
        try (PrintWriter writer = new PrintWriter(sink)) {
            thrown.printStackTrace(writer);
        }
        return utf8Length(sink.toString());
    }

    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
