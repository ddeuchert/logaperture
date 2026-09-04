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
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The level-control engine — framework- and agent-agnostic, per
 * doc/specs/level-control.md and doc/specs/persistence.md. Every mutating
 * method follows the same ordering rule: <b>capability check &rarr; adapter
 * mutation &rarr; registry commit &rarr; state-store write &rarr; audit
 * record</b>, so a failure at any step leaves no partial registry/audit/
 * state-store state for the logger in question.
 */
public final class LevelControlService implements LevelControlOperations {

    private final LoggingAdapter adapter;
    private final BaselineRegistry baselines;
    private final OverrideRegistry overrides;
    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final StateStore stateStore;
    private final String principal;
    private final String source;

    public LevelControlService(
            LoggingAdapter adapter,
            BaselineRegistry baselines,
            OverrideRegistry overrides,
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
    public List<LoggerInfo> listLoggers(String filter) {
        requireCapability(Capability.VIEW);

        TreeSet<String> names = new TreeSet<>(adapter.knownLoggerNames());
        names.addAll(overrides.all().keySet());

        List<LoggerInfo> result = new ArrayList<>();
        for (String name : names) {
            if (!NameFilter.matches(filter, name)) {
                continue;
            }
            Optional<Level> configured = baselines.captureIfAbsent(name, adapter);
            Level effective = adapter.effectiveLevel(name);
            Optional<LevelOverride> override = overrides.get(name);
            result.add(new LoggerInfo(
                    name,
                    configured.orElse(null),
                    effective,
                    override.isPresent(),
                    override.map(LevelOverride::source).orElse(null),
                    override.map(LevelOverride::reason).orElse(null),
                    override.map(LevelOverride::tier).orElse(null),
                    override.map(LevelOverride::expiresAt).orElse(null)));
        }
        return List.copyOf(result);
    }

    @Override
    public SetLevelResult setLevel(String loggerName, Level level, SetLevelOptions options) {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;

        List<String> targets = targetsFor(loggerName, opts);
        checkSetLevelPermitted(targets, level, opts);

        baselines.captureIfAbsent(loggerName, adapter);
        Level previousEffective = adapter.effectiveLevel(loggerName);

        LevelOverride primary = null;
        for (String target : targets) {
            LevelOverride applied = applyAndRecordMutation(target, level, opts);
            if (target.equals(loggerName)) {
                primary = applied;
            }
        }

        // Actionable warning (doc/specs/handler-floor-control.md "Warning on
        // level commands"): only for a genuine raise on the named logger --
        // matches the JBoss LogManager adapter's own former guard, now done
        // once here so every adapter gets it via the SPI default.
        List<HandlerFloor> blockingHandlers = level.isMoreVerboseThan(previousEffective)
                ? adapter.handlerFloorsBelow(loggerName, level)
                : List.of();
        return new SetLevelResult(primary, blockingHandlers);
    }

    /**
     * Runs {@code setLevel}'s capability pre-flight without mutating anything —
     * throws {@link CapabilityDeniedException} exactly where {@code setLevel}
     * would. {@link AggregateLevelControl} calls this against <em>every</em>
     * context before broadcasting a {@code setLevel}, so a denial in any one
     * context fails the whole broadcast before any context is mutated
     * (doc/specs/wildfly-support.md, "all pass or all fail").
     */
    public void checkSetLevelPermitted(String loggerName, Level level, SetLevelOptions options) {
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;
        checkSetLevelPermitted(targetsFor(loggerName, opts), level, opts);
    }

    private List<String> targetsFor(String loggerName, SetLevelOptions opts) {
        List<String> targets = new ArrayList<>();
        targets.add(loggerName);
        if (opts.includeChildren()) {
            targets.addAll(LoggerHierarchy.descendantsOf(loggerName, adapter.knownLoggerNames()));
        }
        return targets;
    }

    private void checkSetLevelPermitted(List<String> targets, Level level, SetLevelOptions opts) {
        // Pre-check capability for every target before mutating any of
        // them (see the implementation plan's design call #5): removes
        // "partially applied because a capability was denied N loggers in"
        // as a failure mode. Does not eliminate a mid-fan-out adapter-level
        // exception's partial effect -- the adapter has no rollback
        // primitive, so that residual case is intentional, not hidden.
        for (String target : targets) {
            Capability required = requiredCapabilityFor(target, level);
            if (!policy.isGranted(required)) {
                throw new CapabilityDeniedException(required);
            }
            // persist is required in addition to raise/lower whenever the
            // change is meant to outlive this session (doc/specs/persistence.md
            // "Capability and audit") -- checked at the same pre-flight point,
            // so a denial fails the whole call before any target is mutated.
            if (opts.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
                throw new CapabilityDeniedException(Capability.PERSIST);
            }
        }
    }

    @Override
    public void resetLevel(String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName");
        Optional<LevelOverride> existing = overrides.get(loggerName);
        if (existing.isEmpty()) {
            return; // no-op, not an error -- per spec
        }
        // Simplification for this slice: every reset requires LEVEL_LOWER,
        // regardless of whether reverting to baseline happens to raise or
        // lower the effective level for this particular logger. resetAll's
        // "get me back to normal" framing is the dominant use case; the
        // capability-direction nuance for a reset that's actually a raise
        // (reverting a manual silence) is a known, documented gap -- not
        // resolved by the spec, not addressed here.
        requireCapability(Capability.LEVEL_LOWER);
        applyReset(loggerName, existing.get(), source, null);
    }

    @Override
    public void resetAll() {
        requireCapability(Capability.LEVEL_LOWER);
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            applyReset(entry.getKey(), entry.getValue(), source, null);
        }
    }

