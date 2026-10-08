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
    void stackTraceShare_isTheFormattedTextFromTheTraceOn() {
        RuntimeException thrown = new RuntimeException("simulated failure");
        String formatted = "Oct 07 SEVERE com.acme.Worker: boom\n" + render(thrown);

        assertEquals(formatted.indexOf("java.lang.RuntimeException"), ByteCountingFormatter.traceStart(formatted, thrown));
        assertEquals(utf8(render(thrown)), ByteCountingFormatter.stackTraceBytes(formatted, utf8(formatted), thrown));
    }

    @Test
    void stackTraceShare_skipsTheHeaderWhenTheMessageRepeatsIt() {
        RuntimeException thrown = new RuntimeException("simulated failure");
        String formatted = "SEVERE: failed: " + thrown + "\n" + render(thrown) + "\n";

        assertEquals(utf8(render(thrown) + "\n"), ByteCountingFormatter.stackTraceBytes(formatted, utf8(formatted), thrown),
                "T1: the trace, not the copy in the message; T3: the trailing line break counts");
    }

    @Test
    void stackTraceShare_crlfLineBreaks() {
        RuntimeException thrown = new RuntimeException("x");
        String formatted = "SEVERE: x\r\n" + thrown + "\r\n\tat a.B.c(B.java:1)\r\n";

        assertEquals("SEVERE: x\r\n".length(), ByteCountingFormatter.traceStart(formatted, thrown));
    }

    @Test
    void stackTraceShare_throwableWithoutFrames_headerAloneIsEnough() {
        RuntimeException thrown = new RuntimeException("stackless", null, false, false) {
        };
        String formatted = "SEVERE: msg\n" + thrown + "\n";

        assertEquals("SEVERE: msg\n".length(), ByteCountingFormatter.traceStart(formatted, thrown));
    }

    @Test
    void stackTraceShare_notInTheOutput_fallsBackToASeparateRender() {
        RuntimeException thrown = new RuntimeException("simulated failure");
        String json = "{\"message\":\"boom\",\"exception\":\"" + render(thrown).replace("\n", "\\n").replace("\t", "\\t")
                + "\"}";

        assertEquals(-1, ByteCountingFormatter.traceStart(json, thrown));
        assertEquals(Math.min(utf8(json), utf8(render(thrown))),
                ByteCountingFormatter.stackTraceBytes(json, utf8(json), thrown), "T2: today's measurement");
        assertEquals(2, ByteCountingFormatter.stackTraceBytes("{}", 2, thrown), "clamped to the record's total");
    }

    @Test
    void stackTraceShare_frameless_skipsACopyOfTheHeaderInsideTheMessage() {
        RuntimeException thrown = new RuntimeException("stackless", null, false, false) {
        };
        String formatted = "SEVERE: failed: " + thrown + "\ncontext\n" + thrown + "\n";

        assertEquals(formatted.lastIndexOf(String.valueOf(thrown)), ByteCountingFormatter.traceStart(formatted, thrown));
    }

    @Test
    void stackTraceShare_jbossStyle_traceRightAfterTheMessageOnTheSameLine() {
        RuntimeException thrown = new RuntimeException("outer");
        String formatted = "ERROR [c] failed: " + thrown + ": " + render(thrown);

        assertEquals(formatted.lastIndexOf(thrown + "\n\tat"), ByteCountingFormatter.traceStart(formatted, thrown),
                "%e follows the message with ': '; the copy in the message isn't followed by a line break");
    }

    @Test
    void stackTraceShare_emptyOrNullToString_neverHangsOrThrows() {
        RuntimeException empty = new RuntimeException("x") {
            @Override
            public String toString() {
                return "";
            }
        };
        RuntimeException nullHeader = new RuntimeException() {
            @Override
            public String toString() {
                return null;
            }
        };
        assertEquals(-1, ByteCountingFormatter.traceStart("SEVERE: msg\n", empty));
        assertEquals(-1, ByteCountingFormatter.traceStart("SEVERE: msg\n", nullHeader));
        assertTrue(ByteCountingFormatter.stackTraceBytes("SEVERE: msg\n", 12, nullHeader) <= 12);

        TopCounters counters = new TopCounters(10);
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("com.acme.Worker");
        record.setThrown(nullHeader);
        new ByteCountingFormatter(new SimpleFormatter(), counters).format(record);
        assertEquals(1, counters.snapshot().size(), "the record is still formatted and counted");
    }

    @Test
    void utf8Length_matchesTheEncoder() {
        for (String text : List.of("", "plain ascii", "caf\u00e9", "\u20ac 5", "\ud83d\ude00 emoji",
                "lone \ud83d high", "lone \ude00 low", "end \ud83d")) {
            assertEquals(utf8(text), ByteCountingFormatter.utf8Length(text, 0, text.length()), text);
        }
    }

    private static String render(Throwable thrown) {
        java.io.StringWriter sink = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(sink, true));
        return sink.toString();
    }

    private static long utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
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
