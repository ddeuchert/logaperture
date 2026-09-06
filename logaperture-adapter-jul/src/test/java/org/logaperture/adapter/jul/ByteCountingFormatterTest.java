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

import org.junit.jupiter.api.Test;
import org.logaperture.api.LoggerByteCount;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/top.md "Adapter SPI" — calls the real formatter exactly once and
 * measures what it returns, splitting out the stack-trace share separately.
 */
class ByteCountingFormatterTest {

    @Test
    void format_recordsBytesAgainstTheRecordsLoggerName_andReturnsTheRealFormattedText() {
        TopCounters counters = new TopCounters(10);
        ByteCountingFormatter formatter = new ByteCountingFormatter(new SimpleFormatter(), counters);
        LogRecord record = new LogRecord(Level.INFO, "hello world");
        record.setLoggerName("com.acme.Worker");

        String formatted = formatter.format(record);

        assertEquals(new SimpleFormatter().format(record), formatted, "returns exactly what the real formatter produced");
        List<LoggerByteCount> rows = counters.snapshot();
        assertEquals(1, rows.size());
        assertEquals("com.acme.Worker", rows.get(0).loggerName());
        assertEquals(formatted.getBytes(StandardCharsets.UTF_8).length, rows.get(0).totalBytes());
        assertEquals(0, rows.get(0).stackTraceBytes(), "no throwable -- no stack-trace share");
    }

    @Test
    void format_withAThrowable_splitsOutTheStackTraceShare() {
        TopCounters counters = new TopCounters(10);
        ByteCountingFormatter formatter = new ByteCountingFormatter(new SimpleFormatter(), counters);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(new RuntimeException("simulated failure"));

        formatter.format(record);

        LoggerByteCount row = counters.snapshot().get(0);
        assertTrue(row.stackTraceBytes() > 0, "a formatted throwable contributes a non-zero stack-trace share");
        assertTrue(row.stackTraceBytes() <= row.totalBytes(), "never exceeds the record's total measured bytes");
    }

    @Test
    void format_recordWithNoLoggerName_isNotAttributedToAnything() {
        TopCounters counters = new TopCounters(10);
        ByteCountingFormatter formatter = new ByteCountingFormatter(new SimpleFormatter(), counters);
        LogRecord record = new LogRecord(Level.INFO, "orphaned record");
        record.setLoggerName(null);

        formatter.format(record);

        assertTrue(counters.snapshot().isEmpty());
    }

    @Test
    void getHeadAndGetTail_delegateToTheWrappedFormatter() {
        SimpleFormatter delegate = new SimpleFormatter();
        ByteCountingFormatter formatter = new ByteCountingFormatter(delegate, new TopCounters(10));

        assertEquals(delegate.getHead(null), formatter.getHead(null));
        assertEquals(delegate.getTail(null), formatter.getTail(null));
    }

    @Test
    void delegate_exposesTheWrappedFormatter_forReWrapDetection() {
        SimpleFormatter delegate = new SimpleFormatter();
        ByteCountingFormatter formatter = new ByteCountingFormatter(delegate, new TopCounters(10));

        assertEquals(delegate, formatter.delegate());
    }
}
