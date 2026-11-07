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
    private volatile Shared shared;
    private final boolean buildsProxy;

    /**
     * @param buildsProxy whether this appender's layout can render from the event's {@code ThrowableProxy}
     *                    ({@code %xEx} and the like): only then is the {@link TrimmedProxy} built, since the
     *                    proxy's constructor is eager and costs a stack walk per trimmed event
     */
    TrimAppender(Appender delegate, Shared shared, boolean buildsProxy) {
        super(delegate.getName(), null, delegate.getLayout(), delegate.ignoreExceptions(), Property.EMPTY_ARRAY);
        this.delegate = delegate;
        this.shared = shared;
        this.buildsProxy = buildsProxy;
    }

    boolean buildsProxy() {
        return buildsProxy;
    }

    // What the slot object must answer as the appender it stands in for (the control asks the slot):

    @Override
    public org.apache.logging.log4j.core.ErrorHandler getHandler() {
        return delegate.getHandler();
    }

    @Override
    public boolean requiresLocation() {
        return delegate instanceof org.apache.logging.log4j.core.impl.LocationAware aware
                ? aware.requiresLocation() : super.requiresLocation();
    }

    /** A wrapper in front of an appender the configuration has stopped must not claim to be started. */
    @Override
    public boolean isStarted() {
        return delegate.isStarted();
    }

    // The appender's own filter (a ThresholdFilter inside a <Console>, say). Log4j checks it on the object in
    // the slot, and delegate.append never does, so the wrapper has to answer for it or every event it should
    // deny would be written. Asked of the delegate each time, not copied: a filter added to or removed from the
    // appender later (a plugin, a programmatic call) is followed, and the delegate keeps ownership of it.

    @Override
    public boolean isFiltered(LogEvent event) {
        return delegate instanceof Filterable filterable && filterable.isFiltered(event);
    }

    @Override
    public Filter getFilter() {
        return delegate instanceof Filterable filterable ? filterable.getFilter() : null;
    }

    @Override
    public boolean hasFilter() {
        return delegate instanceof Filterable filterable && filterable.hasFilter();
    }

    Appender delegate() {
        return delegate;
    }

    Shared shared() {
        return shared;
    }

    /**
     * Takes this wrapper over for another adapter's {@link Shared}: a context that outlived its adapter keeps
     * the wrappers, tied to a gate that is now null, and a new adapter must not mistake them for its own
     * working wrappers and skip the slot.
     */
    void adopt(Shared newOwner) {
        this.shared = newOwner;
    }

    @Override
    public void append(LogEvent event) {
        delegate.append(trimmed(event));
    }

    /**
     * Whether anything behind this wrapper may print the caller location: the appender's own answer where it
     * has one ({@code AbstractAppender} asks its layout; an {@code <Async>} appender is asked for its
     * {@code includeLocation}), as Log4j itself decides it. An appender that cannot say is assumed to want it,
     * as before the fix: a wrong "no" would silently drop a field, a wrong "yes" only costs the walk.
     * (That is the opposite default to {@link #requiresLocation()} above, on purpose: that one answers Log4j's own
     * question by its convention, this one decides whether a field may be dropped.)
     */
    boolean needsLocation() {
        return !(delegate instanceof org.apache.logging.log4j.core.impl.LocationAware aware)
                || aware.requiresLocation();
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
            // Without the proxy the rebuilt event must not carry the original's (untrimmed) one over.
            // The builder asks the source event for its caller location, a stack walk on a synchronous logger's
            // event (#333): hand it a view that has none unless something behind this wrapper prints it.
            LogEvent source = !needsLocation() && LocationWalkProbe.viewPays() ? new SourcelessEvent(event) : event;
            return new Log4jLogEvent.Builder(source).setThrown(trimmed)
                    .setThrownProxy(buildsProxy ? new TrimmedProxy(trimmed) : null).build();
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
