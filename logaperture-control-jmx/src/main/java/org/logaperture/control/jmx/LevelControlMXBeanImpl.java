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
package org.logaperture.control.jmx;

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleChange;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.DoctorOperations;
import org.logaperture.core.EnvironmentReportOperations;
import org.logaperture.core.HandlerLevelControlOperations;
import org.logaperture.core.LevelControlOperations;
import org.logaperture.core.RuleOperations;
import org.logaperture.core.RuleView;
import org.logaperture.core.StormOperations;
import org.logaperture.core.TopOperations;
import org.logaperture.core.VendorDefaultsExportOperations;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Wraps a {@link LevelControlOperations} and a {@link
 * HandlerLevelControlOperations} — in production the same {@code
 * AggregateLevelControl} instance implements both, but this bean depends on
 * the two narrow interfaces rather than that concrete type, same as {@code
 * core} itself does. Converts between the {@code String}-based JMX boundary
 * and {@code api} types. Any {@code RuntimeException} thrown by the wrapped
 * operations (a {@code CapabilityDeniedException}, an adapter failure, an
 * {@code UnknownHandlerException}) is left to propagate -- JMX wraps these
 * in a {@code RuntimeMBeanException} for the remote caller automatically,
 * satisfying "surfaces to the JMX caller as a failed operation" (doc/specs/
 * level-control.md's failure handling).
 */
public final class LevelControlMXBeanImpl implements LevelControlMXBean {

    private final LevelControlOperations operations;
    private final HandlerLevelControlOperations handlerOperations;
    private final DoctorOperations doctorOperations;
    private final TopOperations topOperations;
    private final StormOperations stormOperations;
    private final RuleOperations ruleOperations;
    private final EnvironmentReportOperations environmentReportOperations;
    private final VendorDefaultsExportOperations exportOperations;

    public LevelControlMXBeanImpl(LevelControlOperations operations, HandlerLevelControlOperations handlerOperations,
            DoctorOperations doctorOperations, TopOperations topOperations, StormOperations stormOperations,
            RuleOperations ruleOperations, EnvironmentReportOperations environmentReportOperations,
            VendorDefaultsExportOperations exportOperations) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.handlerOperations = Objects.requireNonNull(handlerOperations, "handlerOperations");
        this.doctorOperations = Objects.requireNonNull(doctorOperations, "doctorOperations");
        this.topOperations = Objects.requireNonNull(topOperations, "topOperations");
        this.stormOperations = Objects.requireNonNull(stormOperations, "stormOperations");
        this.ruleOperations = Objects.requireNonNull(ruleOperations, "ruleOperations");
        this.environmentReportOperations = Objects.requireNonNull(environmentReportOperations, "environmentReportOperations");
        this.exportOperations = Objects.requireNonNull(exportOperations, "exportOperations");
    }

    @Override
    public List<LoggerInfoData> listLoggers(String filter) {
        return operations.listLoggers(filter).stream().map(LoggerInfoData::from).toList();
    }

    @Override
    public SetLevelResultData setLogger(String target, String level, String reason, String tier, long forSeconds,
            boolean confirmed) {
        Level parsedLevel = parseLevel(level);
        SetLevelOptions options = toOptions(reason, tier, forSeconds, confirmed);
        var result = operations.setLogger(target, parsedLevel, options);
        return SetLevelResultData.from(result);
    }

    @Override
    public ResetOutcomeData resetLogger(String target, boolean includeSticky) {
        return ResetOutcomeData.from(operations.resetLogger(target, includeSticky));
    }

    @Override
    public ResetOutcomeData resetAllLoggers(boolean includeSticky) {
        return ResetOutcomeData.from(operations.resetAllLoggers(includeSticky));
    }

    @Override
    public ResetOutcomeData resetLogger(String target, boolean includeSticky, boolean toNative) {
        return ResetOutcomeData.from(operations.resetLogger(target, includeSticky, toNative));
    }

    @Override
    public ResetOutcomeData resetAllLoggers(boolean includeSticky, boolean toNative) {
        return ResetOutcomeData.from(operations.resetAllLoggers(includeSticky, toNative));
    }

    @Override
    public HandlerLevelOverrideData setHandlerLevel(String handlerRef, String level, String reason, String tier,
            long forSeconds) {
        Level parsedLevel = parseLevel(level);
        SetHandlerLevelOptions options = toHandlerOptions(reason, tier, forSeconds);
        HandlerRef ref = new HandlerRef(handlerRef);
        // Read before the mutation, against the pre-raise handler level -- doc/specs/
        // handler-floor-control.md "Squelch warning" (issue #16).
        var squelched = handlerOperations.squelchedByRaise(ref, parsedLevel);
        return handlerOperations.setHandlerLevel(ref, parsedLevel, options)
                .map(override -> HandlerLevelOverrideData.from(override, squelched))
                .orElse(null); // this framework's handlers have no level of their own -- documented no-op
    }

    @Override
    public HandlerLevelOverrideData setHandlerAuto(String handlerRef, String reason, String tier, long forSeconds) {
        SetHandlerLevelOptions options = toHandlerOptions(reason, tier, forSeconds);
        return handlerOperations.setHandlerAuto(new HandlerRef(handlerRef), options)
                .map(HandlerLevelOverrideData::from)
                .orElse(null); // no level of its own, or (single-handler) nothing to track yet
    }

    @Override
    public HandlerResetOutcomeData resetHandler(String handlerRef, boolean includeSticky) {
        return HandlerResetOutcomeData.from(handlerOperations.resetHandler(new HandlerRef(handlerRef), includeSticky));
    }

    @Override
    public HandlerResetOutcomeData resetAllHandlers(boolean includeSticky) {
        return HandlerResetOutcomeData.from(handlerOperations.resetAllHandlers(includeSticky));
    }

    @Override
    public HandlerResetOutcomeData resetHandler(String handlerRef, boolean includeSticky, boolean toNative) {
        return HandlerResetOutcomeData.from(
                handlerOperations.resetHandler(new HandlerRef(handlerRef), includeSticky, toNative));
    }

    @Override
    public HandlerResetOutcomeData resetAllHandlers(boolean includeSticky, boolean toNative) {
        return HandlerResetOutcomeData.from(handlerOperations.resetAllHandlers(includeSticky, toNative));
    }

    @Override
    public List<HandlerLevelOverrideData> listHandlerOverrides() {
        return handlerOperations.listHandlerOverrides().stream().map(HandlerLevelOverrideData::from).toList();
    }

    @Override
    public List<HandlerInfoData> listHandlers() {
        return handlerOperations.listHandlers().stream().map(HandlerInfoData::from).toList();
    }

    @Override
    public List<String> setDefaultHandlerMembers(List<String> names) {
        List<HandlerRef> refs = names.stream().map(HandlerRef::new).toList();
        return handlerOperations.setDefaultHandlerMembers(refs).stream().map(HandlerRef::value).toList();
    }

    @Override
    public List<String> resetDefaultHandler(boolean toNative) {
        return handlerOperations.resetDefaultHandlerMembers(toNative).stream().map(HandlerRef::value).toList();
    }

    @Override
    public List<DoctorFindingData> diagnose() {
        return doctorOperations.diagnose().stream().map(DoctorFindingData::from).toList();
    }

    @Override
    public TopReportData topLoggers(int limit) {
        return TopReportData.from(topOperations.topLoggers(limit));
    }

    @Override
    public StormReportData activeStorms(int limit) {
        return StormReportData.from(stormOperations.activeStorms(limit));
    }

    @Override
    public EnvironmentReportData environmentReport() {
        return EnvironmentReportData.from(environmentReportOperations.environmentReport());
    }

    @Override
    public String exportVendorDefaults() {
        return exportOperations.exportVendorDefaults();
    }

    @Override
    public List<RuleData> listRules() {
        return ruleOperations.listRules().stream().map(RuleData::from).toList();
    }

    @Override
    public RuleData resetRule(String id, boolean includeSticky) {
        Optional<RuleView> removed = ruleOperations.resetRule(id, includeSticky);
        return removed.map(RuleData::from).orElse(null);
    }

    @Override
    public RuleResetOutcomeData resetAllRules(boolean includeSticky) {
        return RuleResetOutcomeData.from(ruleOperations.resetAllRules(includeSticky));
    }

    @Override
    public RuleResetOutcomeData resetRulesForLogger(String loggerName, boolean includeSticky) {
        return RuleResetOutcomeData.from(ruleOperations.resetRulesForLogger(loggerName, includeSticky));
    }

    @Override
    public RuleData resetRule(String id, boolean includeSticky, boolean toNative) {
        Optional<RuleView> removed = ruleOperations.resetRule(id, includeSticky, toNative);
        return removed.map(RuleData::from).orElse(null);
    }

    @Override
    public RuleResetOutcomeData resetAllRules(boolean includeSticky, boolean toNative) {
        return RuleResetOutcomeData.from(ruleOperations.resetAllRules(includeSticky, toNative));
    }

    @Override
    public RuleResetOutcomeData resetRulesForLogger(String loggerName, boolean includeSticky, boolean toNative) {
        return RuleResetOutcomeData.from(ruleOperations.resetRulesForLogger(loggerName, includeSticky, toNative));
    }

    @Override
    public RuleAlterationData alterRule(String id, String messageContains, boolean messageIgnoreCase,
            boolean clearMessage, String throwableType, boolean clearThrowable, String throwableMessageContains,
            boolean clearThrowableMessage, Boolean anyCause, String belowLevel, Boolean sampleFullEnabled,
            Long sampleFullEveryMillis, Integer frames, Boolean collapseCauses, String reason, String tier,
            long forSeconds) {
        SampleFullPolicy sampleFull = null;
        if (Boolean.FALSE.equals(sampleFullEnabled)) {
            sampleFull = SampleFullPolicy.disabled(); // the rule's own interval is kept (RuleService)
        } else if (Boolean.TRUE.equals(sampleFullEnabled)) {
            sampleFull = sampleFullEveryMillis == null ? SampleFullPolicy.defaults()
                    : SampleFullPolicy.every(Duration.ofMillis(sampleFullEveryMillis));
        }
        RuleChange change = new RuleChange(
                field(messageContains, clearMessage, "message"),
                messageIgnoreCase,
                field(throwableType, clearThrowable, "throwable"),
                field(throwableMessageContains, clearThrowableMessage, "throwable-message-contains"),
                anyCause,
                belowLevel == null ? null : parseLevel(belowLevel),
                sampleFull,
                frames,
                collapseCauses,
                reason);
        PersistenceTier parsedTier = tier == null ? null : parseTier(tier);
        Duration expiresIn = parsedTier == PersistenceTier.FOR ? Duration.ofSeconds(forSeconds) : null;
        return ruleOperations.alterRule(id, change, parsedTier, expiresIn).map(RuleAlterationData::from).orElse(null);
    }

    private static <T> RuleChange.Field<T> field(T value, boolean clear, String name) {
        if (value != null && clear) {
            throw new IllegalArgumentException("can't both set and clear " + name);
        }
        if (clear) {
            return RuleChange.Field.cleared();
        }
        return value == null ? RuleChange.Field.unchanged() : RuleChange.Field.set(value);
    }

    @Override
    public RuleData addRuleDrop(String target, String messageContains, boolean messageIgnoreCase,
            String throwableType, String throwableMessageContains, boolean anyCause, String belowLevel,
            boolean sampleFullEnabled, long sampleFullEveryMillis, String reason, String tier, long forSeconds) {
        if (messageContains == null && throwableType == null && throwableMessageContains == null) {
            throw new IllegalArgumentException(
                    "'add rule drop' needs at least one content matcher (message or throwable) -- "
                            + "use 'set logger' to change a logger's level instead.");
        }
        CompiledMatchers matchers = new CompiledMatchers(belowLevel == null ? null : parseLevel(belowLevel),
                messageContains, messageIgnoreCase, throwableType, throwableMessageContains, anyCause);
        SampleFullPolicy sampleFull =
                new SampleFullPolicy(sampleFullEnabled, Duration.ofMillis(sampleFullEveryMillis));
        RuleAttachOptions options = toRuleAttachOptions(reason, tier, forSeconds);
        return RuleData.from(ruleOperations.addRuleDrop(target, matchers, options, sampleFull));
    }

    @Override
    public RuleData addRuleTrim(String target, String messageContains, boolean messageIgnoreCase,
            String throwableType, String throwableMessageContains, boolean anyCause, String belowLevel, int frames,
            boolean collapseCauses, String reason, String tier, long forSeconds) {
        CompiledMatchers matchers = new CompiledMatchers(belowLevel == null ? null : parseLevel(belowLevel),
                messageContains, messageIgnoreCase, throwableType, throwableMessageContains, anyCause);
        RuleAttachOptions options = toRuleAttachOptions(reason, tier, forSeconds);
        return RuleData.from(ruleOperations.addRuleTrim(target, matchers, options, frames, collapseCauses));
    }

    private static SetLevelOptions toOptions(String reason, String tier, long forSeconds, boolean confirmed) {
        PersistenceTier parsedTier = parseTier(tier);
        Duration expiresIn = parsedTier == PersistenceTier.FOR ? Duration.ofSeconds(forSeconds) : null;
        return new SetLevelOptions(reason, expiresIn, parsedTier, confirmed);
    }

    private static SetHandlerLevelOptions toHandlerOptions(String reason, String tier, long forSeconds) {
        PersistenceTier parsedTier = parseTier(tier);
        Duration expiresIn = parsedTier == PersistenceTier.FOR ? Duration.ofSeconds(forSeconds) : null;
        return new SetHandlerLevelOptions(reason, expiresIn, parsedTier);
    }

    private static RuleAttachOptions toRuleAttachOptions(String reason, String tier, long forSeconds) {
        PersistenceTier parsedTier = parseTier(tier);
        Duration expiresIn = parsedTier == PersistenceTier.FOR ? Duration.ofSeconds(forSeconds) : null;
        return new RuleAttachOptions(reason, expiresIn, parsedTier);
    }

    private static PersistenceTier parseTier(String tier) {
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        try {
            return PersistenceTier.valueOf(tier.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown tier: '" + tier + "' (expected one of "
                    + List.of(PersistenceTier.values()) + ")", e);
        }
    }

    private static Level parseLevel(String level) {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        try {
            return Level.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown level: '" + level + "' (expected one of "
                    + List.of(Level.values()) + ")", e);
        }
    }
}
