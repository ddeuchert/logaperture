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

import org.logaperture.core.StormObservation;
import org.logaperture.core.StormObserver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * The gate-stage observer wearing a {@link Filter}'s clothes — doc/specs/
 * storm-detection.md "Adapter SPI". Installed on a real {@link
 * java.util.logging.Handler} (the one-Filter-per-Handler attach point every
 * enabled record reaches, regardless of which logger it originated on),
 * capturing whatever {@link Filter} was already there and delegating to it
 * for the actual allow/deny verdict — this filter <b>never denies an
 * event</b>, so the observer is verdict-transparent.
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

    @Override
    public boolean isLoggable(LogRecord record) {
        try {
            observer.observe(toObservation(record));
        } catch (RuntimeException e) {
            System.err.println("[logaperture-adapter-jul] storm observation failed, record unaffected: " + e);
        }
        return delegate == null || delegate.isLoggable(record);
    }

    private static StormObservation toObservation(LogRecord record) {
        String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "";
        org.logaperture.api.Level level = LevelMapper.toApi(record.getLevel());
        Throwable thrown = record.getThrown();
        String throwableClassName = thrown == null ? null : thrown.getClass().getName();
        String rawMessage = record.getMessage() != null ? record.getMessage() : "";
        boolean hasThrown = thrown != null;
        Instant timestamp = record.getInstant();
        return new StormObservation(
                loggerName, level, throwableClassName, rawMessage, hasThrown,
                hasThrown ? () -> sampleTopFrames(thrown) : null,
                () -> renderFirstOccurrence(record, thrown),
                timestamp);
    }

    private static List<String> sampleTopFrames(Throwable thrown) {
        StackTraceElement[] trace = thrown.getStackTrace();
        List<String> frames = new ArrayList<>(Math.min(MAX_TOP_FRAMES, trace.length));
        for (int i = 0; i < trace.length && i < MAX_TOP_FRAMES; i++) {
            frames.add(trace[i].toString());
        }
        return frames;
    }

    /** doc/specs/storm-detection.md Decision #6: the original message + trace, uncapped here -- {@code StormDetector} applies the 8KB cap. */
    private static String renderFirstOccurrence(LogRecord record, Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        sb.append(record.getInstant()).append(' ').append(record.getLevel()).append(" [")
                .append(record.getLoggerName()).append("] ")
                .append(record.getMessage() != null ? record.getMessage() : "");
        if (thrown != null) {
            StringWriter sink = new StringWriter();
            try (PrintWriter writer = new PrintWriter(sink)) {
                thrown.printStackTrace(writer);
            }
            sb.append('\n').append(sink);
        }
        return sb.toString();
    }
}
