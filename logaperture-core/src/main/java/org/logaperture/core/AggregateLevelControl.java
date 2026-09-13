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
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerByteCount;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.spi.ContextHandle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>{@code setLevel} / {@code resetLevel} / {@code resetAll} —
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
        DoctorOperations, TopOperations, EnvironmentReportOperations {

    /**
     * One context: its {@link ContextHandle}, the single-context logger
     * service, the single-context handler service, the single-context
     * doctor service, the single-context top service, and the
     * single-context environment-report service that drive it.
     */
    public record ContextControl(ContextHandle handle, LevelControlService service,
            HandlerLevelControlService handlerService, DoctorService doctorService, TopService topService,
            EnvironmentReportService environmentReportService) {
        public ContextControl {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(handlerService, "handlerService");
            Objects.requireNonNull(doctorService, "doctorService");
            Objects.requireNonNull(topService, "topService");
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
        this.containerName = containerName;
        this.containerVersion = Objects.requireNonNull(containerVersion, "containerVersion");
        this.stateFilePath = stateFilePath;
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
        List<DoctorFinding> result = new ArrayList<>();
        for (ContextControl context : sortedByKey()) {
            String key = context.stableKey();
            for (DoctorFinding finding : context.doctorService().diagnose()) {
                result.add(finding.withContext(key));
            }
        }
        return List.copyOf(result);
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
                stateFilePath);
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
    public SetLevelResult setLevel(String loggerName, Level level, SetLevelOptions options) {
        List<ContextControl> contexts = sortedByKey();
        if (contexts.isEmpty()) {
            throw new IllegalStateException("no logging context is registered yet");
        }
        // Broadcast, "all pass or all fail" (doc/specs/wildfly-support.md):
        // pre-check the capability in *every* context before mutating any,
        // because raise-vs-lower is judged against each context's own current
        // effective level and so can differ per context under a non-trivial
        // policy. A mid-broadcast adapter fault can still leave earlier
        // contexts changed; the verification sweep (Slice 3) reconciles that.
        for (ContextControl context : contexts) {
            context.service().checkSetLevelPermitted(loggerName, level, options);
        }
        LevelOverride fromSystem = null;
        LevelOverride fromAny = null;
        // Union of blocking handlers across every context, deduplicated by
        // ref -- a handler named e.g. CONSOLE in more than one context is
        // still just "CONSOLE" to the operator reading the warning.
        Map<HandlerRef, HandlerFloor> blockingByRef = new LinkedHashMap<>();
        for (ContextControl context : contexts) {
            SetLevelResult result = context.service().setLevel(loggerName, level, options);
            fromAny = result.override();
            if (ContextHandle.SYSTEM.equals(context.stableKey())) {
                fromSystem = result.override();
            }
            for (HandlerFloor floor : result.blockingHandlers()) {
                // Keep the stricter reading for a ref shared across
                // contexts, not merely the first seen -- same fix as
                // LevelControlService.setLevel's own merge, and for the
                // same reason: WildFly's collapsed ALL_HANDLERS ref can
                // legitimately report different levels from different
                // contexts (code-review finding).
                blockingByRef.merge(floor.handlerRef(), floor, LevelControlService::stricterFloor);
            }
        }
        return new SetLevelResult(fromSystem != null ? fromSystem : fromAny, List.copyOf(blockingByRef.values()));
    }

    @Override
    public void resetLevel(String loggerName) {
        for (ContextControl context : sortedByKey()) {
            context.service().resetLevel(loggerName);
        }
    }

    @Override
    public void resetAll() {
        for (ContextControl context : sortedByKey()) {
            context.service().resetAll();
            context.handlerService().resetAllHandlers();
        }
    }

    /**
     * Broadcasts {@code setHandlerLevel} across every registered context —
     * the {@link #setLevel} counterpart for handlers (doc/specs/
     * handler-floor-control.md "Multi-context (WildFly)"). Same "all pass or
     * all fail" capability pre-check; unlike {@code setLevel}, a per-context
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
    public void resetHandler(HandlerRef ref) {
        for (ContextControl context : sortedByKey()) {
            context.handlerService().resetHandler(ref);
        }
    }

    /**
     * Every handler override active anywhere in this aggregate — the {@link
     * #listLoggers} counterpart for handlers, feeding {@code logctl status}
     * (doc/specs/handler-floor-control.md "logctl status shows handler
     * overrides too"). Unioned by ref rather than tagged per context, same
     * as {@link #setLevel}'s blocking-handler union: a handler named e.g.
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
     * contexts is two real handlers). {@code logctl handlers} shows the
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
     * Runs the expiry sweep across every context — the composition root's
     * single scheduled task drives this instead of one-per-context (§15.5;
     * doc/specs/persistence.md's "the composition root owns <em>when</em>").
     * Covers logger and handler overrides alike.
     */
    public void sweepExpiredOverrides(Instant now) {
        for (ContextControl context : sortedByKey()) {
            context.service().sweepExpiredOverrides(now);
            context.handlerService().sweepExpiredOverrides(now);
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
        for (ContextControl context : sortedByKey()) {
            reapplied += context.service().verifyAndReapply(now);
            reapplied += context.handlerService().verifyAndReapply(now);
            context.topService().startMeasuring();
        }
        return reapplied;
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