    /**
     * Re-applies every currently-tracked override to {@code adapter} --
     * called from the composition root's reconfiguration-reset callback
     * (doc/specs/persistence.md "Reconfiguration re-application") once a
     * real reset event exists to drive it; also exercised directly by
     * tests, per doc/specs/level-control.md's re-appliability note.
     */
    public void reapplyActiveOverrides(LoggingAdapter targetAdapter) {
        for (LevelOverride override : overrides.all().values()) {
            OverrideApplier.apply(override, targetAdapter);
        }
    }

    /**
     * The verification sweep (doc/specs/wildfly-support.md, §15.5; §15.5's
     * "re-establish installed state from an event <em>or</em> a periodic
     * sweep"): for every active override, compare the adapter's current
     * {@code effectiveLevel} against the override's level. Where they
     * disagree, something reconfigured the logger out from under us — a
     * WildFly {@code /subsystem=logging} change, an XML edit + {@code
     * :reload}, JBoss LogManager having no reconfiguration event of its own
     * (§4.3) — so re-apply it and record a {@code "verification-sweep"}
     * mutation. Idempotent: an already-correct override is skipped, so a
     * quiet system produces no re-applies and no audit noise. Expired {@code
     * FOR} overrides are left to {@link #sweepExpiredOverrides}.
     *
     * <p>Concurrency: this runs on the composition root's sweep thread while
     * {@code setLevel}/{@code resetLevel} run on a control-plane thread. It
     * follows {@link #sweepExpiredOverrides}'s discipline — iterate a snapshot
     * of <em>names</em>, re-read the registry entry per iteration, and (here)
     * re-check the entry <em>after</em> applying — so a concurrent reset that
     * removed the override cannot be "resurrected" by a stale snapshot value,
     * and a concurrent {@code setLevel} that replaced it is honoured rather
     * than shadowed.
     *
     * @return how many overrides had drifted and were re-applied
     */
    public int verifyAndReapply(Instant now) {
        int reapplied = 0;
        for (String loggerName : List.copyOf(overrides.all().keySet())) {
            LevelOverride override = overrides.get(loggerName).orElse(null);
            if (override == null) {
                continue; // reset out from under this sweep between snapshot and now
            }
            if (override.tier() == PersistenceTier.FOR && !override.expiresAt().isAfter(now)) {
                continue; // expired -- the expiry sweep owns this one
            }
            Level current = adapter.effectiveLevel(loggerName);
            if (current == override.level()) {
                continue; // still in force
            }

            OverrideApplier.apply(override, adapter);

            Optional<LevelOverride> afterApply = overrides.get(loggerName);
            if (!afterApply.map(override::equals).orElse(false)) {
                // A concurrent resetLevel/setLevel won the race between our
                // read and our apply. Undo what we just did rather than leave
                // the adapter disagreeing with the registry, and record no
                // audit for a re-apply that did not stick.
                afterApply.ifPresentOrElse(
                        replacement -> OverrideApplier.apply(replacement, adapter),
                        () -> adapter.applyLevel(loggerName, baselines.get(loggerName).orElse(null)));
                continue;
            }

            auditLog.record(new AuditRecord(
                    now, principal, "verification-sweep", loggerName, current.toString(),
                    override.level().toString(), override.reason(), AuditRecord.Action.MUTATION));
            reapplied++;
        }
        return reapplied;
    }

