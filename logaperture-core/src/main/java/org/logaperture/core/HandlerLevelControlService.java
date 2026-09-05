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

import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;
import org.logaperture.core.spi.UnknownHandlerException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The handler-level-control engine — the {@link LevelControlService}
 * counterpart for {@code logctl handler <name> <level>}, per doc/specs/
 * handler-floor-control.md. Same ordering discipline as {@link
 * LevelControlService}: <b>capability check &rarr; adapter mutation &rarr;
 * registry commit &rarr; state-store write &rarr; audit record</b>.
 *
 * <p>Deliberately a separate class from {@link LevelControlService} rather
 * than folded into it: independent lifetime (doc/specs/
 * handler-floor-control.md "Independent lifetime" — a handler override is
 * not coupled to any logger override), so it needs none of {@link
 * LevelControlService}'s fan-out/overlap machinery, only its own baseline,
 * registry, and persistence, one entry per {@link HandlerRef}.
 */
public final class HandlerLevelControlService implements HandlerLevelControlOperations {

    private final LoggingAdapter adapter;
    private final HandlerBaselineRegistry baselines;
    private final HandlerOverrideRegistry overrides;
    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final StateStore stateStore;
    private final String principal;
    private final String source;

    public HandlerLevelControlService(
            LoggingAdapter adapter,
            HandlerBaselineRegistry baselines,
            HandlerOverrideRegistry overrides,
            CapabilityPolicy policy,
            AuditLog auditLog,
            StateStore stateStore,
            String principal,
            String source) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.baselines = Objects.requireNonNull(baselines, "baselines");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Optional<HandlerLevelOverride> setHandlerLevel(HandlerRef ref, Level level, SetHandlerLevelOptions options) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(level, "level");
        SetHandlerLevelOptions opts = options == null ? SetHandlerLevelOptions.defaults() : options;

