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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.ThrowableProxy;
import org.apache.logging.log4j.core.time.Instant;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

import java.util.Map;

/**
 * A read-only view of an event that never reports a caller location (issue #333). {@link TrimAppender} copies
 * an event with {@code new Log4jLogEvent.Builder(event)}, which on current Log4j asks the source event for
 * {@code getSource()}; a synchronous logger's event answers with a stack walk, about 16 µs per call, for a
 * location the appender's layout never prints. Handing the builder this view instead leaves the event
 * untouched and the walk undone. Only used where nothing behind the wrapper needs the location
 * ({@link TrimAppender#needsLocation}) and the running Log4j says it pays ({@link LocationWalkProbe}): on
 * Log4j 2.20 it would be slower, since the builder's generic path builds a {@code ThrowableProxy} the specialised
 * one does not.
 *
 * <p>It is not a {@code MutableLogEvent} or a {@code RingBufferLogEvent}, so the builder reads it through the
 * getters, the one path every Log4j version has.
 */
class SourcelessEvent implements LogEvent {

    private static final long serialVersionUID = 1L;

    private final LogEvent event;

    SourcelessEvent(LogEvent event) {
        this.event = event;
    }

    @Override
    public StackTraceElement getSource() {
        return null;
    }

    @Override
    public boolean isIncludeLocation() {
        return false;
    }

    @Override
    public void setIncludeLocation(boolean locationRequired) {
        // a view: the original keeps its own flag
    }

    @Override
    public LogEvent toImmutable() {
        // Never the original's: its immutable copy would carry a location again.
        return new org.apache.logging.log4j.core.impl.Log4jLogEvent.Builder(this).build();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Map<String, String> getContextMap() {
        return event.getContextMap();
    }

    @Override
    public ReadOnlyStringMap getContextData() {
        return event.getContextData();
    }

    @Override
    public ThreadContext.ContextStack getContextStack() {
        return event.getContextStack();
    }

    @Override
    public String getLoggerFqcn() {
        return event.getLoggerFqcn();
    }

    @Override
    public Level getLevel() {
        return event.getLevel();
    }

    @Override
    public String getLoggerName() {
        return event.getLoggerName();
    }

    @Override
    public Marker getMarker() {
        return event.getMarker();
    }

    @Override
    public Message getMessage() {
        return event.getMessage();
    }

    @Override
    public long getTimeMillis() {
        return event.getTimeMillis();
    }

    @Override
    public Instant getInstant() {
        return event.getInstant();
    }

    @Override
    public String getThreadName() {
        return event.getThreadName();
    }

    @Override
    public long getThreadId() {
        return event.getThreadId();
    }

    @Override
    public int getThreadPriority() {
        return event.getThreadPriority();
    }

    @Override
    public Throwable getThrown() {
        return event.getThrown();
    }

    @Override
    public ThrowableProxy getThrownProxy() {
        return event.getThrownProxy();
    }

    @Override
    public boolean isEndOfBatch() {
        return event.isEndOfBatch();
    }

    @Override
    public void setEndOfBatch(boolean endOfBatch) {
        // a view: the original keeps its own flag
    }

    @Override
    public long getNanoTime() {
        return event.getNanoTime();
    }
}