    /**
     * Every override this context currently tracks — used by {@link
     * AggregateLevelControl} to re-broadcast the active set onto a
     * context that registered after they were applied
     * (doc/specs/wildfly-support.md, "The redeploy loop").
     */
    public List<LevelOverride> activeOverrides() {
        return List.copyOf(overrides.all().values());
    }

    /**
     * Applies an override that another context in the same aggregate
     * already holds, onto this context — the multi-context broadcast /
     * redeploy re-application path (doc/specs/wildfly-support.md). Adapter
     * mutation, registry commit, and a {@code "resume"} audit record; no
     * capability check (this reinstates state an already-authorized action
     * established) and no state-store write (the originating context
     * already persisted it to the shared store).
     */
    public void adoptOverride(LevelOverride override) {
        Objects.requireNonNull(override, "override");
        baselines.captureIfAbsent(override.loggerName(), adapter);
        String previousValue = adapter.effectiveLevel(override.loggerName()).toString();
        OverrideApplier.apply(override, adapter);
        overrides.put(override);
        auditLog.record(new AuditRecord(
                Instant.now(), principal, "resume", override.loggerName(), previousValue,
                override.level().toString(), override.reason(), AuditRecord.Action.MUTATION));
    }

    /**
     * Runs once, at composition-root install time, after baseline capture
     * and before this service is handed back to its caller (doc/specs/
     * persistence.md "Resume on restart"). Bypasses capability checks
     * deliberately -- this reinstates state a previous, already-authorized
     * session persisted; it is not a new operator action.
     *
     * @param now injected so tests can simulate "time has passed since the
     *            override was persisted" without a real sleep
     */
    public void resumeFromStateStore(Instant now) {
        for (LevelOverride persisted : stateStore.loadAll()) {
            try {
                resumeOne(persisted, now);
            } catch (RuntimeException e) {
                // One bad persisted entry must not take the whole install
                // down with it (§9's fail-open discipline) -- skip it and
                // keep resuming the rest.
                System.err.println("[logaperture-state] failed to resume persisted override for '"
                        + persisted.loggerName() + "', skipping it: " + e);
            }
        }
    }

    private void resumeOne(LevelOverride persisted, Instant now) {
        baselines.captureIfAbsent(persisted.loggerName(), adapter);

        if (persisted.tier() == PersistenceTier.FOR && !persisted.expiresAt().isAfter(now)) {
            // Expired while this JVM was down -- never (re-)applied this
            // session, but still recorded as a reversion and dropped
            // from the store, so the audit trail doesn't have a silent
            // gap where the override simply stops existing.
            recordReversionForNeverApplied(persisted, now);
            safePersist(() -> stateStore.remove(persisted.loggerName()));
            return;
        }

        String previousValue = adapter.effectiveLevel(persisted.loggerName()).toString();
        OverrideApplier.apply(persisted, adapter);
        overrides.put(persisted);
        auditLog.record(new AuditRecord(
                now, principal, "resume", persisted.loggerName(), previousValue,
                persisted.level().toString(), persisted.reason(), AuditRecord.Action.MUTATION));
    }

    /**
     * Reverts every {@code FOR} override whose {@code expiresAt} is at or
     * before {@code now}, and drops it from {@code stateStore} so it
     * doesn't reappear on a later resume (doc/specs/persistence.md "Expiry
     * enforcement"). Owned and scheduled by the composition root, not by
     * this class -- {@code core} has no opinion about *when* this runs,
     * only what running it does.
     */
    public void sweepExpiredOverrides(Instant now) {
        // Iterate a snapshot of names, but re-read each one's CURRENT value
        // right before deciding to revert it -- the snapshot can be stale
        // by the time this loop reaches an entry (a concurrent setLevel may
        // have already replaced it), and applyReset's compare-and-remove
        // uses this same fresh value, not the (possibly stale) one below.
        for (String loggerName : overrides.all().keySet()) {
            overrides.get(loggerName).ifPresent(override -> {
                if (override.tier() == PersistenceTier.FOR && !override.expiresAt().isAfter(now)) {
                    applyReset(loggerName, override, "expiry-sweep", null);
                }
            });
        }
    }