        if (!adapter.hasHandlerLevels()) {
            return Optional.empty(); // doc/specs/handler-floor-control.md "Logback / none" -- documented no-op
        }
        checkSetHandlerLevelPermitted(ref, level, opts);
        return Optional.of(applyAndRecordMutation(ref, level, opts));
    }

    /**
     * Runs {@code setHandlerLevel}'s capability pre-flight without mutating
     * anything — the {@link LevelControlService#checkSetLevelPermitted}
     * counterpart for handlers. {@link AggregateLevelControl} calls this
     * against every context before broadcasting a {@code setHandlerLevel},
     * for the same "all pass or all fail" reason. A context whose adapter
     * has no handler levels never needs a grant -- there is nothing for it
     * to authorize.
     */
    public void checkSetHandlerLevelPermitted(HandlerRef ref, Level level, SetHandlerLevelOptions options) {
        if (!adapter.hasHandlerLevels()) {
            return;
        }
        SetHandlerLevelOptions opts = options == null ? SetHandlerLevelOptions.defaults() : options;
        // Empty means this context's adapter doesn't resolve ref at all --
        // nothing to authorize here (the actual attempt will no-op/throw
        // UnknownHandlerException on its own, caught by the broadcast).
        Optional<Capability> required = requiredCapabilityFor(ref, level);
        if (required.isPresent() && !policy.isGranted(required.get())) {
            throw new CapabilityDeniedException(required.get());
        }
        if (opts.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }
    }

    @Override
    public void resetHandler(HandlerRef ref) {
        Objects.requireNonNull(ref, "ref");
        Optional<HandlerLevelOverride> existing = overrides.get(ref);
        if (existing.isEmpty()) {
            return; // no-op, not an error -- matches resetLevel
        }
        // Simplification for this slice, matching LevelControlService.resetLevel:
        // every reset requires HANDLER_LOWER, regardless of whether reverting
        // to baseline happens to raise or lower this particular handler.
        requireCapability(Capability.HANDLER_LOWER);
        applyReset(ref, existing.get(), source);
    }

    /**
     * Reverts every active handler override — the {@link
     * LevelControlService#resetAll} counterpart for handlers, called by
     * {@link AggregateLevelControl#resetAll} alongside the logger reset so
     * {@code logctl reset --all} covers both (doc/specs/
     * handler-floor-control.md "The operation").
     */
    public void resetAllHandlers() {
        requireCapability(Capability.HANDLER_LOWER);
        for (Map.Entry<HandlerRef, HandlerLevelOverride> entry : overrides.all().entrySet()) {
            applyReset(entry.getKey(), entry.getValue(), source);
        }
    }

    /**
     * Re-applies every currently-tracked handler override to {@code
     * targetAdapter} -- the {@link HandlerOverrideApplier} counterpart to
     * {@link LevelControlService#reapplyActiveOverrides}, called after a
     * WildFly reconfiguration (doc/specs/handler-floor-control.md
     * "Reconfiguration re-application" -- the ref is re-resolved by the
     * adapter itself, since the live handler instance may be new).
     */
    public void reapplyActiveOverrides(LoggingAdapter targetAdapter) {
        for (HandlerLevelOverride override : overrides.all().values()) {
            HandlerOverrideApplier.apply(override, targetAdapter);
        }
    }

    /**
     * The verification sweep — the {@link LevelControlService#verifyAndReapply}
     * counterpart for handlers, closing the gap doc/specs/
     * handler-floor-control.md's "Reconfiguration re-application" flagged: for
     * every active handler override, compare the adapter's current handler
     * level against the override's level, and re-apply where they disagree —
     * a WildFly {@code /subsystem=logging} change, an XML edit + {@code
     * :reload}, JBoss LogManager having no reconfiguration event of its own
     * (§4.3). Idempotent, and follows the same concurrency discipline: iterate
     * a snapshot of refs, re-read the registry entry per iteration, and
     * re-check it after applying so a concurrent reset or supersede is
     * honoured rather than clobbered. Expired {@code FOR} overrides are left
     * to {@link #sweepExpiredOverrides}. A handler that has vanished (adapter
     * throws resolving it) is dropped from tracking with a diagnostic, same
     * as {@link #sweepExpiredOverrides}'s failure-handling story.
     *
     * @return how many handler overrides had drifted and were re-applied
     */
    public int verifyAndReapply(Instant now) {
        int reapplied = 0;
        for (HandlerRef ref : List.copyOf(overrides.all().keySet())) {
            HandlerLevelOverride override = overrides.get(ref).orElse(null);
            if (override == null) {
                continue; // reset out from under this sweep between snapshot and now
            }
            if (override.tier() == PersistenceTier.FOR && !override.expiresAt().isAfter(now)) {
                continue; // expired -- the expiry sweep owns this one
            }

            Optional<Level> current;
            try {
                current = adapter.handlerLevel(ref);
            } catch (UnknownHandlerException e) {
                System.err.println("[logaperture-core] verification sweep: handler '" + ref
                        + "' no longer resolves, dropping it from tracking: " + e);
                overrides.removeIfCurrent(ref, override);
                continue;
            } catch (RuntimeException e) {
                System.err.println("[logaperture-core] verification sweep: failed to read handler '"
                        + ref + "', leaving it for the next tick: " + e);
                continue;
            }
            if (current.isPresent() && current.get() == override.level()) {
                continue; // still in force
            }

            try {
                HandlerOverrideApplier.apply(override, adapter);
            } catch (UnknownHandlerException e) {
                // The handler itself is gone (context torn down, config
                // dropped it) -- not a transient failure, so don't leave a
                // permanently-undead override that fails every future tick.
                System.err.println("[logaperture-core] verification sweep: handler '" + ref
                        + "' no longer resolves, dropping it from tracking: " + e);
                overrides.removeIfCurrent(ref, override);
                continue;
            } catch (RuntimeException e) {
                System.err.println("[logaperture-core] verification sweep: failed to re-apply handler '"
                        + ref + "', leaving it drifted for the next tick: " + e);
                continue;
            }

            Optional<HandlerLevelOverride> afterApply = overrides.get(ref);
            if (!afterApply.map(override::equals).orElse(false)) {
                // A concurrent resetHandler/setHandlerLevel won the race
                // between our read and our apply -- undo rather than leave
                // the adapter disagreeing with the registry, no audit for a
                // re-apply that did not stick.
                afterApply.ifPresentOrElse(
                        replacement -> HandlerOverrideApplier.apply(replacement, adapter),
                        () -> adapter.setHandlerLevel(ref, baselines.get(ref).orElse(null)));
                continue;
            }

            auditLog.record(new AuditRecord(now, principal, "verification-sweep", ref.value(),
                    current.map(Level::toString).orElse("<none>"), override.level().toString(), override.reason(),
                    AuditRecord.Action.MUTATION));
            reapplied++;
        }
        return reapplied;
    }

    /** Every handler override this context currently tracks. */
    @Override
    public List<HandlerLevelOverride> listHandlerOverrides() {
        return List.copyOf(overrides.all().values());
    }

    /**
     * Applies a handler override another context in the same aggregate
     * already holds, onto this context -- the {@link
     * LevelControlService#adoptOverride} counterpart for handlers. Unlike
     * the logger version, this context's adapter may simply not have this
     * handler at all (a per-app logging profile with a different handler
     * set, say) -- a failure here is caught and logged rather than thrown,
     * so it doesn't abort {@link AggregateLevelControl#addContext}'s
     * rebroadcast of every other still-live override onto the same new
     * context.
     */
    public void adoptOverride(HandlerLevelOverride override) {
        Objects.requireNonNull(override, "override");
        baselines.captureIfAbsent(override.handlerRef(), adapter);
        try {
            HandlerOverrideApplier.apply(override, adapter);
        } catch (RuntimeException e) {
            System.err.println("[logaperture-core] failed to adopt handler override for '"
                    + override.handlerRef() + "' onto this context, skipping it: " + e);
            return;
        }
        overrides.put(override);
        auditLog.record(new AuditRecord(
                Instant.now(), principal, "resume", override.handlerRef().value(), null,
                override.level().toString(), override.reason(), AuditRecord.Action.MUTATION));
    }

    /**
     * Runs once, at composition-root install time -- the {@link
     * LevelControlService#resumeFromStateStore} counterpart for handlers.
     *
     * @param now injected so tests can simulate "time has passed since the
     *            override was persisted" without a real sleep
     */
    public void resumeFromStateStore(Instant now) {
        for (HandlerLevelOverride persisted : stateStore.loadAllHandlers()) {
            try {
                resumeOne(persisted, now);
            } catch (RuntimeException e) {
                System.err.println("[logaperture-state] failed to resume persisted handler override for '"
                        + persisted.handlerRef() + "', skipping it: " + e);
            }
        }
    }

    private void resumeOne(HandlerLevelOverride persisted, Instant now) {
        baselines.captureIfAbsent(persisted.handlerRef(), adapter);

        if (persisted.tier() == PersistenceTier.FOR && !persisted.expiresAt().isAfter(now)) {
            recordReversionForNeverApplied(persisted, now);
            safePersist(() -> stateStore.removeHandler(persisted.handlerRef()));
            return;
        }

        HandlerOverrideApplier.apply(persisted, adapter);
        overrides.put(persisted);
        auditLog.record(new AuditRecord(
                now, principal, "resume", persisted.handlerRef().value(), null,
                persisted.level().toString(), persisted.reason(), AuditRecord.Action.MUTATION));
    }

    /**
     * Reverts every {@code FOR} handler override whose {@code expiresAt} is
     * at or before {@code now} -- the {@link
     * LevelControlService#sweepExpiredOverrides} counterpart for handlers.
     */
    public void sweepExpiredOverrides(Instant now) {
        for (HandlerRef ref : overrides.all().keySet()) {
            Consumer<HandlerLevelOverride> revertIfExpired = override -> {
                if (override.tier() == PersistenceTier.FOR && !override.expiresAt().isAfter(now)) {
                    applyReset(ref, override, "expiry-sweep");
                }
            };
            overrides.get(ref).ifPresent(revertIfExpired);
        }
    }

    /**
     * @return the capability the requested change needs in this context, or
     *         empty if the handler doesn't resolve here at all -- nothing to
     *         authorize when there is nothing to mutate (doc/specs/
     *         handler-floor-control.md "Multi-context (WildFly)"). Returning
     *         a direction guessed from {@code newLevel} itself in that case
     *         would silently demand the wrong capability for every other
     *         context in a broadcast's pre-check.
     */
    private Optional<Capability> requiredCapabilityFor(HandlerRef ref, Level newLevel) {
        Optional<Level> current = baselines.captureIfAbsent(ref, adapter);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(newLevel.isMoreVerboseThan(current.get()) ? Capability.HANDLER_LOWER : Capability.HANDLER_RAISE);
    }

    private HandlerLevelOverride applyAndRecordMutation(HandlerRef ref, Level level, SetHandlerLevelOptions opts) {
        baselines.captureIfAbsent(ref, adapter);
        String previousValue = adapter.handlerLevel(ref).map(Level::toString).orElse("<none>");

        Instant now = Instant.now();
        Instant expiresAt = opts.tier() == PersistenceTier.FOR ? now.plus(opts.expiresIn()) : null;
        HandlerLevelOverride override = new HandlerLevelOverride(
                ref, level, opts.reason(), now, source, opts.tier(), expiresAt);
        HandlerOverrideApplier.apply(override, adapter); // mutation: the point of no return

        overrides.put(override); // commit
        if (opts.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.saveHandler(override));
        } else {
            safePersist(() -> stateStore.removeHandler(ref));
        }
        auditLog.record(new AuditRecord(
                now, principal, source, ref.value(), previousValue, level.toString(), opts.reason(),
                AuditRecord.Action.MUTATION));

        return override;
    }

    private void applyReset(HandlerRef ref, HandlerLevelOverride toRevert, String auditSource) {
        if (!overrides.removeIfCurrent(ref, toRevert)) {
            return; // a concurrent setHandlerLevel already replaced it
        }

        String previousValue = toRevert.level().toString();
        Level baseline;
        try {
            // Always captured -- applyAndRecordMutation/adoptOverride/resumeOne
            // all capture a real baseline before an override is ever tracked,
            // so this should never actually be empty. Guarded anyway: a
            // handler that has since vanished, or any other adapter failure,
            // must not abort whatever else is being reset or swept alongside
            // it (doc/specs/handler-floor-control.md "Failure handling").
            baseline = baselines.get(ref).orElse(null);
            if (baseline != null) {
                adapter.setHandlerLevel(ref, baseline); // mutation
            }
        } catch (RuntimeException e) {
            System.err.println("[logaperture-core] failed to revert handler '" + ref
                    + "', dropping it from tracking without reverting it: " + e);
            safePersist(() -> stateStore.removeHandler(ref));
            return;
        }

        safePersist(() -> stateStore.removeHandler(ref));

        String newValue = baseline == null ? "<none>" : baseline.toString();
        auditLog.record(new AuditRecord(
                Instant.now(), principal, auditSource, ref.value(), previousValue, newValue, null,
                AuditRecord.Action.REVERSION));
    }

    private void safePersist(Runnable stateStoreCall) {
        try {
            stateStoreCall.run();
        } catch (RuntimeException e) {
            System.err.println("[logaperture-state] state store operation failed, continuing in-memory only: " + e);
        }
    }

    private void recordReversionForNeverApplied(HandlerLevelOverride persisted, Instant now) {
        Optional<Level> baseline = baselines.get(persisted.handlerRef());
        String newValue = baseline.map(Level::toString).orElse("<none>");
        auditLog.record(new AuditRecord(
                now, principal, "resume", persisted.handlerRef().value(), persisted.level().toString(), newValue,
                "expired while stopped", AuditRecord.Action.REVERSION));
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
