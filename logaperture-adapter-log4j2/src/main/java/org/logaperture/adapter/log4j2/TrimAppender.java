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

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.filter.Filterable;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.TrimDecision;
import org.logaperture.core.TrimmedThrowables;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * An appender in a {@code LoggerConfig} slot wearing the original's name, layout and error policy, that
 * trims the throwable of a matching event before handing it on (doc/specs/log4j2-adapter.md A4, "Scope" 5;
 * spike T1). Layouts cannot be wrapped ({@code AbstractAppender} has no {@code setLayout}), so the event is
 * rebuilt instead, on a private copy: the original event, which may be a reused {@code MutableLogEvent}
 * or a ring-buffer slot, is never touched.
 *
 * <p>The gate counted the trim hit once, before the event existed; this recomputes only the choice, with
 * {@link RuleGate#trimChoice}, which never counts. It sees the event on whichever thread renders it, and
 * in front of an {@code <Async>} appender that is the calling thread, before the queue.
 */
final class TrimAppender extends AbstractAppender {

    /** What every wrapper of one adapter shares: the gate, replaceable, and null once the adapter lets go. */
    static final class Shared {
        volatile RuleGate gate;
    }

    private final Appender delegate;
    private final Shared shared;

    TrimAppender(Appender delegate, Shared shared) {
        super(delegate.getName(), filterOf(delegate), delegate.getLayout(), delegate.ignoreExceptions(),
                Property.EMPTY_ARRAY);
        this.delegate = delegate;
        this.shared = shared;
    }

    /**
     * The appender's own filter (a {@code ThresholdFilter} inside a {@code <Console>}, say). Log4j checks it on
     * the object in the slot, and {@code delegate.append} never does, so the wrapper has to carry it or every
     * event it should deny would be written. The delegate keeps ownership: this one is not stopped with it.
     */
    private static Filter filterOf(Appender appender) {
        return appender instanceof Filterable filterable ? filterable.getFilter() : null;
    }

    Appender delegate() {
        return delegate;
    }

    Shared shared() {
        return shared;
    }

    @Override
    public void append(LogEvent event) {
        delegate.append(trimmed(event));
    }

    /** The event itself unless a trim rule applies to its throwable; never throws (§9, fail open). */
    private LogEvent trimmed(LogEvent event) {
        Throwable thrown = event.getThrown();
        RuleGate gate = shared.gate;
        if (thrown == null || gate == null || !gate.appliesTo(event.getLoggerName())) {
            return event;
        }
        try {
            TrimDecision decision = gate.trimChoice(new RuleCandidateEvent(event.getLoggerName(),
                    LevelMapper.toApi(event.getLevel()), thrown, () -> event.getMessage().getFormattedMessage(),
                    Instant.ofEpochMilli(event.getTimeMillis())));
            if (decision == null) {
                return event;
            }
            Throwable trimmed = TrimmedThrowables.build(thrown, decision);
            return new Log4jLogEvent.Builder(event).setThrown(trimmed).setThrownProxy(new TrimmedProxy(trimmed))
                    .build();
        } catch (RuntimeException e) {
            Diagnostics.warnThrottled("log4j2-trim", "trim failed, event written untrimmed: " + e, null);
            return event;
        }
    }

    /** Stopping the wrapper must not stop the appender it stands in front of: the configuration owns that. */
    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        setStopped();
        return true;
    }
}
