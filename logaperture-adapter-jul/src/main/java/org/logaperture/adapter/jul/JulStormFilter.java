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

import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.StormObserver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * The gate-stage observer wearing a {@link Filter}'s clothes — doc/specs/
 * storm-detection.md "Adapter SPI". Installed on a real {@link
 * java.util.logging.Handler} (the one-Filter-per-Handler attach point every
 * enabled record reaches, regardless of which logger it originated on),
 * capturing whatever {@link Filter} was already there and delegating to it
 * for the actual allow/deny verdict — this filter <b>never denies an
 * event</b>, so the observer is verdict-transparent. While storm detection
 * is disabled it stays installed and only passes the verdict through
 * (doc/specs/storm-detection-toggle.md T5).
 *
 * <p>All fingerprinting, normalization, the tally counter, and the state
 * machine live in {@code core}'s {@link org.logaperture.core.StormDetector};
 * this class does the framework-specific extraction only, and nothing else.
 * A failure feeding the observer is swallowed here too, defense in depth on
 * top of {@code StormDetector}'s own try/catch (doc/specs/storm-detection.md
 * "Failure handling").
 */
final class JulStormFilter implements Filter {

    /** Sampled once per storm engagement, never per event -- doc/specs/storm-detection.md "Detection algorithm". */
    private static final int MAX_TOP_FRAMES = 5;

    private static final String EXT_LOG_RECORD_CLASS_NAME = "org.jboss.logmanager.ExtLogRecord";

    /** Substitutes a plain JUL record's {@code {0}}-style parameters; stateless, as in {@link RuleCandidateEvents}. */
    private static final Formatter MESSAGE_FORMATTER = new SimpleFormatter();

    private final Filter delegate;
    private final StormObserver observer;

    JulStormFilter(Filter delegate, StormObserver observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    /** The filter this instance captured -- restored by {@link JulLoggingAdapter} on teardown. */
    Filter delegate() {
        return delegate;
    }

    /** Kept across a {@link FilterLayering} rebuild triggered by the rule filter's install. */
    StormObserver observer() {
        return observer;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        // doc/specs/storm-detection-toggle.md T5: installed in both positions, inert when disabled --
        // one volatile read, then straight to the verdict.
        if (!observer.isActive()) {
            return delegate == null || delegate.isLoggable(record);
        }
        try {
            // Issue #147: the event's fields go to the detector directly; the record itself is the
            // source RECORD_DETAILS renders from if this event engages a storm. Nothing per event.
            Throwable thrown = record.getThrown();
            observer.observe(
                    record.getLoggerName() != null ? record.getLoggerName() : "",
                    LevelMapper.toApi(record.getLevel()),
                    thrown == null ? null : thrown.getClass().getName(),
                    record.getMessage() != null ? record.getMessage() : "",
                    record.getInstant(),
                    record,
                    RECORD_DETAILS);
        } catch (RuntimeException e) {
            Diagnostics.warnThrottled("storm-observation", "storm observation failed, record unaffected: " + e, null);
        }
        return delegate == null || delegate.isLoggable(record);
    }

    /** Renders a storm's sampled details from the record that engaged it, during that same observe call. */
    private static final StormObserver.Details<LogRecord> RECORD_DETAILS = new StormObserver.Details<>() {
        @Override
        public List<String> topFrames(LogRecord record) {
            return record.getThrown() == null ? null : sampleTopFrames(record.getThrown());
        }

        @Override
        public String firstOccurrence(LogRecord record) {
            return renderFirstOccurrence(record, record.getThrown());
        }
    };

    private static List<String> sampleTopFrames(Throwable thrown) {
        StackTraceElement[] trace = thrown.getStackTrace();
        List<String> frames = new ArrayList<>(Math.min(MAX_TOP_FRAMES, trace.length));
        for (int i = 0; i < trace.length && i < MAX_TOP_FRAMES; i++) {
            frames.add(trace[i].toString());
        }
        return frames;
    }

    /**
     * doc/specs/storm-detection.md Decision #6: the storm's sample, message as logged + trace,
     * uncapped here -- {@code StormDetector} applies the 8KB cap.
     */
    private static String renderFirstOccurrence(LogRecord record, Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        sb.append(record.getInstant()).append(' ').append(record.getLevel()).append(" [")
                .append(record.getLoggerName()).append("] ")
                .append(formattedMessage(record));
        if (thrown != null) {
            StringWriter sink = new StringWriter();
            try (PrintWriter writer = new PrintWriter(sink)) {
                thrown.printStackTrace(writer);
            }
            sb.append('\n').append(sink);
        }
        return sb.toString();
    }

    /**
     * The message with its parameters filled in (issue #169). JBoss Logging's {@code debugf}/{@code
     * infof} templates are printf style, which JUL's {@link Formatter#formatMessage} leaves alone, so
     * a JBoss LogManager record formats itself -- {@code ExtLogRecord.getFormattedMessage()}, reached
     * by reflection since this adapter has no compile-time JBoss LogManager dependency (as in {@link
     * ExtLogRecordCopier}). Runs once per storm, never per event.
     */
    static String formattedMessage(LogRecord record) {
        if (isExtLogRecord(record.getClass())) {
            try {
                Object formatted = record.getClass().getMethod("getFormattedMessage").invoke(record);
                if (formatted instanceof String text) {
                    return text;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall back to JUL's own substitution below
            }
        }
        String formatted = MESSAGE_FORMATTER.formatMessage(record);
        return formatted != null ? formatted : "";
    }

    private static boolean isExtLogRecord(Class<?> recordClass) {
        for (Class<?> c = recordClass; c != null; c = c.getSuperclass()) {
            if (EXT_LOG_RECORD_CLASS_NAME.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
