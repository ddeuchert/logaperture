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
package org.logaperture.core;

import org.logaperture.api.BackendInfo;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.EnvironmentReport;
import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerInfo;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.HandlerResetOutcome;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerByteCount;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.api.Severity;
import org.logaperture.api.SquelchedLogger;
import org.logaperture.api.Storm;
import org.logaperture.api.StormReport;
import org.logaperture.core.spi.ContextHandle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fans {@link LevelControlOperations} out across every logging context a
 * container has — see doc/specs/wildfly-support.md, "Multi-context
 * aggregation in {@code core}". Each context keeps its own untouched
 * single-context {@link LevelControlService}; this class holds the map of
 * them and applies the multi-context semantics on top:
 *
 * <ul>
 *   <li>{@code listLoggers} — concatenate every context's rows, each tagged
 *       with its context's {@code stableKey}.</li>
 *   <li>{@code setLogger} / {@code resetLogger} / {@code resetAllLoggers} —
 *       <b>broadcast</b>: apply to the named logger in <em>every</em>
 *       registered context. There is no per-call context selector (override
 *       scoping is a deferred nice-to-have, doc/specs/wildfly-support.md).</li>
 * </ul>
 *
 * <p>For a plain {@code java -jar} app there is exactly one context
 * ({@code "system"}), so every fan-out here collapses to a single delegate
 * call. The multi-context paths are exercised by tests with fake contexts.
 */
