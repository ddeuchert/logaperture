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
package org.logaperture.cli;

import org.logaperture.control.jmx.DoctorFindingData;
import org.logaperture.control.jmx.HandlerFloorData;
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerByteCountData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;
import org.logaperture.control.jmx.TopReportData;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A hand-written JSON writer for the {@code --json} output. The shapes are
 * tiny and fully controlled on both ends, so pulling in a JSON library
 * would add this graph's first third-party dependency for nothing — same
 * reasoning as doc/specs/persistence.md's hand-written state-file reader.
 * Object key order matches the {@code LoggerInfoData} / {@code
 * LevelOverrideData} getter order named in doc/specs/cli-transport.md.
 */
final class Json {

    private Json() {
    }

    static String loggers(List<LoggerInfoData> rows) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (LoggerInfoData row : rows) {
            array.add(logger(row));
        }
        return array.toString();
    }

    static String logger(LoggerInfoData row) {
        return new Obj()
                .str("name", row.getName())
                .str("configuredLevel", row.getConfiguredLevel())
                .str("effectiveLevel", row.getEffectiveLevel())
                .bool("overrideActive", row.isOverrideActive())
                .str("overrideSource", row.getOverrideSource())
                .str("overrideReason", row.getOverrideReason())
                .str("tier", row.getTier())
                .str("expiresAt", row.getExpiresAt())
                .toString();
    }

    static String override(LevelOverrideData data) {
        return overrideObj(data).toString();
    }

    private static Obj overrideObj(LevelOverrideData data) {
        return new Obj()
                .str("loggerName", data.getLoggerName())
                .str("level", data.getLevel())
                .bool("includeChildren", data.isIncludeChildren())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt());
    }

    /**
     * {@code setLevel}'s full JSON result: the override, plus {@code
     * warnings} — one entry per handler that will still swallow records at
     * the new level (doc/specs/handler-floor-control.md "Warning on level
     * commands"), empty on the common case of no such handler.
     */
    static String setLevelResult(SetLevelResultData result) {
        StringJoiner warnings = new StringJoiner(",", "[", "]");
        for (HandlerFloorData floor : result.getBlockingHandlers()) {
            warnings.add(new Obj()
                    .str("handlerRef", floor.getHandlerRef())
                    .str("currentLevel", floor.getCurrentLevel())
                    .toString());
        }
        return overrideObj(result.getOverride()).raw("warnings", warnings.toString()).toString();
    }

    static String handlerOverride(HandlerLevelOverrideData data) {
        return handlerOverrideObj(data).toString();
    }

    static String handlerOverrides(List<HandlerLevelOverrideData> rows) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (HandlerLevelOverrideData row : rows) {
            array.add(handlerOverride(row));
        }
        return array.toString();
    }

    private static Obj handlerOverrideObj(HandlerLevelOverrideData data) {
        return new Obj()
                .str("handlerRef", data.getHandlerRef())
                .str("level", data.getLevel())
                .str("mode", data.getMode())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt());
    }

    /**
     * {@code logctl status --json}: active logger overrides plus active
     * handler overrides, each an array under its own key — a plain array of
     * loggers alone would have no room to also carry the handler overrides
     * doc/specs/handler-floor-control.md's "logctl status shows handler
     * overrides too" calls for.
     */
    static String status(List<LoggerInfoData> loggerOverrides, List<HandlerLevelOverrideData> handlerOverrides) {
        return new Obj()
                .raw("loggers", loggers(loggerOverrides))
                .raw("handlerOverrides", handlerOverrides(handlerOverrides))
                .toString();
    }

    /** {@code logctl handler} against a framework whose handlers have no level of their own (Logback, {@code none}). */
    static String handlerNoOp(String handlerRef) {
        return new Obj()
                .str("handlerRef", handlerRef)
                .bool("changed", false)
                .toString();
    }

    static String handlerReset(String handlerRef) {
        return new Obj()
                .str("handlerRef", handlerRef)
                .bool("reset", true)
                .toString();
    }

    static String revertedCount(long count) {
        return "{\"reverted\":" + count + "}";
    }

    /**
     * {@code logctl doctor --json} — doc/specs/doctor.md "The operation".
     * {@code checksRun} is the count of distinct {@code check} ids among
     * {@code findings}, same figure the text renderer's summary line uses
     * ({@link #checksRun}).
     */
    static String doctor(List<DoctorFindingData> findings) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (DoctorFindingData finding : findings) {
            array.add(new Obj()
                    .str("check", finding.getCheck())
                    .str("severity", finding.getSeverity())
                    .str("subject", finding.getSubject())
                    .str("summary", finding.getSummary())
                    .str("detail", finding.getDetail())
                    .str("suggestedFix", finding.getSuggestedFix())
                    .str("context", finding.getContext())
                    .toString());
        }
        return new Obj().raw("findings", array.toString()).raw("checksRun", String.valueOf(checksRun(findings)))
                .toString();
    }

    /**
     * The count of distinct {@code check} ids among {@code findings} — one
     * check can flag more than one subject (e.g. two misconfigured handlers),
     * so this is not the same as {@code findings.size()}. Shared by both the
     * {@code --json} {@code checksRun} field and the text renderer's "N
     * checks run" summary line.
     */
    static int checksRun(List<DoctorFindingData> findings) {
        Set<String> checks = new LinkedHashSet<>();
        for (DoctorFindingData finding : findings) {
            checks.add(finding.getCheck());
        }
        return checks.size();
    }

    /**
     * {@code logctl top --json} — doc/specs/top.md "The operation". Raw
     * counts only, same as the wire {@link LoggerByteCountData} shape — rate
     * and the stack-trace percentage are the text renderer's own derivation
     * (doc/specs/top.md "Data model"); a script wanting them computes from
     * {@code totalBytes}/{@code stackTraceBytes} and {@code
     * measurementStartedAt} the same way. {@code trackedCount} is the true
     * count of tracked loggers, independent of {@code --limit} -- it can
     * exceed {@code loggers}' own size once the limit has truncated it.
     */
    static String top(TopReportData report) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (LoggerByteCountData logger : report.getLoggers()) {
            array.add(new Obj()
                    .str("loggerName", logger.getLoggerName())
                    .raw("totalBytes", String.valueOf(logger.getTotalBytes()))
                    .raw("stackTraceBytes", String.valueOf(logger.getStackTraceBytes()))
                    .str("context", logger.getContext())
                    .toString());
        }
        return new Obj()
                .raw("loggers", array.toString())
                .str("measurementStartedAt", report.getMeasurementStartedAt())
                .raw("trackedCount", String.valueOf(report.getTrackedCount()))
                .toString();
    }

    /**
     * {@code logctl handlers --json} — the addressable handler catalog
     * (doc/specs/handler-floor-control.md "The handler catalog", issue #15).
     * {@code level} / {@code autoFlush} / the override fields are {@code null}
     * where they don't apply (the {@code ALL_HANDLERS} row, a console
     * handler's {@code targetPath}, a handler with no override).
     */
    static String handlers(List<HandlerInfoData> rows) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (HandlerInfoData row : rows) {
            array.add(new Obj()
                    .str("ref", row.getRef())
                    .str("level", row.getLevel())
                    .bool("persistent", row.isPersistent())
                    .str("targetPath", row.getTargetPath())
                    .raw("autoFlush", row.getAutoFlush() == null ? "null" : String.valueOf(row.getAutoFlush()))
                    .bool("overrideActive", row.isOverrideActive())
                    .str("overrideLevel", row.getOverrideLevel())
                    .str("overrideMode", row.getOverrideMode())
                    .str("overrideTier", row.getOverrideTier())
                    .str("overrideExpiresAt", row.getOverrideExpiresAt())
                    .str("context", row.getContext())
                    .toString());
        }
        return new Obj().raw("handlers", array.toString()).toString();
    }

    /**
     * {@code reset <logger>} fell through with no post-reset {@link LoggerInfoData}
     * to emit — the logger is not "Live" and holds no override, so {@code
     * listLoggers} returns nothing for it. Report what is actually known instead
     * of a bare {@code null}: the name, that no override is active, and whether
     * this call cleared one.
     */
    static String reset(String loggerName, boolean wasOverridden) {
        return new Obj()
                .str("name", loggerName)
                .bool("overrideActive", false)
                .bool("wasOverridden", wasOverridden)
                .toString();
    }

    private static final class Obj {
        private final StringJoiner body = new StringJoiner(",", "{", "}");

        Obj str(String key, String value) {
            body.add(quote(key) + ":" + (value == null ? "null" : quote(value)));
            return this;
        }

        Obj bool(String key, boolean value) {
            body.add(quote(key) + ":" + value);
            return this;
        }

        /** Embeds an already-serialized JSON value verbatim -- e.g. an array built from other {@link Obj}s. */
        Obj raw(String key, String rawJsonValue) {
            body.add(quote(key) + ":" + rawJsonValue);
            return this;
        }

        @Override
        public String toString() {
            return body.toString();
        }
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
