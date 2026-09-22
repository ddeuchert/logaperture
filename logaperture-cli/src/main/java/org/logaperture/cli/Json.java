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
import org.logaperture.control.jmx.EnvironmentReportData;
import org.logaperture.control.jmx.HandlerFloorData;
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerByteCountData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.RuleData;
import org.logaperture.control.jmx.SetLevelResultData;
import org.logaperture.control.jmx.SquelchedLoggerData;
import org.logaperture.control.jmx.StormData;
import org.logaperture.control.jmx.StormReportData;
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
        return loggerObj(row).toString();
    }

    private static Obj loggerObj(LoggerInfoData row) {
        return new Obj()
                .str("name", row.getName())
                .str("configuredLevel", row.getConfiguredLevel())
                .str("effectiveLevel", row.getEffectiveLevel())
                .bool("overrideActive", row.isOverrideActive())
                .str("overrideSource", row.getOverrideSource())
                .str("overrideReason", row.getOverrideReason())
                .str("tier", row.getTier())
                .str("expiresAt", row.getExpiresAt());
    }

    static String override(LevelOverrideData data) {
        return overrideObj(data).toString();
    }

    private static Obj overrideObj(LevelOverrideData data) {
        return new Obj()
                .str("loggerName", data.getLoggerName())
                .str("level", data.getLevel())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt());
    }

    /**
     * {@code setLogger}'s full JSON result: {@code overrides} — one entry
     * for an exact-name target, zero or more for a pattern (doc/specs/
     * pattern-selection-semantics.md) — plus {@code warnings}, one entry per
     * handler that will still swallow records at the new level
     * (doc/specs/handler-floor-control.md "Warning on level commands"),
     * empty on the common case of no such handler.
     */
    static String setLevelResult(SetLevelResultData result) {
        StringJoiner overrides = new StringJoiner(",", "[", "]");
        for (LevelOverrideData override : result.getOverrides()) {
            overrides.add(overrideObj(override).toString());
        }
        StringJoiner warnings = new StringJoiner(",", "[", "]");
        for (HandlerFloorData floor : result.getBlockingHandlers()) {
            warnings.add(new Obj()
                    .str("handlerRef", floor.getHandlerRef())
                    .str("currentLevel", floor.getCurrentLevel())
                    .toString());
        }
        return new Obj().raw("overrides", overrides.toString()).raw("warnings", warnings.toString()).toString();
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
        StringJoiner warnings = new StringJoiner(",", "[", "]");
        for (SquelchedLoggerData squelched : data.getWarnings()) {
            warnings.add(new Obj()
                    .str("loggerName", squelched.getLoggerName())
                    .str("level", squelched.getLevel())
                    .toString());
        }
        return new Obj()
                .str("handlerRef", data.getHandlerRef())
                .str("level", data.getLevel())
                .str("mode", data.getMode())
                .str("reason", data.getReason())
                .str("appliedAt", data.getAppliedAt())
                .str("source", data.getSource())
                .str("tier", data.getTier())
                .str("expiresAt", data.getExpiresAt())
                .raw("warnings", warnings.toString());
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

    static String handlerReset(String handlerRef, boolean reverted) {
        return new Obj()
                .str("handlerRef", handlerRef)
                .bool("reset", reverted)
                .toString();
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
     * {@code logctl storms --json} — doc/specs/storm-detection.md "The
     * operation": {@code trackedCount}/{@code ongoingCount} are the true
     * pre-{@code --limit} counts, same discipline as {@code top}'s.
     */
    static String storms(StormReportData report) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (StormData storm : report.getStorms()) {
            array.add(new Obj()
                    .str("loggerName", storm.getLoggerName())
                    .str("level", storm.getLevel())
                    .str("throwableClass", storm.getThrowableClass())
                    .str("normalizedMessage", storm.getNormalizedMessage())
                    .raw("topFrames", storm.getTopFrames() == null ? "null" : stringArray(storm.getTopFrames()))
                    .str("status", storm.getStatus())
                    .str("firstEventAt", storm.getFirstEventAt())
                    .str("lastEventAt", storm.getLastEventAt())
                    .str("endedAt", storm.getEndedAt())
                    .raw("eventCount", String.valueOf(storm.getEventCount()))
                    .str("firstOccurrence", storm.getFirstOccurrence())
                    .str("context", storm.getContext())
                    .toString());
        }
        return new Obj()
                .raw("storms", array.toString())
                .raw("trackedCount", String.valueOf(report.getTrackedCount()))
                .raw("ongoingCount", String.valueOf(report.getOngoingCount()))
                .str("measurementStartedAt", report.getMeasurementStartedAt())
                .raw("notRetainedCount", String.valueOf(report.getNotRetainedCount()))
                .toString();
    }

    /**
     * {@code logctl list handlers --json} — the addressable handler catalog
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
                    .str("membersSummary", row.getMembersSummary())
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
        return resetObj(loggerName, wasOverridden).toString();
    }

    private static Obj resetObj(String loggerName, boolean wasOverridden) {
        return new Obj()
                .str("name", loggerName)
                .bool("overrideActive", false)
                .bool("wasOverridden", wasOverridden);
    }

    /**
     * {@code reset logger <exact-name> --json}, doc/specs/
     * rule-pipeline-foundation.md "Command surface": the same base shape
     * {@link #logger}/{@link #reset} already emit, with the rule-removal
     * side effect folded in rather than silently dropped (a code-review
     * finding against an earlier version of this call site, which computed
     * the outcome and then never wrote it to the JSON response).
     */
    static String resetLoggerWithRules(LoggerInfoData after, String target, boolean wasOverridden,
            List<String> removedRuleIds, List<String> skippedStickyRuleIds) {
        Obj obj = after != null ? loggerObj(after) : resetObj(target, wasOverridden);
        return obj
                .raw("removedRuleIds", stringArray(removedRuleIds))
                .raw("skippedStickyRuleIds", stringArray(skippedStickyRuleIds))
                .toString();
    }

    /**
     * {@code reset logger <pattern> --json} (doc/specs/
     * reset-command-surface.md): the loggers this call actually reverted --
     * every currently-matched logger that carried an active, non-skipped
     * override -- plus any it left alone for being {@code STICKY}. Both
     * empty when the pattern currently matches nothing overridden.
     */
    static String resetPattern(String pattern, List<String> reverted, List<String> skippedSticky) {
        return new Obj()
                .str("pattern", pattern)
                .raw("revertedLoggerNames", stringArray(reverted))
                .raw("skippedStickyLoggerNames", stringArray(skippedSticky))
                .toString();
    }

    /** {@code reset loggers --json} (doc/specs/reset-command-surface.md). */
    static String resetAllLoggers(List<String> reverted, List<String> skippedSticky) {
        return new Obj()
                .raw("revertedLoggerNames", stringArray(reverted))
                .raw("skippedStickyLoggerNames", stringArray(skippedSticky))
                .toString();
    }

    /** {@code reset handlers --json} (doc/specs/reset-command-surface.md). */
    static String resetAllHandlers(List<String> reverted, List<String> skippedSticky) {
        return new Obj()
                .raw("revertedHandlerRefs", stringArray(reverted))
                .raw("skippedStickyHandlerRefs", stringArray(skippedSticky))
                .toString();
    }

    /**
     * {@code set default-handler --json} / {@code reset default-handler --json}
     * (doc/specs/handler-floor-control.md
     * "Default handler group", issue #28) -- an empty array either means
     * "cleared, now rule-derived" (empty {@code names} was passed) or "set to
     * nothing," which {@code Commands.setDefaultHandlerMembers} never actually
     * allows through; the caller distinguishes those two by what it passed in,
     * not by this response.
     */
    static String defaultHandlerMembers(List<String> members) {
        return new Obj()
                .raw("defaultHandlerMembers", stringArray(members))
                .toString();
    }

    /** {@code logctl list rules --json} (doc/specs/rule-pipeline-foundation.md "Command surface"). */
    static String rules(List<RuleData> rows) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (RuleData row : rows) {
            array.add(rule(row));
        }
        return new Obj().raw("rules", array.toString()).toString();
    }

    static String rule(RuleData row) {
        return new Obj()
                .str("id", row.getId())
                .str("loggerName", row.getLoggerName())
                .str("action", row.getAction())
                .str("levelAtMost", row.getLevelAtMost())
                .str("messageContains", row.getMessageContains())
                .bool("messageIgnoreCase", row.isMessageIgnoreCase())
                .str("throwableType", row.getThrowableType())
                .str("throwableMessageContains", row.getThrowableMessageContains())
                .bool("anyCause", row.isAnyCause())
                .str("reason", row.getReason())
                .str("tier", row.getTier())
                .str("expiresAt", row.getExpiresAt())
                .str("createdAt", row.getCreatedAt())
                .str("context", row.getContext())
                .raw("hitCount", String.valueOf(row.getHitCount()))
                .toString();
    }

    /** {@code reset rule <id> --json} (doc/specs/rule-pipeline-foundation.md). */
    static String resetRule(String id, boolean removed) {
        return new Obj()
                .str("id", id)
                .bool("removed", removed)
                .toString();
    }

    /** {@code reset rules --json} / {@code reset logger <target> --json}'s rule side effect. */
    static String resetAllRules(List<String> removed, List<String> skippedSticky) {
        return new Obj()
                .raw("removedIds", stringArray(removed))
                .raw("skippedStickyIds", stringArray(skippedSticky))
                .toString();
    }

    private static String stringArray(List<String> values) {
        StringJoiner array = new StringJoiner(",", "[", "]");
        for (String value : values) {
            array.add(quote(value));
        }
        return array.toString();
    }

    /**
     * {@code logctl env --json} — doc/specs/environment-report.md "The
     * operation". A flat object; {@code cliVersion} is added here rather
     * than carried on {@code EnvironmentReportData} itself, same "logctl
     * already knows its own version locally" split the text renderer uses.
     * A fact this JVM can't resolve is {@code null}, matching the text
     * renderer's own "leave the line out" for backend/container.
     */
    static String env(EnvironmentReportData report, String cliVersion) {
        return new Obj()
                .str("agentVersion", report.getAgentVersion())
                .str("cliVersion", cliVersion)
                .str("javaVersion", report.getJavaVersion())
                .str("javaVendor", report.getJavaVendor())
                .str("osName", report.getOsName())
                .str("osVersion", report.getOsVersion())
                .str("osArch", report.getOsArch())
                .str("backendName", report.getBackendName())
                .str("backendVersion", report.getBackendVersion())
                .str("containerName", report.getContainerName())
                .str("containerVersion", report.getContainerVersion())
                .str("diagnosticsLevel", report.getDiagnosticsLevel())
                .str("stateFilePath", report.getStateFilePath())
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
