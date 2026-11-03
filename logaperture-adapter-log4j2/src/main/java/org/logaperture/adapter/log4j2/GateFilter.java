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
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.StormObserver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The one context-wide filter that is Log4j 2's gate (doc/specs/log4j2-adapter.md A3, "Scope" 4): drop
 * rules and storm detection, evaluated once per call, before any {@code LogEvent} exists.
 *
 * <p>It runs ahead of the level check on every call, disabled {@code debug()} in a hot loop included
 * (spike G2), so the first thing it does is compare the call's level with the logger's and leave if the
 * call would not be written; the fixed-arity overloads do that before allocating the argument array the
 * base class would. It never returns {@code ACCEPT}, which would skip the level check (G2); a rule
 * verdict is {@code DENY} and everything else {@code NEUTRAL}. Order is the drop rule's decision #3: a
 * denied event never reaches storm detection.
 *
 * <p>Fails open (§9): a rule or detector that throws is a throttled {@code Diagnostics} line and the
 * event passes. Both seams are optional and replaceable, so installing one again is idempotent.
 */
final class GateFilter extends AbstractFilter {

    /** Sampled once per storm engagement, never per event (storm-detection.md "Detection algorithm"). */
    private static final int MAX_TOP_FRAMES = 5;

    private volatile RuleGate gate;
    private volatile StormObserver storm;

    void setGate(RuleGate gate) {
        this.gate = gate;
    }

    void setStorm(StormObserver storm) {
        this.storm = storm;
    }

    /** Lets go of both seams: the filter stays on the configuration, inert, until it goes with it. */
    void release() {
        gate = null;
        storm = null;
    }

    // --- the entry points Log4j calls ---------------------------------------------------------------

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object... params) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, params, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, null, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3, p4}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3, p4, p5}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3, p4, p5, p6}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3, p4, p5, p6, p7}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3, p4, p5, p6, p7, p8}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, new Object[] {p0, p1, p2, p3, p4, p5, p6, p7, p8, p9}, null);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object message, Throwable thrown) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, null, thrown);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message message, Throwable thrown) {
        return skip(logger, level, message) ? Result.NEUTRAL : run(logger, level, message, null, thrown);
    }

    /** Never called by Log4j for a context-wide filter (spike G1: one evaluation per log call). */
    @Override
    public Result filter(LogEvent event) {
        return Result.NEUTRAL;
    }

    // --- the work -------------------------------------------------------------------------------------

    /**
     * Leave without allocating if there is nothing to do: an {@code isXxxEnabled()} guard (no message),
     * a call the logger's level would not write (G2), or no seam interested in this logger.
     */
    private boolean skip(Logger logger, Level level, Object message) {
        if (message == null || !level.isMoreSpecificThan(logger.getLevel())) {
            return true;
        }
        RuleGate rules = gate;
        StormObserver observer = storm;
        return (rules == null || !rules.appliesTo(logger.getName()))
                && (observer == null || !observer.isActive());
    }

    private Result run(Logger logger, Level level, Object message, Object[] params, Throwable thrown) {
        RuleGate rules = gate;
        StormObserver observer = storm;
        try {
            Call call = Call.of(logger.getName(), level, message, params, thrown);
            if (rules != null && rules.appliesTo(call.loggerName)) {
                GateVerdict verdict = rules.evaluateOnce(new RuleCandidateEvent(call.loggerName,
                        LevelMapper.toApi(level), call.thrown, call::formatted, Instant.now()));
                if (verdict.deny()) {
                    return Result.DENY; // never reaches storm detection (drop-rule.md decision #3)
                }
            }
            if (observer != null && observer.isActive()) {
                observer.observe(call.loggerName, LevelMapper.toApi(level),
                        call.thrown == null ? null : call.thrown.getClass().getName(),
                        call.format == null ? "" : call.format, Instant.now(), call, CALL_DETAILS);
            }
        } catch (RuntimeException e) {
            Diagnostics.warnThrottled("log4j2-gate", "rule or storm evaluation failed, event passes through unaffected: " + e,
                    null);
        }
        return Result.NEUTRAL;
    }

    /** One call's fields, resolved only when a seam needs them. */
    private static final class Call {
        final String loggerName;
        final String format;
        final Object[] params;
        final Throwable thrown;
        final Object message;
        final Level level;

        private Call(String loggerName, Level level, String format, Object[] params, Throwable thrown, Object message) {
            this.loggerName = loggerName;
            this.level = level;
            this.format = format;
            this.params = params;
            this.thrown = thrown;
            this.message = message;
        }

        static Call of(String loggerName, Level level, Object message, Object[] params, Throwable thrown) {
            if (message instanceof Message m) {
                return new Call(loggerName, level, m.getFormat(), m.getParameters(),
                        thrown != null ? thrown : m.getThrowable(), m);
            }
            String format = message instanceof String s ? s : String.valueOf(message);
            Throwable effective = thrown != null ? thrown : lastThrowable(format, params);
            return new Call(loggerName, level, format, params, effective, message);
        }

        /** The message with its parameters filled in, as an appender would write it: for a rule that matches text. */
        String formatted() {
            if (message instanceof Message m) {
                return m.getFormattedMessage();
            }
            if (params == null || params.length == 0) {
                return format;
            }
            return ParameterizedMessage.format(format, params);
        }

        /** Log4j's rule: a trailing {@code Throwable} not consumed by a placeholder is the call's throwable. */
        private static Throwable lastThrowable(String format, Object[] params) {
            if (params == null || params.length == 0 || !(params[params.length - 1] instanceof Throwable last)) {
                return null;
            }
            return placeholders(format) < params.length ? last : null;
        }

        private static int placeholders(String format) {
            int count = 0;
            for (int i = format.indexOf("{}"); i >= 0; i = format.indexOf("{}", i + 2)) {
                count++;
            }
            return count;
        }
    }

    /** Renders a storm's sampled details from the call that engaged it, during that same observe call. */
    private static final StormObserver.Details<Call> CALL_DETAILS = new StormObserver.Details<>() {
        @Override
        public List<String> topFrames(Call call) {
            if (call.thrown == null) {
                return null;
            }
            StackTraceElement[] trace = call.thrown.getStackTrace();
            List<String> frames = new ArrayList<>(Math.min(MAX_TOP_FRAMES, trace.length));
            for (int i = 0; i < trace.length && i < MAX_TOP_FRAMES; i++) {
                frames.add(trace[i].toString());
            }
            return frames;
        }

        @Override
        public String firstOccurrence(Call call) {
            StringBuilder text = new StringBuilder();
            text.append(Instant.now()).append(' ').append(call.level).append(" [").append(call.loggerName).append("] ")
                    .append(call.formatted());
            if (call.thrown != null) {
                StringWriter sink = new StringWriter();
                try (PrintWriter writer = new PrintWriter(sink)) {
                    call.thrown.printStackTrace(writer);
                }
                text.append('\n').append(sink);
            }
            return text.toString();
        }
    };
}
