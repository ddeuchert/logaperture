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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        if (HandlerRef.ALL_HANDLERS.equals(ref)) {
            return Optional.of(applyAndRecordGroupMutation(level, opts));
        }
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
        if (HandlerRef.ALL_HANDLERS.equals(ref)) {
            // Decision #5 (issue #13): no invented aggregate "current level"
            // for the group -- direction is judged per real handler with the
            // exact single-ref logic below, and the union of whichever
            // capabilities the reals need must all be granted.
            for (HandlerRef real : adapter.realHandlers()) {
                requireCapabilityIfNeeded(real, level);
            }
        } else {
            requireCapabilityIfNeeded(ref, level);
        }
        if (opts.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }
    }

    /**
     * Empty means this context's adapter doesn't resolve {@code ref} at all
     * -- nothing to authorize here (the actual attempt will no-op/throw
     * {@code UnknownHandlerException} on its own, caught by the broadcast).
     */
    private void requireCapabilityIfNeeded(HandlerRef ref, Level level) {
        Optional<Capability> required = requiredCapabilityFor(ref, level);
        if (required.isPresent() && !policy.isGranted(required.get())) {
            throw new CapabilityDeniedException(required.get());
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

            String currentDescription;
            boolean drifted;
            Map<HandlerRef, String> groupPreviousValues = null;
            if (HandlerRef.ALL_HANDLERS.equals(ref)) {
                // Decision #1/#2 (issue #13): ALL_HANDLERS isn't itself a
                // live handler, so there is no single adapter.handlerLevel(ref)
                // to compare -- drift means *any* real handler disagreeing
                // with the tracked group level.
                DriftCheck check = checkGroupDrift(override.level());
                drifted = check.drifted();
                currentDescription = check.description();
                groupPreviousValues = check.perRealCurrent();
            } else {
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
                drifted = current.isEmpty() || current.get() != override.level();
                currentDescription = current.map(Level::toString).orElse("<none>");
            }
            if (!drifted) {
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
                if (afterApply.isPresent()) {
                    HandlerOverrideApplier.apply(afterApply.get(), adapter);
                } else if (HandlerRef.ALL_HANDLERS.equals(ref)) {
                    restoreGroupToBaselinesSilently();
                } else {
                    adapter.setHandlerLevel(ref, baselines.get(ref).orElse(null));
                }
                continue;
            }

            if (HandlerRef.ALL_HANDLERS.equals(ref)) {
                // Decision #3 (issue #13): the audit trail stays as granular
                // as ever, one row per real handler re-applied -- never a
                // single ALL_HANDLERS-keyed row standing in for the whole
                // group (code-review finding: this used to write exactly
                // one row here, unlike every other ALL_HANDLERS mutation
                // path in this class).
                for (Map.Entry<HandlerRef, String> entry : groupPreviousValues.entrySet()) {
                    auditLog.record(new AuditRecord(now, principal, "verification-sweep", entry.getKey().value(),
                            entry.getValue(), override.level().toString(), override.reason(),
                            AuditRecord.Action.MUTATION));
                }
            } else {
                auditLog.record(new AuditRecord(now, principal, "verification-sweep", ref.value(),
                        currentDescription, override.level().toString(), override.reason(),
                        AuditRecord.Action.MUTATION));
            }
            reapplied++;
        }
        return reapplied;
    }

    /** One iteration's read of "what does the adapter say right now, and does it disagree with the tracked override". */
    private record DriftCheck(boolean drifted, String description, Map<HandlerRef, String> perRealCurrent) {
    }

    /**
     * ALL_HANDLERS has no single current level to compare -- drifted if
     * *any* real handler disagrees with {@code overrideLevel}. {@code
     * description} joins every real handler's own current value for a
     * single-ref-style log line; {@code perRealCurrent} is the same data,
     * structured, so a successful re-apply can still audit one row per real
     * handler (Decision #3) instead of one row for the whole group (issue
     * #13, Decisions #1/#2).
     */
    private DriftCheck checkGroupDrift(Level overrideLevel) {
        Map<HandlerRef, String> perReal = new LinkedHashMap<>();
        List<String> currentValues = new ArrayList<>();
        boolean drifted = false;
        for (HandlerRef real : adapter.realHandlers()) {
            Optional<Level> current = adapter.handlerLevel(real);
            String value = current.map(Level::toString).orElse("<none>");
            perReal.put(real, value);
            currentValues.add(real.value() + "=" + value);
            if (current.isEmpty() || current.get() != overrideLevel) {
                drifted = true;
            }
        }
        return new DriftCheck(drifted, String.join(", ", currentValues), perReal);
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
        captureBaselineFor(override.handlerRef());
        boolean group = HandlerRef.ALL_HANDLERS.equals(override.handlerRef());
        List<HandlerRef> reals = group ? adapter.realHandlers() : null;
        List<String> previousValues = group ? previousValuesOf(reals) : null;
        try {
            HandlerOverrideApplier.apply(override, adapter);
        } catch (RuntimeException e) {
            System.err.println("[logaperture-core] failed to adopt handler override for '"
                    + override.handlerRef() + "' onto this context, skipping it: " + e);
            return;
        }
        overrides.put(override);
        if (group) {
            // Decision #3 (issue #13): one audit row per real handler, not
            // one for the group (code-review finding).
            recordGroupMutationAudit("resume", reals, previousValues, override.level(), override.reason(),
                    Instant.now());
        } else {
            auditLog.record(new AuditRecord(
                    Instant.now(), principal, "resume", override.handlerRef().value(), null,
                    override.level().toString(), override.reason(), AuditRecord.Action.MUTATION));
        }
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
        captureBaselineFor(persisted.handlerRef());

        if (persisted.tier() == PersistenceTier.FOR && !persisted.expiresAt().isAfter(now)) {
            recordReversionForNeverApplied(persisted, now);
            safePersist(() -> stateStore.removeHandler(persisted.handlerRef()));
            return;
        }

        if (HandlerRef.ALL_HANDLERS.equals(persisted.handlerRef())) {
            List<HandlerRef> reals = adapter.realHandlers();
            List<String> previousValues = previousValuesOf(reals);
            HandlerOverrideApplier.apply(persisted, adapter);
            overrides.put(persisted);
            // Decision #3 (issue #13): one audit row per real handler, not
            // one for the group (code-review finding).
            recordGroupMutationAudit("resume", reals, previousValues, persisted.level(), persisted.reason(), now);
            return;
        }

        HandlerOverrideApplier.apply(persisted, adapter);
        overrides.put(persisted);
        auditLog.record(new AuditRecord(
                now, principal, "resume", persisted.handlerRef().value(), null,
                persisted.level().toString(), persisted.reason(), AuditRecord.Action.MUTATION));
    }

    /** Every real handler's own current level, right now, in the same order as {@code reals}. */
    private List<String> previousValuesOf(List<HandlerRef> reals) {
        List<String> values = new ArrayList<>(reals.size());
        for (HandlerRef real : reals) {
            values.add(adapter.handlerLevel(real).map(Level::toString).orElse("<none>"));
        }
        return values;
    }

    /**
     * Writes one MUTATION audit row per real handler for an ALL_HANDLERS
     * group override that was just applied elsewhere (resume, adopt) --
     * {@code reals}/{@code previousValues} are the pre-mutation snapshot
     * taken before {@link HandlerOverrideApplier#apply} ran, paired by
     * index (issue #13, Decision #3).
     */
    private void recordGroupMutationAudit(String auditSource, List<HandlerRef> reals, List<String> previousValues,
            Level newLevel, String reason, Instant now) {
        for (int i = 0; i < reals.size(); i++) {
            auditLog.record(new AuditRecord(now, principal, auditSource, reals.get(i).value(),
                    previousValues.get(i), newLevel.toString(), reason, AuditRecord.Action.MUTATION));
        }
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

    /**
     * Captures the baseline(s) an override for {@code ref} needs before it's
     * tracked. For {@link HandlerRef#ALL_HANDLERS} (issue #13) that means
     * every real handler in {@code adapter.realHandlers()} -- the group ref
     * itself never resolves to a live handler, so capturing a baseline
     * "for" it directly would capture nothing and leave every real handler
     * unable to be reverted later ({@link #applyGroupReset} needs each
     * real's own captured value).
     */
    private void captureBaselineFor(HandlerRef ref) {
        if (HandlerRef.ALL_HANDLERS.equals(ref)) {
            for (HandlerRef real : adapter.realHandlers()) {
                baselines.captureIfAbsent(real, adapter);
            }
        } else {
            baselines.captureIfAbsent(ref, adapter);
        }
    }

    /**
     * ALL_HANDLERS fan-out (issue #13, Decision #2): applies {@code level}
     * to every real handler in {@code adapter.realHandlers()}, reusing the
     * same baseline-capture/apply/audit steps {@link #applyAndRecordMutation}
     * uses for a single ref, once per real handler -- then tracks the whole
     * group as ONE {@link HandlerLevelOverride} keyed by the reserved
     * ALL_HANDLERS ref (Decision #3): {@code logctl status}, resume, and the
     * expiry sweep all key off that single entry, while the audit trail
     * still gets one record per real handler actually mutated, named by its
     * own real ref. A real handler whose mutation throws is logged and
     * skipped, same "one bad target doesn't abort the rest" discipline
     * {@link AggregateLevelControl#setHandlerLevel} uses across contexts --
     * applied here across the real handlers within one context instead.
     */
    private HandlerLevelOverride applyAndRecordGroupMutation(Level level, SetHandlerLevelOptions opts) {
        Instant now = Instant.now();
        int mutated = 0;
        List<HandlerRef> reals = adapter.realHandlers();
        for (HandlerRef real : reals) {
            baselines.captureIfAbsent(real, adapter);
            String previousValue = adapter.handlerLevel(real).map(Level::toString).orElse("<none>");
            try {
                adapter.setHandlerLevel(real, level); // mutation
            } catch (RuntimeException e) {
                System.err.println("[logaperture-core] ALL_HANDLERS: failed to set handler '" + real
                        + "', leaving it unchanged: " + e);
                continue;
            }
            mutated++;
            // An individually-tracked override on this same real handler
            // (plain JUL, where a real ref stays addressable alongside
            // ALL_HANDLERS) is now stale: the group mutation just applied a
            // new value directly to it, and the row below already audits
            // that change. Drop it so `logctl status` doesn't show two
            // overrides disagreeing about the same handler's level
            // (code-review finding). The reverse ordering -- an individual
            // override set *after* ALL_HANDLERS, deliberately peeling one
            // handler off the group -- is left alone; that override still
            // wins and this class does not currently guard the group's own
            // reset/reapply from also touching that handler.
            if (overrides.get(real).isPresent()) {
                overrides.remove(real);
                safePersist(() -> stateStore.removeHandler(real));
            }
            auditLog.record(new AuditRecord(
                    now, principal, source, real.value(), previousValue, level.toString(), opts.reason(),
                    AuditRecord.Action.MUTATION));
        }
        if (mutated == 0 && !reals.isEmpty()) {
            throw new IllegalStateException("setHandlerLevel(ALL_HANDLERS) failed for every real handler");
        }

        Instant expiresAt = opts.tier() == PersistenceTier.FOR ? now.plus(opts.expiresIn()) : null;
        HandlerLevelOverride override = new HandlerLevelOverride(
                HandlerRef.ALL_HANDLERS, level, opts.reason(), now, source, opts.tier(), expiresAt);
        overrides.put(override); // one tracked entry for the whole group
        if (opts.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.saveHandler(override));
        } else {
            safePersist(() -> stateStore.removeHandler(HandlerRef.ALL_HANDLERS));
        }
        return override;
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

        if (HandlerRef.ALL_HANDLERS.equals(ref)) {
            applyGroupReset(auditSource);
            safePersist(() -> stateStore.removeHandler(ref));
            return;
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

    /**
     * ALL_HANDLERS reset (issue #13, Decision #4): reverts every real
     * handler to its OWN captured baseline, never to the group override's
     * single {@code level} field -- if CONSOLE started at INFO and FILE at
     * DEBUG, both go back to their own prior value. One REVERSION audit
     * record per real handler actually reverted, symmetric with the N
     * mutation records {@link #applyAndRecordGroupMutation} wrote (Decision
     * #3). A real handler that has since vanished, or any other adapter
     * failure, is logged and skipped rather than aborting the rest of the
     * group (doc/specs/handler-floor-control.md "Failure handling").
     */
    private void applyGroupReset(String auditSource) {
        Instant now = Instant.now();
        for (HandlerRef real : adapter.realHandlers()) {
            if (!baselines.isCaptured(real)) {
                continue; // never touched by the group mutation -- nothing to revert
            }
            Level baseline = baselines.get(real).orElse(null);
            String previousValue = adapter.handlerLevel(real).map(Level::toString).orElse("<none>");
            if (!trySetHandlerLevel(real, baseline, "revert")) {
                continue;
            }
            String newValue = baseline == null ? "<none>" : baseline.toString();
            auditLog.record(new AuditRecord(
                    now, principal, auditSource, real.value(), previousValue, newValue, null,
                    AuditRecord.Action.REVERSION));
        }
    }

    /**
     * Undoes a verification-sweep re-apply of an ALL_HANDLERS override that
     * lost a race with a concurrent reset -- every real handler with a
     * captured baseline is silently set back to it, no audit record, same
     * as the single-ref undo in {@link #verifyAndReapply} ("no audit for a
     * re-apply that did not stick").
     */
    private void restoreGroupToBaselinesSilently() {
        for (HandlerRef real : adapter.realHandlers()) {
            if (baselines.isCaptured(real)) {
                trySetHandlerLevel(real, baselines.get(real).orElse(null), "undo");
            }
        }
    }

    /** @return {@code true} unless the adapter call threw (nothing to set counts as success -- there was no baseline to restore) */
    private boolean trySetHandlerLevel(HandlerRef ref, Level level, String action) {
        try {
            if (level != null) {
                adapter.setHandlerLevel(ref, level);
            }
            return true;
        } catch (RuntimeException e) {
            System.err.println("[logaperture-core] ALL_HANDLERS: failed to " + action + " handler '" + ref
                    + "', leaving it unchanged: " + e);
            return false;
        }
    }

    private void safePersist(Runnable stateStoreCall) {
        try {
            stateStoreCall.run();
        } catch (RuntimeException e) {
            System.err.println("[logaperture-state] state store operation failed, continuing in-memory only: " + e);
        }
    }

    private void recordReversionForNeverApplied(HandlerLevelOverride persisted, Instant now) {
        if (HandlerRef.ALL_HANDLERS.equals(persisted.handlerRef())) {
            // One record per real handler, symmetric with every other
            // ALL_HANDLERS audit trail (Decision #3) -- captureBaselineFor
            // already populated a baseline for each real handler, above.
            for (HandlerRef real : adapter.realHandlers()) {
                String newValue = baselines.isCaptured(real)
                        ? baselines.get(real).map(Level::toString).orElse("<none>")
                        : "<none>";
                auditLog.record(new AuditRecord(
                        now, principal, "resume", real.value(), persisted.level().toString(), newValue,
                        "expired while stopped", AuditRecord.Action.REVERSION));
            }
            return;
        }
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