public final class AggregateLevelControl implements LevelControlOperations, HandlerLevelControlOperations,
        DoctorOperations, TopOperations, StormOperations, EnvironmentReportOperations, RuleOperations {

    /**
     * One context: its {@link ContextHandle}, the single-context logger
     * service, the single-context handler service, the single-context
     * doctor service, the single-context top service, the single-context
     * storm service, the single-context rule service, and the single-context
     * environment-report service that drive it.
     */
    public record ContextControl(ContextHandle handle, LevelControlService service,
            HandlerLevelControlService handlerService, DoctorService doctorService, TopService topService,
            StormService stormService, RuleService ruleService, EnvironmentReportService environmentReportService) {
        public ContextControl {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(handlerService, "handlerService");
            Objects.requireNonNull(doctorService, "doctorService");
            Objects.requireNonNull(topService, "topService");
            Objects.requireNonNull(stormService, "stormService");
            Objects.requireNonNull(ruleService, "ruleService");
            Objects.requireNonNull(environmentReportService, "environmentReportService");
        }

        String stableKey() {
            return handle.stableKey();
        }
    }

    /**
     * Same property {@code Diagnostics.LEVEL_PROPERTY} in {@code
     * logaperture-bridge} names — duplicated rather than depending on that
     * module for one string, the same independent-duplication precedent
     * {@code AgentBootstrap.VERSION_PROPERTY}/{@code Discovery.MARKER_PROPERTY}
     * already set for {@code "logaperture.version"}.
     */
    private static final String DIAGNOSTICS_LEVEL_PROPERTY = "logaperture.diagnostics.level";

    private final Map<String, ContextControl> byKey = new ConcurrentHashMap<>();

    /** The detected container's display name — doc/specs/environment-report.md; {@code null} for {@code none}. */
    private final String containerName;
    /**
     * Its best-effort version — a <em>supplier</em>, re-invoked fresh on
     * every {@link #environmentReport()} call, deliberately not resolved
     * once and cached. {@code WildFlyContainerIntegration.version()}'s own
     * javadoc has the real-WildFly story: at the point a container's
     * composition root would otherwise resolve this eagerly (premain time),
     * the fact often isn't available yet (e.g. {@code jboss.home.dir} is not
     * yet visible to {@code System.getProperty} in every real launch
     * tried) — resolving once here would silently bake in "no version"
     * forever instead of self-healing once the server finishes its own
     * bootstrap.
     */
    private final Supplier<Optional<String>> containerVersion;

    /**
     * The fully-qualified path of this JVM's {@code StateStore} location —
     * doc/specs/environment-report.md "State file". Unlike {@link
     * #containerVersion}, resolved once and stored plainly: it comes from
     * the composition root's already-open {@code StateStore} (opened
     * before this class is ever constructed), not from a fact that might
     * only become resolvable after the container finishes its own
     * bootstrap. {@code null} when this JVM's {@code StateStore} has no
     * single filesystem location to name (session-only degraded mode, or a
     * future non-file-backed store).
     */
    private final String stateFilePath;

    /**
     * Whether {@link #installHandlerLevel()} may run yet -- the container's
     * floor (doc/specs/wildfly-deferred-handler-install.md). Always-true for
     * a container with no reason to defer ({@code none}).
     */
    private final BooleanSupplier handlerInstallAllowed;

    /**
     * The vendor defaults file this JVM was started with -- doc/specs/vendor-defaults.md. Each
     * context applies it at install; this copy is what {@code status}/{@code env}/{@code doctor}
     * report on. {@link VendorDefaults#none()} when no file was configured.
     */
    private final VendorDefaults vendorDefaults;

    /**
     * Set once {@link #installHandlerLevel()} has succeeded -- every step, none swallowed -- for at
     * least one context. A context whose steps threw is retried on a later sweep and does not count.
     */
    private volatile boolean handlerLevelInstalled;

    /**
     * Serializes the handler-level installs: the container's installing thread, its one-shot, the
     * sweep and the configuration listener can all reach {@link #installHandlerLevel(ContextControl)},
     * and each step is check-then-wrap, which two concurrent callers could both pass.
     */
    private final Object handlerInstallLock = new Object();

    /** No container, no known state file — the minimal construction tests reach for; production always supplies both (even {@code none} passes its real {@code StateStore} location through the 3-arg constructor). */
    public AggregateLevelControl() {
        this(null, Optional::empty, null);
    }

    /**
     * @param containerName    the detected container/framework's display
     *                         name, e.g. {@code "WildFly"} — {@code null}
     *                         for {@code none}
     * @param containerVersion supplies its best-effort version on demand,
     *                         or {@code Optional.empty()}; see the field doc
     * @param stateFilePath    this JVM's {@code StateStore} location,
     *                         fully qualified, or {@code null}; see the
     *                         field doc
     */
    public AggregateLevelControl(String containerName, Supplier<Optional<String>> containerVersion,
            String stateFilePath) {
        this(containerName, containerVersion, stateFilePath, () -> true);
    }

    /**
     * @param handlerInstallAllowed gates {@link #installHandlerLevel()} and the
     *                              handler-level half of {@link #verificationSweep};
     *                              see the field doc
     */
    public AggregateLevelControl(String containerName, Supplier<Optional<String>> containerVersion,
            String stateFilePath, BooleanSupplier handlerInstallAllowed) {
        this(containerName, containerVersion, stateFilePath, handlerInstallAllowed, VendorDefaults.none());
    }

    /**
     * @param vendorDefaults the vendor defaults file this JVM was started with; see the field doc
     */
    public AggregateLevelControl(String containerName, Supplier<Optional<String>> containerVersion,
            String stateFilePath, BooleanSupplier handlerInstallAllowed, VendorDefaults vendorDefaults) {
        this.vendorDefaults = Objects.requireNonNull(vendorDefaults, "vendorDefaults");
        this.containerName = containerName;
        this.containerVersion = Objects.requireNonNull(containerVersion, "containerVersion");
        this.stateFilePath = stateFilePath;
        this.handlerInstallAllowed = Objects.requireNonNull(handlerInstallAllowed, "handlerInstallAllowed");
    }

    /** The vendor defaults file this JVM was started with ({@link VendorDefaults#none()} if none). */
    public VendorDefaults vendorDefaults() {
        return vendorDefaults;
    }

    /**
     * Adds a context discovered during the container's initial sweep. The
     * context's {@link LevelControlService} is expected to have already
     * resumed its persisted state (the composition root does that before
     * calling here).
     */
    public void register(ContextControl control) {
        byKey.put(control.stableKey(), control);
    }

    /**
     * Adds a context that appeared <em>after</em> initial discovery — a
     * redeploy, a reload — and re-broadcasts every currently-active override
     * onto it, so a {@code --sticky} (or still-live {@code --for}) override
     * re-applies itself against the new context without being re-issued
     * (doc/specs/wildfly-support.md, "The redeploy loop"). Slice 1 exercises
     * this with fake contexts only; {@code none} never calls it.
     */
    public void addContext(ContextControl control) {
        Instant now = Instant.now();
        Optional<ContextControl> existingAny = byKey.values().stream().findFirst();
        List<LevelOverride> toRebroadcast = existingAny
                .map(existing -> existing.service().activeOverrides())
                .orElseGet(List::of)
                .stream()
                .filter(override -> isStillLive(override.tier(), override.expiresAt(), now))
                .toList();
        List<HandlerLevelOverride> handlersToRebroadcast = existingAny
                .map(existing -> existing.handlerService().listHandlerOverrides())
                .orElseGet(List::of)
                .stream()
                .filter(override -> isStillLive(override.tier(), override.expiresAt(), now))
                .toList();
        byKey.put(control.stableKey(), control);
        for (LevelOverride override : toRebroadcast) {
            control.service().adoptOverride(override);
        }
        for (HandlerLevelOverride override : handlersToRebroadcast) {
            control.handlerService().adoptOverride(override);
        }
        // A reset --to-native lasts until restart in every context (doc/specs/reset-to-native.md),
        // including one that registers after it -- copied over like the overrides above.
        existingAny.ifPresent(existing -> {
            control.service().adoptResetToNative(existing.service().resetToNativeLoggerNames());
            control.handlerService().adoptResetToNative(existing.handlerService().resetToNativeHandlerRefs(),
                    existing.handlerService().defaultHandlersResetToNative());
        });
        // One recompute pass now that both halves have been rebroadcast onto
        // the new context -- doc/specs/handler-floor-control.md "AUTO
        // handler level", AUTO-5: an AUTO override rebroadcast above carries
        // whatever level its origin context had computed, possibly stale by
        // the time it lands here alongside a freshly-rebroadcast logger
        // override set.
        control.handlerService().recomputeAuto();
    }

    /**
     * A {@code FOR} override whose deadline has already passed is not
     * re-broadcast onto a fresh context — it would be applied and audited as
     * live, then reverted only on the next sweep tick (up to the sweep
     * interval later). The other contexts' expiry sweep handles the real
     * reversion; a context that never held it needs no reversion record.
     */
    private static boolean isStillLive(PersistenceTier tier, Instant expiresAt, Instant now) {
        return tier != PersistenceTier.FOR || expiresAt.isAfter(now);
    }

    /**
     * Drops a context whose deployment went away. Its persisted overrides
     * stay in the {@code StateStore} — an undeploy is not a reset
     * (doc/specs/wildfly-support.md).
     */
    public void removeContext(String stableKey) {
        byKey.remove(stableKey);
    }

    /** How many contexts are currently registered. */
    public int contextCount() {
        return byKey.size();
    }

    /** Whether a context with this {@code stableKey} is registered. */
    public boolean hasContext(String stableKey) {
        return byKey.containsKey(stableKey);
    }

    /** The registered contexts, ordered by {@code stableKey}. */
    public List<ContextControl> contexts() {
        return sortedByKey();
    }

    @Override
    public List<LoggerInfo> listLoggers(String filter) {
        List<LoggerInfo> result = new ArrayList<>();
        for (ContextControl context : sortedByKey()) {
            String key = context.stableKey();
            for (LoggerInfo info : context.service().listLoggers(filter)) {
                result.add(info.withContext(key));
            }
        }
        return List.copyOf(result);
    }

    /**
     * {@code logctl doctor} across every registered context — the {@link
     * #listLoggers} counterpart for diagnosis (doc/specs/doctor.md). Every
     * context's findings are concatenated, each tagged with its context's
     * {@code stableKey}; unlike {@code listHandlerOverrides}, findings are
     * never unioned/deduped across contexts, since two contexts can
     * legitimately have different underlying configuration to report on.
     */
    @Override
    public List<DoctorFinding> diagnose() {
        List<DoctorFinding> result = new ArrayList<>(vendorDefaultsFindings());
        for (ContextControl context : sortedByKey()) {
            String key = context.stableKey();
            for (DoctorFinding finding : context.doctorService().diagnose()) {
                result.add(finding.withContext(key));
            }
            for (HandlerRef pending : context.handlerService().pendingVendorHandlers()) {
                result.add(new DoctorFinding("vendor-defaults.unresolved-handler", Severity.INFO, pending.value(),
                        "the vendor defaults file sets handler '" + pending.value() + "', but no such handler "
                                + "exists in this context yet.",
                        "it will be applied as soon as the handler appears; if it never does, check the name "
                                + "against 'logctl list handlers --show-all'.",
                        null).withContext(key));
            }
        }
        return List.copyOf(result);
    }

    /**
     * doc/specs/vendor-defaults.md "Surfaces": the file is process-wide, so its findings carry no
     * context. Nothing at all when no file was configured.
     */
    private List<DoctorFinding> vendorDefaultsFindings() {
        String path = vendorDefaults.path().map(Object::toString).orElse(null);
        return switch (vendorDefaults.status()) {
            case NOT_CONFIGURED -> List.of();
            case REJECTED -> List.of(new DoctorFinding("vendor-defaults.file", Severity.WARNING, path,
                    "the vendor defaults file was rejected -- none of its settings apply.",
                    String.join("\n", vendorDefaults.errors()),
                    "correct the file and restart the application."));
            case LOADED -> {
                List<DoctorFinding> findings = new ArrayList<>();
                findings.add(new DoctorFinding("vendor-defaults.file", Severity.OK, path,
                        "vendor defaults loaded (" + vendorDefaults.summary() + ").", null, null));
                if (vendorDefaults.writable()) {
                    findings.add(new DoctorFinding("vendor-defaults.writable", Severity.WARNING, path,
                            "the vendor defaults file, or its directory, is writable by the account this JVM runs "
                                    + "as.",
                            "anyone who can run code as that account can change the baseline logging "
                                    + "configuration.",
                            "make the file and its directory read-only for this account."));
                }
                yield List.copyOf(findings);
            }
        };
    }

    /**
     * {@code logctl top} across every registered context — the {@link
     * #diagnose} counterpart for byte-volume measurement (doc/specs/top.md).
     * Every context's rows are merged, each tagged with its context's {@code
     * stableKey}, then re-sorted worst-first and truncated to {@code limit}
     * over the merged set (not per context) so a global top-10 is genuinely
     * the ten worst loggers, not the worst ten-per-context.
     *
     * <p>{@code measurementStartedAt} reports the <em>earliest</em> window
     * among registered contexts. A context installed later than that instant
     * under-reports its own rate for as long as its own window is shorter
     * than the merged one shown — the same "give it time" caveat as any
     * freshly-started measurement, not a defect; doc/specs/top.md's own
     * "On interpretation" section covers the broader limits of this number.
     */
    @Override
    public TopReport topLoggers(int limit) {
        List<LoggerByteCount> merged = new ArrayList<>();
        Instant earliest = null;
        for (ContextControl context : sortedByKey()) {
            String key = context.stableKey();
            TopReport report = context.topService().topLoggers(0); // unlimited -- limit applies after merging
            for (LoggerByteCount row : report.loggers()) {
                merged.add(row.withContext(key));
            }
            Instant started = report.measurementStartedAt();
            if (started != null && (earliest == null || started.isBefore(earliest))) {
                earliest = started;
            }
        }
        merged.sort(Comparator.comparingLong(LoggerByteCount::totalBytes).reversed());
        List<LoggerByteCount> limited = limit > 0 && merged.size() > limit ? merged.subList(0, limit) : merged;
        return new TopReport(List.copyOf(limited), earliest, merged.size());
    }

    /**
     * {@code logctl storms} across every registered context — the {@link
     * #topLoggers} counterpart for storm detection (doc/specs/
     * storm-detection.md). Every context's storms are merged, each tagged
     * with its context's {@code stableKey}, then re-sorted worst-first and
     * truncated to {@code limit} over the merged set (not per context).
     * {@code trackedCount}/{@code ongoingCount}/{@code notRetainedCount} are
     * summed across contexts, pre-truncation. {@code measurementStartedAt}
     * reports the earliest window among registered contexts, same convention
     * as {@link #topLoggers}.
     */
    @Override
    public StormReport activeStorms(int limit) {
        List<Storm> merged = new ArrayList<>();
        Instant earliest = null;
        int trackedCount = 0;
        int ongoingCount = 0;
        int notRetainedCount = 0;
        for (ContextControl context : sortedByKey()) {
            String key = context.stableKey();
            StormReport report = context.stormService().activeStorms(0); // unlimited -- limit applies after merging
            for (Storm storm : report.storms()) {
                merged.add(storm.withContext(key));
            }
            trackedCount += report.trackedCount();
            ongoingCount += report.ongoingCount();
            notRetainedCount += report.notRetainedCount();
            Instant started = report.measurementStartedAt();
            if (started != null && (earliest == null || started.isBefore(earliest))) {
                earliest = started;
            }
        }
        merged.sort(StormDetector.worstFirst());
        List<Storm> limited = limit > 0 && merged.size() > limit ? merged.subList(0, limit) : merged;
        return new StormReport(List.copyOf(limited), trackedCount, ongoingCount, earliest, notRetainedCount);
    }

    /**
     * {@code logctl list rules} across every registered context — the
     * {@link #activeStorms} counterpart for the rule pipeline (doc/specs/
     * rule-pipeline-foundation.md "Command surface"). Every context's rules
     * are tagged with its {@code stableKey} via {@link RuleView} (not
     * {@code LogRule::withContext} — see that type's class doc). No
     * re-sorting/truncation the way {@code topLoggers}/{@code activeStorms}
     * do: there is no "worst first" ordering for rules in this slice.
     */
    @Override
    public List<RuleView> listRules() {
        List<RuleView> result = new ArrayList<>();
        for (ContextControl context : sortedByKey()) {
            String key = context.stableKey();
            for (RuleView view : context.ruleService().listRules()) {
                // The 3-arg constructor -- re-stamping context via the 2-arg one silently
                // defaulted hitCount back to 0, discarding what RuleService just computed (a
                // code-review finding: every rule showed HITS=0 in production regardless of how
                // many events it had actually matched).
                result.add(view.withContext(key));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Broadcasts {@code reset rule <id>} across every registered context. A
     * rule lives in exactly one context's registry, so only one context's
     * call actually finds and removes it; the others are no-ops.
     *
     * <p>Rule ids are scoped per context and can collide across contexts
     * (doc/specs/rule-pipeline-foundation.md "Rule identity": each {@code
     * RuleService} mints its own sequence independently, disambiguated on
     * read by {@code context}, same as a {@code HandlerRef}). So a {@code
     * STICKY} refusal from the <em>first</em> context whose id happens to
     * match must not stop a later context's own, unrelated match under the
     * same bare id from being tried — that refusal is remembered and
     * surfaced only if no other context yields a real match (a code-review
     * finding against an earlier version of this method, which let the
     * first refusal abort the whole call).
     *
     * <p>A vendor defaults rule ({@code vendor:<name>}) is the exception: the file is applied
     * to every context, so the same id genuinely lives in each one, and a suspension must reach
     * all of them rather than stop at the first (doc/specs/vendor-defaults.md "Rules").
     */
    @Override
    public Optional<RuleView> resetRule(String id, boolean includeSticky, boolean includeVendorDefaults) {
        boolean vendorId = id.startsWith(VendorDefaults.RULE_ID_PREFIX);
        Optional<RuleView> firstRemoved = Optional.empty();
        IllegalArgumentException stickyRefusal = null;
        for (ContextControl context : sortedByKey()) {
            try {
                Optional<RuleView> removed = context.ruleService().resetRule(id, includeSticky, includeVendorDefaults);
                if (removed.isPresent()) {
                    if (!vendorId) {
                        return removed;
                    }
                    if (firstRemoved.isEmpty()) {
                        firstRemoved = removed;
                    }
                }
            } catch (IllegalArgumentException e) {
                stickyRefusal = e;
            }
        }
        if (firstRemoved.isPresent()) {
            return firstRemoved;
        }
        if (stickyRefusal != null) {
            throw stickyRefusal;
        }
        return Optional.empty();
    }

    @Override
    public RuleResetOutcome resetAllRules(boolean includeSticky, boolean includeVendorDefaults) {
        List<String> removed = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        List<String> skippedVendor = new ArrayList<>();
        for (ContextControl context : sortedByKey()) {
            RuleResetOutcome outcome = context.ruleService().resetAllRules(includeSticky, includeVendorDefaults);
            removed.addAll(outcome.removedIds());
            skippedSticky.addAll(outcome.skippedStickyIds());
            skippedVendor.addAll(outcome.skippedVendorIds());
        }
        return new RuleResetOutcome(removed, skippedSticky, skippedVendor);
    }

    @Override
    public RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky,
            boolean includeVendorDefaults) {
        List<String> removed = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        List<String> skippedVendor = new ArrayList<>();
        for (ContextControl context : sortedByKey()) {
            RuleResetOutcome outcome =
                    context.ruleService().resetRulesForLogger(loggerName, includeSticky, includeVendorDefaults);
            removed.addAll(outcome.removedIds());
            skippedSticky.addAll(outcome.skippedStickyIds());
            skippedVendor.addAll(outcome.skippedVendorIds());
        }
        return new RuleResetOutcome(removed, skippedSticky, skippedVendor);
    }

    /**
     * {@code logctl add rule drop} — doc/specs/drop-rule.md "Command
     * surface". Attaches to the first registered context only (deterministic
     * by {@code stableKey}, same ordering every other fan-out here uses) —
     * multi-context fan-out and leading-star pattern-target expansion are
     * deferred past this pass, per {@link RuleOperations#addRuleDrop}'s own
     * note.
     *
     * @throws IllegalStateException if no context is registered
     */
    @Override
    public RuleView addRuleDrop(String loggerName, CompiledMatchers matchers, RuleAttachOptions options,
            SampleFullPolicy sampleFull) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context registered -- nothing to attach a rule to");
        }
        return contexts.get(0).ruleService().addRuleDrop(loggerName, matchers, options, sampleFull);
    }

    /**
     * {@code logctl add rule trim} — doc/specs/trim-rule.md "Command
     * surface". Attaches to the first registered context only, same scope
     * reduction as {@link #addRuleDrop}.
     *
     * @throws IllegalStateException if no context is registered
     */
    @Override
    public RuleView addRuleTrim(String loggerName, CompiledMatchers matchers, RuleAttachOptions options, int frames,
            boolean collapseCauses) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context registered -- nothing to attach a rule to");
        }
        return contexts.get(0).ruleService().addRuleTrim(loggerName, matchers, options, frames, collapseCauses);
    }

    /**
     * The periodic drop-summary line's scheduling entry point (doc/specs/
     * drop-rule.md "Periodic summary line") — fanned out to every
     * registered context's own {@link RuleService#reportDueDropSummaries},
     * called from the same sweep tick that already drives expiry and
     * reconfiguration re-application. A misbehaving context must not stop
     * its siblings from reporting.
     */
    public void reportDueDropSummaries(Instant now) {
        for (ContextControl context : sortedByKey()) {
            try {
                context.ruleService().reportDueDropSummaries(now);
            } catch (RuntimeException e) {
                System.err.println("[logaperture] failed to report drop summaries for context '"
                        + context.handle().stableKey() + "', continuing: " + e);
            }
        }
    }

    /**
     * {@code logctl env} — doc/specs/environment-report.md. Agent/JVM/OS
     * facts and the detected container are process-wide, computed here
     * directly; the logging backend is the one fact that can, in principle,
     * differ per context (§15.4), so this takes the first non-empty {@link
     * BackendInfo} among registered contexts (in {@code stableKey} order,
     * for determinism) rather than requiring every context to agree — no
     * implemented container actually produces more than one distinct
     * backend today (doc/specs/environment-report.md "Explicitly out of
     * scope"). Zero registered contexts is not an error, unlike a mutation:
     * the report still carries every process-wide fact, just no backend.
     */
    @Override
    public EnvironmentReport environmentReport() {
        BackendInfo backend = BackendInfo.EMPTY;
        for (ContextControl context : sortedByKey()) {
            try {
                BackendInfo candidate = context.environmentReportService().backendInfo();
                if (candidate.name() != null) {
                    backend = candidate;
                    break;
                }
            } catch (RuntimeException e) {
                // A misbehaving adapter must not fail the whole report -- doc/specs/
                // environment-report.md "Failure handling": an unresolved fact is left
                // out, never a command failure. Same per-context isolation as
                // setHandlerLevel/setHandlerAuto's own broadcast loops.
                System.err.println("[logaperture-core] backendInfo() failed in context '"
                        + context.stableKey() + "', treating its backend as unresolved: " + e);
            }
        }
        return new EnvironmentReport(
                agentVersion(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                backend.name(),
                backend.version(),
                containerName,
                resolveContainerVersion(),
                System.getProperty(DIAGNOSTICS_LEVEL_PROPERTY),
                stateFilePath,
                vendorDefaults.path().map(Object::toString).orElse(null),
                vendorDefaultsStatusLine());
    }

    /**
     * {@link VendorDefaults#statusLine()}, plus how many of its entries are reset to native
     * (doc/specs/reset-to-native.md "Surfaces") -- counted in the first context, since resets
     * broadcast to every context.
     */
    private String vendorDefaultsStatusLine() {
        String line = vendorDefaults.statusLine();
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            return line;
        }
        ContextControl first = contexts.get(0);
        int resetToNative = first.service().resetToNativeCount() + first.handlerService().resetToNativeCount();
        return resetToNative == 0 ? line : line + ", " + resetToNative + " reset to native";
    }

    /** {@link #containerVersion}'s supplier is third-party code (a {@code ContainerIntegration}'s own); a throw there must degrade the same as a throwing adapter, never fail the whole report. */
    private String resolveContainerVersion() {
        try {
            return containerVersion.get().orElse(null);
        } catch (RuntimeException e) {
            System.err.println("[logaperture-core] the container version supplier failed, treating it as unresolved: " + e);
            return null;
        }
    }

    /**
     * Same manifest resolution {@code AgentBootstrap.agentVersion()} uses,
     * read from this class's own package instead — {@code core} must never
     * depend on {@code agent} (§4.6), but both modules are stamped with the
     * same {@code Implementation-Version} from one Maven reactor build
     * (§11.1's "one version number, released together"), so this resolves
     * to the same string.
     */
    private static String agentVersion() {
        String version = AggregateLevelControl.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev"; // null when run from classes dir, e.g. a unit test
    }

    @Override
    public SetLevelResult setLogger(String loggerName, Level level, SetLevelOptions options) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context is registered yet");
        }
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;
        boolean isPattern = NameFilter.isPattern(loggerName);

        // An unconfirmed pattern throws from the very first context checked
        // below otherwise, so its ConfirmationRequiredException would carry
        // only that one context's matches (a code-review finding) -- every
        // context is unconfirmed alike here, so gather each one's matches
        // first and throw a single exception naming the union across the
        // whole aggregate, the real blast radius a caller decides against.
        if (isPattern && !opts.confirmed()) {
            List<String> allMatches = new ArrayList<>();
            for (ContextControl context : contexts) {
                try {
                    context.service().checkSetLevelPermitted(loggerName, level, opts);
                } catch (ConfirmationRequiredException e) {
                    allMatches.addAll(e.matches());
                }
            }
            throw new ConfirmationRequiredException(loggerName, allMatches);
        }

        // Broadcast, "all pass or all fail" (doc/specs/wildfly-support.md):
        // pre-check the capability in *every* context before mutating any,
        // because raise-vs-lower is judged against each context's own current
        // effective level and so can differ per context under a non-trivial
        // policy. A mid-broadcast adapter fault can still leave earlier
        // contexts changed; the verification sweep (Slice 3) reconciles that.
        //
        // Each context's resolved match list is kept, not discarded, and
        // threaded into the apply loop below via setLogger's resolved-match
        // overload -- a code-review finding: resolving a pattern's matches
        // is a full scan of the context's known loggers plus overrides, and
        // the apply loop used to pay for that scan a second time immediately
        // after this pre-flight had already computed the identical answer.
        Map<String, List<String>> resolvedMatchesByContext = new LinkedHashMap<>();
        for (ContextControl context : contexts) {
            resolvedMatchesByContext.put(context.stableKey(),
                    context.service().checkSetLevelPermittedAndResolve(loggerName, level, opts));
        }
        // For a pattern target, every context's current matches, concatenated
        // -- the only shape that makes sense once a single call can produce
        // several overrides in the first place. For an exact-name target,
        // one representative override (preferring SYSTEM), same as every
        // other broadcast operation here -- every context's own
        // LevelControlService still independently creates and persists its
        // own override underneath, this is only what's reported back
        // (doc/specs/pattern-level-targeting.md: "overrides has exactly one
        // entry for an exact-name target").
        List<LevelOverride> allOverrides = new ArrayList<>();
        LevelOverride fromSystem = null;
        LevelOverride fromAny = null;
        // Union of blocking handlers across every context, deduplicated by
        // ref -- a handler named e.g. CONSOLE in more than one context is
        // still just "CONSOLE" to the operator reading the warning.
        Map<HandlerRef, HandlerFloor> blockingByRef = new LinkedHashMap<>();
        for (ContextControl context : contexts) {
            SetLevelResult result = context.service().setLogger(
                    loggerName, level, opts, resolvedMatchesByContext.get(context.stableKey()));
            if (isPattern) {
                allOverrides.addAll(result.overrides());
            } else if (!result.overrides().isEmpty()) {
                LevelOverride override = result.overrides().get(0);
                fromAny = override;
                if (ContextHandle.SYSTEM.equals(context.stableKey())) {
                    fromSystem = override;
                }
            }
            for (HandlerFloor floor : result.blockingHandlers()) {
                // Keep the stricter reading for a ref shared across
                // contexts, not merely the first seen -- same fix as
                // LevelControlService.setLogger's own merge, and for the
                // same reason: WildFly's collapsed ALL_HANDLERS ref can
                // legitimately report different levels from different
                // contexts (code-review finding).
                blockingByRef.merge(floor.handlerRef(), floor, LevelControlService::stricterFloor);
            }
        }
        List<LevelOverride> reportedOverrides = isPattern
                ? allOverrides
                : List.of(fromSystem != null ? fromSystem : fromAny);
        return new SetLevelResult(reportedOverrides, List.copyOf(blockingByRef.values()));
    }

    @Override
    public ResetOutcome resetLogger(String target, boolean includeSticky) {
        return resetLogger(target, includeSticky, false);
    }

    /** doc/specs/reset-to-native.md -- broadcast like every reset. */
    @Override
    public ResetOutcome resetLogger(String target, boolean includeSticky, boolean toNative) {
        // A dedup by name, not a concatenation: two contexts sharing a
        // logger (or matched by the same pattern) each report reverting it
        // independently, and the caller-facing list should name it once,
        // same reasoning as setLogger's one-override-per-logger fix above.
        // No "all pass or all fail" pre-flight for the sticky refusal
        // (Decision #1's IllegalArgumentException) -- same accepted
        // residual-effect convention this broadcast already has for a
        // capability denial part-way through (see the class doc's "a
        // mid-broadcast adapter fault can still leave earlier contexts
        // changed").
        Set<String> reverted = new LinkedHashSet<>();
        Set<String> skippedSticky = new LinkedHashSet<>();
        for (ContextControl context : sortedByKey()) {
            ResetOutcome outcome = context.service().resetLogger(target, includeSticky, toNative);
            reverted.addAll(outcome.revertedLoggerNames());
            skippedSticky.addAll(outcome.skippedStickyLoggerNames());
        }
        return new ResetOutcome(List.copyOf(reverted), List.copyOf(skippedSticky));
    }

    @Override
    public ResetOutcome resetAllLoggers(boolean includeSticky) {
        return resetAllLoggers(includeSticky, false);
    }

    @Override
    public ResetOutcome resetAllLoggers(boolean includeSticky, boolean toNative) {
        Set<String> reverted = new LinkedHashSet<>();
        Set<String> skippedSticky = new LinkedHashSet<>();
        for (ContextControl context : sortedByKey()) {
            ResetOutcome outcome = context.service().resetAllLoggers(includeSticky, toNative);
            reverted.addAll(outcome.revertedLoggerNames());
            skippedSticky.addAll(outcome.skippedStickyLoggerNames());
        }
        return new ResetOutcome(List.copyOf(reverted), List.copyOf(skippedSticky));
    }

    /**
     * Broadcasts {@code setHandlerLevel} across every registered context —
     * the {@link #setLogger} counterpart for handlers (doc/specs/
     * handler-floor-control.md "Multi-context (WildFly)"). Same "all pass or
     * all fail" capability pre-check; unlike {@code setLogger}, a per-context
     * adapter fault here is caught and skipped rather than left for a later
     * sweep to reconcile (doc/specs/handler-floor-control.md "Failure
     * handling") -- there is no handler verification sweep in this slice.
     */
    @Override
    public Optional<HandlerLevelOverride> setHandlerLevel(HandlerRef ref, Level level, SetHandlerLevelOptions options) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context is registered yet");
        }
        for (ContextControl context : contexts) {
            context.handlerService().checkSetHandlerLevelPermitted(ref, level, options);
        }
        HandlerLevelOverride fromSystem = null;
        HandlerLevelOverride fromAny = null;
        int succeeded = 0;
        for (ContextControl context : contexts) {
            try {
                Optional<HandlerLevelOverride> applied = context.handlerService().setHandlerLevel(ref, level, options);
                succeeded++;
                if (applied.isPresent()) {
                    fromAny = applied.get();
                    if (ContextHandle.SYSTEM.equals(context.stableKey())) {
                        fromSystem = applied.get();
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("[logaperture-core] setHandlerLevel(" + ref + ") failed in context '"
                        + context.stableKey() + "', that context is unchanged: " + e);
            }
        }
        if (succeeded == 0) {
            throw new IllegalStateException("setHandlerLevel(" + ref + ") failed in every context");
        }
        // Every context succeeding as a no-op (no handler levels anywhere,
        // e.g. Logback) is not a failure -- doc/specs/handler-floor-control.md
        // "Logback / none".
        return Optional.ofNullable(fromSystem != null ? fromSystem : fromAny);
    }

    /**
     * {@code squelchedByRaise}'s multi-context broadcast (doc/specs/
     * handler-floor-control.md "Squelch warning", issue #16) -- read-only, no
     * capability pre-check needed (nothing is mutated). Same representative-answer
     * preference {@link #setHandlerLevel}/{@link #setHandlerAuto} already use: the
     * {@code system} context's answer when it has one, else the first non-empty
     * answer from any context, since {@code ref}'s pre-raise level (and therefore
     * what counts as newly squelched) can genuinely differ per context.
     */
    @Override
    public List<SquelchedLogger> squelchedByRaise(HandlerRef ref, Level newLevel) {
        List<SquelchedLogger> fromSystem = null;
        List<SquelchedLogger> fromAny = null;
        for (ContextControl context : sortedByKey()) {
            List<SquelchedLogger> squelched = context.handlerService().squelchedByRaise(ref, newLevel);
            if (!squelched.isEmpty()) {
                if (fromAny == null) {
                    fromAny = squelched;
                }
                if (ContextHandle.SYSTEM.equals(context.stableKey())) {
                    fromSystem = squelched;
                }
            }
        }
        return fromSystem != null ? fromSystem : (fromAny != null ? fromAny : List.of());
    }

    /**
     * Broadcasts {@code setHandlerAuto} across every registered context —
     * the {@link #setHandlerLevel} counterpart for {@code AUTO} (doc/specs/
     * handler-floor-control.md "AUTO handler level", issue #20). Same "all
     * pass or all fail" capability pre-check and per-context fault isolation
     * as {@link #setHandlerLevel}.
     */
    @Override
    public Optional<HandlerLevelOverride> setHandlerAuto(HandlerRef ref, SetHandlerLevelOptions options) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context is registered yet");
        }
        for (ContextControl context : contexts) {
            context.handlerService().checkSetHandlerAutoPermitted(options);
        }
        HandlerLevelOverride fromSystem = null;
        HandlerLevelOverride fromAny = null;
        int succeeded = 0;
        for (ContextControl context : contexts) {
            try {
                Optional<HandlerLevelOverride> applied = context.handlerService().setHandlerAuto(ref, options);
                succeeded++;
                if (applied.isPresent()) {
                    fromAny = applied.get();
                    if (ContextHandle.SYSTEM.equals(context.stableKey())) {
                        fromSystem = applied.get();
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("[logaperture-core] setHandlerAuto(" + ref + ") failed in context '"
                        + context.stableKey() + "', that context is unchanged: " + e);
            }
        }
        if (succeeded == 0) {
            throw new IllegalStateException("setHandlerAuto(" + ref + ") failed in every context");
        }
        return Optional.ofNullable(fromSystem != null ? fromSystem : fromAny);
    }

    @Override
    public HandlerResetOutcome resetHandler(HandlerRef ref, boolean includeSticky) {
        return resetHandler(ref, includeSticky, false);
    }

    @Override
    public HandlerResetOutcome resetHandler(HandlerRef ref, boolean includeSticky, boolean toNative) {
        // Same "no pre-flight for the sticky refusal" convention as
        // resetLogger above.
        Set<HandlerRef> reverted = new LinkedHashSet<>();
        Set<HandlerRef> skippedSticky = new LinkedHashSet<>();
        for (ContextControl context : sortedByKey()) {
            HandlerResetOutcome outcome = context.handlerService().resetHandler(ref, includeSticky, toNative);
            reverted.addAll(outcome.revertedHandlerRefs());
            skippedSticky.addAll(outcome.skippedStickyHandlerRefs());
        }
        return new HandlerResetOutcome(List.copyOf(reverted), List.copyOf(skippedSticky));
    }

    @Override
    public HandlerResetOutcome resetAllHandlers(boolean includeSticky) {
        return resetAllHandlers(includeSticky, false);
    }

    @Override
    public HandlerResetOutcome resetAllHandlers(boolean includeSticky, boolean toNative) {
        Set<HandlerRef> reverted = new LinkedHashSet<>();
        Set<HandlerRef> skippedSticky = new LinkedHashSet<>();
        for (ContextControl context : sortedByKey()) {
            HandlerResetOutcome outcome = context.handlerService().resetAllHandlers(includeSticky, toNative);
            reverted.addAll(outcome.revertedHandlerRefs());
            skippedSticky.addAll(outcome.skippedStickyHandlerRefs());
        }
        return new HandlerResetOutcome(List.copyOf(reverted), List.copyOf(skippedSticky));
    }

    /**
     * Every handler override active anywhere in this aggregate — the {@link
     * #listLoggers} counterpart for handlers, feeding {@code logctl status}
     * (doc/specs/handler-floor-control.md "logctl status shows handler
     * overrides too"). Unioned by ref rather than tagged per context, same
     * as {@link #setLogger}'s blocking-handler union: a handler named e.g.
     * CONSOLE in more than one context is still just "CONSOLE" to the
     * operator reading the list, and {@link HandlerLevelOverride} carries no
     * context of its own to tag rows with in the first place.
     */
    @Override
    public List<HandlerLevelOverride> listHandlerOverrides() {
        Map<HandlerRef, HandlerLevelOverride> byRef = new LinkedHashMap<>();
        for (ContextControl context : sortedByKey()) {
            for (HandlerLevelOverride override : context.handlerService().listHandlerOverrides()) {
                byRef.putIfAbsent(override.handlerRef(), override);
            }
        }
        return List.copyOf(byRef.values());
    }

    /**
     * The addressable handler catalog across every registered context — the
     * {@link #listLoggers} counterpart for handlers (doc/specs/
     * handler-floor-control.md "The handler catalog", issue #15). Every row
     * carries its owning context's {@code stableKey}; unlike {@link
     * #listHandlerOverrides} it is <em>not</em> unioned by ref, since a
     * catalog is per context (a handler named {@code CONSOLE} in two
     * contexts is two real handlers). {@code logctl list handlers} shows the
     * {@code [context]} prefix only when the result spans more than one.
     */
    @Override
    public List<HandlerInfo> listHandlers() {
        List<HandlerInfo> rows = new ArrayList<>();
        for (ContextControl context : sortedByKey()) {
            for (HandlerInfo info : context.handlerService().listHandlers()) {
                rows.add(info.withContext(context.stableKey()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * {@code logctl set default-handler <name>...}'s multi-context
     * broadcast (doc/specs/handler-floor-control.md "Default handler
     * group", issue #28) -- membership is assigned per context ("Multi-
     * context (WildFly)": the same real-handler name can validate in one
     * context and not another), so this applies to every context and
     * tolerates one context's {@code UnknownHandlerException}/capability
     * failure without aborting the rest, same fault isolation {@link
     * #setHandlerLevel} uses. Representative answer: the {@code system}
     * context's result when it has one, else the first context's.
     */
    @Override
    public List<HandlerRef> setDefaultHandlerMembers(List<HandlerRef> names) {
        Function<HandlerLevelControlService, List<HandlerRef>> setMembers =
                service -> service.setDefaultHandlerMembers(names);
        return broadcastDefaultHandlers("setDefaultHandlerMembers", setMembers);
    }

    /**
     * {@code logctl reset default-handler [--to-native]}'s broadcast (doc/specs/
     * reset-to-native.md) -- the same per-context fault isolation and representative answer as
     * {@link #setDefaultHandlerMembers}.
     */
    @Override
    public List<HandlerRef> resetDefaultHandlerMembers(boolean toNative) {
        Function<HandlerLevelControlService, List<HandlerRef>> resetMembers =
                service -> service.resetDefaultHandlerMembers(toNative);
        return broadcastDefaultHandlers("resetDefaultHandlerMembers", resetMembers);
    }

    /**
     * Applies a {@code DEFAULT_HANDLERS} membership change to every context, tolerating one
     * context's {@code UnknownHandlerException}/capability failure without aborting the rest
     * (membership is per context: the same real-handler name can validate in one context and
     * not another). Representative answer: the {@code system} context's result when it has one,
     * else the first context's.
     */
    private List<HandlerRef> broadcastDefaultHandlers(String operation,
            Function<HandlerLevelControlService, List<HandlerRef>> change) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context is registered yet");
        }
        List<HandlerRef> fromSystem = null;
        List<HandlerRef> fromAny = null;
        int succeeded = 0;
        for (ContextControl context : contexts) {
            try {
                List<HandlerRef> result = change.apply(context.handlerService());
                succeeded++;
                if (fromAny == null) {
                    fromAny = result;
                }
                if (ContextHandle.SYSTEM.equals(context.stableKey())) {
                    fromSystem = result;
                }
            } catch (RuntimeException e) {
                System.err.println("[logaperture-core] " + operation + " failed in context '"
                        + context.stableKey() + "', that context is unchanged: " + e);
            }
        }
        if (succeeded == 0) {
            throw new IllegalStateException(operation + " failed in every context");
        }
        return fromSystem != null ? fromSystem : fromAny;
    }

    /**
     * Runs the expiry sweep across every context — the composition root's
     * single scheduled task drives this instead of one-per-context (§15.5;
     * doc/specs/persistence.md's "the composition root owns <em>when</em>").
     * Covers logger and handler overrides alike, and {@code for <duration>} rules (issue #95).
     */
    public void sweepExpiredOverrides(Instant now) {
        for (ContextControl context : sortedByKey()) {
            context.service().sweepExpiredOverrides(now);
            context.handlerService().sweepExpiredOverrides(now);
            context.ruleService().sweepExpiredRules(now);
        }
    }

    /**
     * Runs the verification sweep across every context
     * (doc/specs/wildfly-support.md, §15.5) — re-applies any override a
     * framework reconfiguration has silently overwritten. Driven by the
     * composition root, from its periodic tick and (for WildFly) from a
     * `LogManager` configuration-change event. Covers handler overrides too
     * (doc/specs/handler-floor-control.md "Reconfiguration re-application" —
     * previously a documented gap: loggers had this, handlers didn't).
     *
     * <p>Also re-confirms every context's byte-counting wrap is still in
     * place (doc/specs/top.md "Reconfiguration and lifecycle") — {@code top}
     * has no reconfiguration event of its own to hook, so this periodic tick
     * is the mechanism that re-wraps a handler a framework silently replaced,
     * the same floor level-control overrides already rely on here.
     *
     * @return total overrides re-applied across all contexts, loggers and handlers alike
     */
    public int verificationSweep(Instant now) {
        int reapplied = 0;
        boolean handlersOpen = handlerInstallAllowed.getAsBoolean();
        for (ContextControl context : sortedByKey()) {
            reapplied += context.service().verifyAndReapply(now);
            reapplied += context.handlerService().verifyAndReapply(now);
            if (handlersOpen && installHandlerLevelSerialized(context)) {
                handlerLevelInstalled = true;
            }
        }
        return reapplied;
    }

    /**
     * Phase 2 of a container's install -- the steps that put a filter or
     * formatter on a handler: trim rendering, top's byte counting, storm
     * detection, the rule pipeline, in that order for every registered
     * context (doc/specs/wildfly-deferred-handler-install.md "Two phases").
     * Held back until the container's {@code handlerInstallAllowed} gate opens;
     * every step is idempotent, so this is safe to call from the container, the
     * sweep and the configuration listener alike.
     *
     * @return {@code true} if the gate was open and phase 2 ran; {@code false} if it was held back
     */
    public boolean installHandlerLevel() {
        if (!handlerInstallAllowed.getAsBoolean()) {
            return false;
        }
        for (ContextControl context : sortedByKey()) {
            if (installHandlerLevelSerialized(context)) {
                handlerLevelInstalled = true;
            }
        }
        return true;
    }

    /** {@code true} once {@link #installHandlerLevel()} has fully succeeded for at least one context. */
    public boolean isHandlerLevelInstalled() {
        return handlerLevelInstalled;
    }

    /**
     * Every step is guarded on its own: this runs from a scheduled tick (and the
     * container's one-shot) with nothing above it to catch a throw, and a failure
     * here must neither cancel future ticks nor reach the host server -- it is
     * simply retried on the next tick.
     */
    private boolean installHandlerLevelSerialized(ContextControl context) {
        synchronized (handlerInstallLock) {
            return installHandlerLevel(context);
        }
    }

    /** @return {@code true} if every step completed without throwing */
    private static boolean installHandlerLevel(ContextControl context) {
        boolean allSucceeded = true;
        try {
            // Must run before topService.startMeasuring() below -- doc/specs/trim-rule.md
            // "Interaction with top": trim's formatter wrap installs inside top's, so top
            // measures the bytes actually written post-trim.
            context.ruleService().installTrimRendering();
        } catch (RuntimeException e) {
            allSucceeded = false;
            System.err.println("[logaperture-core] failed to (re-)arm trim rendering for context '"
                    + context.stableKey() + "', that context is unchanged: " + e);
        }
        try {
            context.topService().startMeasuring();
        } catch (RuntimeException e) {
            allSucceeded = false;
            System.err.println("[logaperture-core] failed to (re-)arm byte counting for context '"
                    + context.stableKey() + "', that context is unchanged: " + e);
        }
        try {
            // doc/specs/storm-detection.md "Failure handling": a detector bug must never break
            // anything else.
            context.stormService().startDetection();
        } catch (RuntimeException e) {
            allSucceeded = false;
            System.err.println("[logaperture-core] failed to (re-)arm storm detection for context '"
                    + context.stableKey() + "', that context is unchanged: " + e);
        }
        try {
            // doc/specs/rule-pipeline-foundation.md "Relationship to the storm-detection filter".
            context.ruleService().installPipeline();
        } catch (RuntimeException e) {
            allSucceeded = false;
            System.err.println("[logaperture-core] failed to (re-)arm the rule pipeline for context '"
                    + context.stableKey() + "', that context is unchanged: " + e);
        }
        return allSucceeded;
    }

    private List<ContextControl> sortedByKey() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(ContextControl::stableKey))
                .toList();
    }

    /** Package-visible view for tests. */
    Collection<ContextControl> rawContexts() {
        return List.copyOf(byKey.values());
    }
}
