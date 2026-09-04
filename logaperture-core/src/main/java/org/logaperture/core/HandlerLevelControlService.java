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

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    public HandlerLevelOverride setHandlerLevel(HandlerRef ref, Level level, SetHandlerLevelOptions options) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(level, "level");
        SetHandlerLevelOptions opts = options == null ? SetHandlerLevelOptions.defaults() : options;

        checkSetHandlerLevelPermitted(ref, level, opts);
        return applyAndRecordMutation(ref, level, opts);
    }

    /**
     * Runs {@code setHandlerLevel}'s capability pre-flight without mutating
     * anything — the {@link LevelControlService#checkSetLevelPermitted}
     * counterpart for handlers. {@link AggregateLevelControl} calls this
     * against every context before broadcasting a {@code setHandlerLevel},
     * for the same "all pass or all fail" reason.
     */
    public void checkSetHandlerLevelPermitted(HandlerRef ref, Level level, SetHandlerLevelOptions options) {
        SetHandlerLevelOptions opts = options == null ? SetHandlerLevelOptions.defaults() : options;
        Capability required = requiredCapabilityFor(ref, level);
        if (!policy.isGranted(required)) {
            throw new CapabilityDeniedException(required);
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
        applyReset(ref, existing.get(), source, null);
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

    /** Every handler override this context currently tracks. */
    public List<HandlerLevelOverride> activeOverrides() {
        return List.copyOf(overrides.all().values());
    }

    /**
     * Applies a handler override another context in the same aggregate
     * already holds, onto this context -- the {@link
     * LevelControlService#adoptOverride} counterpart for handlers.
     */
    public void adoptOverride(HandlerLevelOverride override) {
        Objects.requireNonNull(override, "override");
        baselines.captureIfAbsent(override.handlerRef(), adapter);
        HandlerOverrideApplier.apply(override, adapter);
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
            overrides.get(ref).ifPresent(override -> {
                if (override.tier() == PersistenceTier.FOR && !override.expiresAt().isAfter(now)) {
                    applyReset(ref, override, "expiry-sweep", null);
                }
            });
        }
    }

    private Capability requiredCapabilityFor(HandlerRef ref, Level newLevel) {
        baselines.captureIfAbsent(ref, adapter);
        Level current = adapter.handlerLevel(ref).orElse(newLevel);
        return newLevel.isMoreVerboseThan(current) ? Capability.HANDLER_LOWER : Capability.HANDLER_RAISE;
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

    private void applyReset(HandlerRef ref, HandlerLevelOverride toRevert, String auditSource, String reasonOverride) {
        if (!overrides.removeIfCurrent(ref, toRevert)) {
            return; // a concurrent setHandlerLevel already replaced it
        }

        String previousValue = toRevert.level().toString();
        Optional<Level> baseline = baselines.get(ref); // always captured -- setHandlerLevel/resume guarantees it

        adapter.setHandlerLevel(ref, baseline.orElse(null)); // mutation

        safePersist(() -> stateStore.removeHandler(ref));

        String newValue = baseline.map(Level::toString).orElse("<none>");
        auditLog.record(new AuditRecord(
                Instant.now(), principal, auditSource, ref.value(), previousValue, newValue, reasonOverride,
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
