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

import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
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
public final class AggregateLevelControl implements LevelControlOperations, HandlerLevelControlOperations {

    /**
     * One context: its {@link ContextHandle}, the single-context logger
     * service, and the single-context handler service that drive it.
     */
    public record ContextControl(ContextHandle handle, LevelControlService service,
            HandlerLevelControlService handlerService) {
        public ContextControl {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(handlerService, "handlerService");
        }

        String stableKey() {
            return handle.stableKey();
        }
    }

    private final Map<String, ContextControl> byKey = new ConcurrentHashMap<>();

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
                blockingByRef.putIfAbsent(floor.handlerRef(), floor);
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
     * @return total overrides re-applied across all contexts, loggers and handlers alike
     */
    public int verificationSweep(Instant now) {
        int reapplied = 0;
        for (ContextControl context : sortedByKey()) {
            reapplied += context.service().verifyAndReapply(now);
            reapplied += context.handlerService().verifyAndReapply(now);
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