    private Capability requiredCapabilityFor(String loggerName, Level newLevel) {
        baselines.captureIfAbsent(loggerName, adapter);
        Level current = adapter.effectiveLevel(loggerName);
        return newLevel.isMoreVerboseThan(current) ? Capability.LEVEL_RAISE : Capability.LEVEL_LOWER;
    }

    private LevelOverride applyAndRecordMutation(String loggerName, Level level, SetLevelOptions opts) {
        baselines.captureIfAbsent(loggerName, adapter);
        String previousValue = adapter.effectiveLevel(loggerName).toString();

        Instant now = Instant.now();
        Instant expiresAt = opts.tier() == PersistenceTier.FOR ? now.plus(opts.expiresIn()) : null;
        LevelOverride override = new LevelOverride(
                loggerName, level, opts.includeChildren(), opts.reason(), now, source, opts.tier(), expiresAt);
        OverrideApplier.apply(override, adapter); // mutation: the point of no return

        overrides.put(override); // commit
        // state-store write -- SESSION also removes, in case this logger
        // had a previously-persisted FOR/STICKY entry that this plain
        // mutation now supersedes; leaving it would reappear on resume.
        if (opts.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.save(override));
        } else {
            safePersist(() -> stateStore.remove(loggerName));
        }
        auditLog.record(new AuditRecord(
                now, principal, source, loggerName, previousValue, level.toString(), opts.reason(),
                AuditRecord.Action.MUTATION)); // audit

        return override;
    }

    private void applyReset(String loggerName, LevelOverride toRevert, String auditSource, String reasonOverride) {
        // Atomic compare-and-remove first: if the registry's current entry
        // for this logger is no longer exactly `toRevert`, a concurrent
        // setLevel already replaced it (the expiry sweep's own race, per
        // doc/specs/persistence.md's review) -- bail out without touching
        // the adapter, so the newer override is never clobbered.
        if (!overrides.removeIfCurrent(loggerName, toRevert)) {
            return;
        }

        String previousValue = toRevert.level().toString();
        Optional<Level> baseline = baselines.get(loggerName); // always captured -- setLevel/resume guarantees it

        adapter.applyLevel(loggerName, baseline.orElse(null)); // mutation

        // state-store write -- no-op if this override was never persisted
        safePersist(() -> stateStore.remove(loggerName));

        String newValue = baseline.map(Level::toString).orElse("<inherited>");
        auditLog.record(new AuditRecord(
                Instant.now(), principal, auditSource, loggerName, previousValue, newValue, reasonOverride,
                AuditRecord.Action.REVERSION)); // audit
    }

    /**
     * Guards a {@link StateStore} call against a misbehaving implementation
     * -- {@link FileStateStore} itself never throws (it catches and logs
     * its own I/O failures, per doc/specs/persistence.md "Failure
     * handling"), but a {@code StateStore} that does throw must still leave
     * level control fully functional in-memory for the session, not
     * propagate and abort an otherwise-successful mutation.
     */
    private void safePersist(Runnable stateStoreCall) {
        try {
            stateStoreCall.run();
        } catch (RuntimeException e) {
            System.err.println("[logaperture-state] state store operation failed, continuing in-memory only: " + e);
        }
    }

    /** A {@code FOR} override that expired while this JVM was stopped -- never reapplied, only recorded. */
    private void recordReversionForNeverApplied(LevelOverride persisted, Instant now) {
        Optional<Level> baseline = baselines.get(persisted.loggerName());
        String newValue = baseline.map(Level::toString).orElse("<inherited>");
        auditLog.record(new AuditRecord(
                now, principal, "resume", persisted.loggerName(), persisted.level().toString(), newValue,
                "expired while stopped", AuditRecord.Action.REVERSION));
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
