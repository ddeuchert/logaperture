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
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

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
    private final LoggerOverrideChangeListener changeListener;

    /** Convenience overload for every context that doesn't need to react to logger-override changes (doc/specs/handler-floor-control.md "AUTO handler level"). */
    public LevelControlService(
            LoggingAdapter adapter,
            BaselineRegistry baselines,
            OverrideRegistry overrides,
            CapabilityPolicy policy,
            AuditLog auditLog,
            StateStore stateStore,
            String principal,
            String source) {
        this(adapter, baselines, overrides, policy, auditLog, stateStore, principal, source,
                LoggerOverrideChangeListener.NONE);
    }

    public LevelControlService(
            LoggingAdapter adapter,
            BaselineRegistry baselines,
            OverrideRegistry overrides,
            CapabilityPolicy policy,
            AuditLog auditLog,
            StateStore stateStore,
            String principal,
            String source,
            LoggerOverrideChangeListener changeListener) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.baselines = Objects.requireNonNull(baselines, "baselines");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.source = Objects.requireNonNull(source, "source");
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    @Override
    public List<LoggerInfo> listLoggers(String filter) {
        requireCapability(Capability.VIEW);

        // Validated and compiled once, up front -- so an invalid filter is
        // rejected even when there happen to be zero candidate names below,
        // and a valid one isn't re-parsed/re-compiled per name in the loop.
        Predicate<String> matchesFilter = NameFilter.compile(filter);

        TreeSet<String> names = new TreeSet<>(adapter.knownLoggerNames());
        names.addAll(overrides.all().keySet());
        names.addAll(baselines.vendorLoggerNames());

        List<LoggerInfo> result = new ArrayList<>();
        for (String name : names) {
            if (!matchesFilter.test(name)) {
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
                    override.map(LevelOverride::expiresAt).orElse(null),
                    null,
                    baselines.vendorLevel(name).orElse(null)));
        }
        return List.copyOf(result);
    }

    @Override
    public SetLevelResult setLogger(String target, Level level, SetLevelOptions options) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(level, "level");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;

        rejectIfTrailingWildcard(target, level);
        if (NameFilter.isPattern(target)) {
            return setLevelForPattern(target, level, opts);
        }
        return setLevelForExactName(target, level, opts);
    }

    /**
     * {@link AggregateLevelControl}'s broadcast entry point once it has
     * already resolved {@code target} and capability-checked the result via
     * {@link #checkSetLevelPermittedAndResolve} for its own "all pass or all
     * fail" pre-flight across every context -- applies {@code
     * resolvedMatches} directly for a pattern target instead of re-resolving
     * it (a code-review finding). {@code resolvedMatches} is meaningless for
     * an exact-name target (it always mutates just {@code target} itself,
     * same as the two-arg overload) and is ignored in that case.
     */
    SetLevelResult setLogger(String target, Level level, SetLevelOptions options, List<String> resolvedMatches) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(level, "level");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;

        rejectIfTrailingWildcard(target, level);
        if (NameFilter.isPattern(target)) {
            return applyToMatches(resolvedMatches, level, opts);
        }
        return setLevelForExactName(target, level, opts);
    }

    private SetLevelResult setLevelForExactName(String loggerName, Level level, SetLevelOptions opts) {
        checkSetLevelPermitted(List.of(loggerName), level, opts);

        baselines.captureIfAbsent(loggerName, adapter);
        Level previousEffective = adapter.effectiveLevel(loggerName);

        LevelOverride override = applyAndRecordMutation(loggerName, level, opts);

        // AUTO handler recompute (doc/specs/handler-floor-control.md "AUTO
        // handler level", "Recompute trigger") -- must run before the
        // blocking-handler floor check below, so an AUTO handler that just
        // tracked down to this new level doesn't show up in that answer.
        changeListener.onChange();

        List<HandlerFloor> blocking = level.isMoreVerboseThan(previousEffective)
                ? adapter.handlerFloorsBelow(loggerName, level)
                : List.of();
        return new SetLevelResult(List.of(override), blocking);
    }

    /**
     * The pattern-selection apply path (doc/specs/
     * pattern-selection-semantics.md "Operations") -- a one-time selection,
     * resolved and applied in the same call, nothing left standing
     * afterward. Only reachable for a target with a leading star and no
     * trailing star: {@link #setLogger} rejects a trailing-star target
     * before this is ever called.
     */
    private SetLevelResult setLevelForPattern(String pattern, Level level, SetLevelOptions opts) {
        List<String> matches = resolveConfirmedMatches(pattern, opts);

        // No precedence filtering (doc/specs/pattern-selection-semantics.md
        // "Precedence, retired"): every match is a candidate, full stop --
        // a pattern-based set overwrites whatever a match already carries,
        // exactly as re-running an exact-name set against it would.
        checkSetLevelPermitted(matches, level, opts);
        return applyToMatches(matches, level, opts);
    }

    /**
     * The part of the pattern apply path that comes after matches are
     * resolved and capability-checked -- pulled out so {@link
     * AggregateLevelControl}'s broadcast, which already resolved and
     * capability-checked this same match list once via {@link
     * #checkSetLevelPermittedAndResolve}, can apply it directly instead of
     * paying for a second, independent resolution of the same pattern (a
     * code-review finding: the match set used to be resolved twice per
     * context for one logical broadcast -- once in the pre-flight check,
     * once again in this call).
     */
    private SetLevelResult applyToMatches(List<String> matches, Level level, SetLevelOptions opts) {
        Map<String, Level> previousEffectiveByTarget = new LinkedHashMap<>();
        for (String name : matches) {
            baselines.captureIfAbsent(name, adapter);
            previousEffectiveByTarget.put(name, adapter.effectiveLevel(name));
        }

        List<LevelOverride> created = new ArrayList<>();
        for (String name : matches) {
            created.add(applyAndRecordMutation(name, level, opts));
        }

        if (!created.isEmpty()) {
            changeListener.onChange();
        }

        Map<HandlerRef, HandlerFloor> blockingByRef = new LinkedHashMap<>();
        for (String name : matches) {
            if (level.isMoreVerboseThan(previousEffectiveByTarget.get(name))) {
                for (HandlerFloor floor : adapter.handlerFloorsBelow(name, level)) {
                    blockingByRef.merge(floor.handlerRef(), floor, LevelControlService::stricterFloor);
                }
            }
        }
        return new SetLevelResult(created, List.copyOf(blockingByRef.values()));
    }

    /**
     * Validates and resolves {@code pattern}'s current matches, then throws
     * {@link ConfirmationRequiredException} if {@code opts.confirmed()} is
     * {@code false} -- shared by the real apply path and {@link
     * #checkSetLevelPermitted(String, Level, SetLevelOptions)}'s pre-flight,
     * so both refuse an unconfirmed pattern the same way (doc/specs/
     * pattern-selection-semantics.md, Decision #1).
     */
    private List<String> resolveConfirmedMatches(String pattern, SetLevelOptions opts) {
        List<String> matches = matchesFor(pattern);
        if (!opts.confirmed()) {
            throw new ConfirmationRequiredException(pattern, matches);
        }
        return matches;
    }

    /** Every currently-known logger name {@code pattern} matches -- validates the grammar as a side effect. */
    private List<String> matchesFor(String pattern) {
        Predicate<String> matcher = NameFilter.compile(pattern);
        TreeSet<String> knownNames = new TreeSet<>(adapter.knownLoggerNames());
        knownNames.addAll(overrides.all().keySet());
        List<String> matches = new ArrayList<>();
        for (String name : knownNames) {
            if (matcher.test(name)) {
                matches.add(name);
            }
        }
        return matches;
    }

    /**
     * Runs {@code setLogger}'s capability pre-flight without mutating anything —
     * throws {@link CapabilityDeniedException} (or, for a pattern target,
     * {@link ConfirmationRequiredException}, or {@link IllegalArgumentException}
     * for a trailing-star target) exactly where {@code setLogger} would.
     * {@link AggregateLevelControl} calls this against <em>every</em>
     * context before broadcasting a {@code setLogger}, so a denial in any one
     * context fails the whole broadcast before any context is mutated
     * (doc/specs/wildfly-support.md, "all pass or all fail").
     */
    public void checkSetLevelPermitted(String target, Level level, SetLevelOptions options) {
        checkSetLevelPermittedAndResolve(target, level, options);
    }

    /**
     * {@link #checkSetLevelPermitted(String, Level, SetLevelOptions)}, plus
     * the resolved match list it already had to compute internally --
     * {@link AggregateLevelControl}'s broadcast keeps this result and hands
     * it to {@link #setLogger(String, Level, SetLevelOptions, List)} instead
     * of discarding it and re-resolving the same pattern a second time (a
     * code-review finding).
     */
    List<String> checkSetLevelPermittedAndResolve(String target, Level level, SetLevelOptions options) {
        Objects.requireNonNull(target, "target");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;
        rejectIfTrailingWildcard(target, level);
        List<String> targets = NameFilter.isPattern(target)
                ? resolveConfirmedMatches(target, opts)
                : List.of(target);
        checkSetLevelPermitted(targets, level, opts);
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
        }
        // persist is required in addition to raise/lower whenever the
        // change is meant to outlive this session (doc/specs/persistence.md
        // "Capability and audit") -- checked once per call, not once per
        // target: PERSIST is a property of the call's tier, not of any one
        // target, and a call with zero targets (a pattern matching no
        // currently-known logger) must not skip this check just because the
        // loop above never ran (a code-review finding -- this used to be
        // nested inside it).
        if (opts.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }
    }

    /**
     * Throws the usage error {@link #setLogger}/{@link #checkSetLevelPermitted}
     * share for a trailing-wildcard target (doc/specs/
     * pattern-selection-semantics.md, Decision #5) -- pulled into one place
     * (a code-review finding) so the two call sites can't drift if this rule
     * ever changes.
     */
    private static void rejectIfTrailingWildcard(String target, Level level) {
        if (NameFilter.isTrailingWildcard(target)) {
            throw rejectTrailingWildcard(target, level);
        }
    }

    /**
     * Builds the usage error for a trailing-wildcard target -- naming the
     * literal ancestor (the target with its trailing {@code ".*"} stripped)
     * as the fix, since the framework's own inheritance already covers every
     * descendant once the ancestor itself is set.
     *
     * <p>A target with <em>both</em> a leading and a trailing star (e.g.
     * {@code "*.apache.tomcat.*"}) strips down to an ancestor that is
     * itself still a pattern -- there is no single literal logger name to
     * set, so the framework-inheritance fix doesn't apply and isn't offered
     * (a code-review finding against an earlier version of this message,
     * which suggested running the still-a-pattern "ancestor" as if it were
     * one, silently contradicting its own inheritance claim).
     */
    private static IllegalArgumentException rejectTrailingWildcard(String target, Level level) {
        String ancestor = target.substring(0, target.length() - 2);
        String header = "'" + target + "': a trailing wildcard isn't accepted for a level-setting command.";
        if (NameFilter.isPattern(ancestor)) {
            return new IllegalArgumentException(header + " '" + ancestor + "' still matches more than one "
                    + "logger with no common ancestor to set instead -- run 'logctl list loggers " + ancestor
                    + " --show-all' to see the current matches, then set the ones you actually want by their own "
                    + "exact name.");
        }
        String fix = "logctl set logger " + ancestor + " " + level.name();
        return new IllegalArgumentException(header + " Every descendant of '" + ancestor + "' already inherits "
                + "its level from the logging framework once '" + ancestor + "' itself is set -- run '" + fix
                + "' instead.");
    }

    @Override
    public ResetOutcome resetLogger(String target, boolean includeSticky) {
        Objects.requireNonNull(target, "target");
        if (NameFilter.isPattern(target)) {
            return resetPattern(target, includeSticky);
        }
        Optional<LevelOverride> existing = overrides.get(target);
        if (existing.isEmpty()) {
            return ResetOutcome.nothingReset(); // no-op, not an error -- per spec
        }
        if (existing.get().tier() == PersistenceTier.STICKY && !includeSticky) {
            // Decision #1 (doc/specs/reset-command-surface.md): a single
            // named target refuses outright rather than silently doing
            // nothing -- checked before the capability check below, same
            // "validate the target's shape/eligibility first" ordering
            // rejectIfTrailingWildcard already uses ahead of setLogger's own
            // capability check.
            throw rejectSticky(target);
        }
        // Simplification for this slice: every reset requires LEVEL_LOWER,
        // regardless of whether reverting to baseline happens to raise or
        // lower the effective level for this particular logger. resetAll's
        // "get me back to normal" framing is the dominant use case; the
        // capability-direction nuance for a reset that's actually a raise
        // (reverting a manual silence) is a known, documented gap -- not
        // resolved by the spec, not addressed here.
        requireCapability(Capability.LEVEL_LOWER);
        if (applyReset(target, existing.get(), source, null)) {
            safePersist(() -> stateStore.remove(target));
        }
        changeListener.onChange(); // this logger's override just went away -- an AUTO handler tracking it needs to know
        return new ResetOutcome(List.of(target), List.of());
    }

    /**
     * A pattern target's {@code resetLogger} (doc/specs/
     * pattern-selection-semantics.md "Operations") -- resolves {@code
     * pattern}'s current match set (same matcher as always) and reverts
     * whichever of those loggers carry an active {@link LevelOverride},
     * regardless of how that override came to exist. No rule identity to
     * look up any more: this is exactly what {@code listLoggers}/{@code
     * levels} already do, filtered down to "and has an override." A
     * {@code STICKY} match is left alone and reported rather than refused
     * (doc/specs/reset-command-surface.md, Decision #1 -- a pattern names a
     * set, however large, not one specific thing).
     */
    private ResetOutcome resetPattern(String pattern, boolean includeSticky) {
        // One read per candidate name, not two (a code-review finding
        // against an earlier version of this method, which read `overrides`
        // once to decide whether a name qualified, then again, separately,
        // right before reverting it): iterate matchesFor's plain name
        // snapshot -- same discipline sweepExpiredOverrides follows -- and
        // act on whatever a single fresh overrides.get(name) finds. The
        // capability check is deferred to the first name actually reverted,
        // so a match set with nothing to revert (nothing overridden, or
        // every match sticky-skipped) still skips it entirely, same
        // convention as an exact-name target.
        List<String> matches = matchesFor(pattern);
        List<String> reverted = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        boolean capabilityChecked = false;
        for (String name : matches) {
            Optional<LevelOverride> current = overrides.get(name);
            if (current.isEmpty()) {
                continue;
            }
            if (current.get().tier() == PersistenceTier.STICKY && !includeSticky) {
                skippedSticky.add(name);
                continue;
            }
            if (!capabilityChecked) {
                requireCapability(Capability.LEVEL_LOWER);
                capabilityChecked = true;
            }
            if (applyReset(name, current.get(), source, null)) {
                reverted.add(name);
            }
        }
        if (reverted.isEmpty() && skippedSticky.isEmpty()) {
            return ResetOutcome.nothingReset();
        }
        if (!reverted.isEmpty()) {
            safePersist(() -> stateStore.removeAll(reverted)); // one rewrite for the whole match set (issue #17)
            changeListener.onChange();
        }
        return new ResetOutcome(reverted, skippedSticky);
    }

    @Override
    public ResetOutcome resetAllLoggers(boolean includeSticky) {
        // Unconditional capability check up front, matching the removed
        // resetAll()'s own convention (unlike resetPattern's lazy check) --
        // "reset everything" asks for authorization to do that regardless of
        // what, if anything, currently qualifies.
        requireCapability(Capability.LEVEL_LOWER);
        List<String> reverted = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            if (entry.getValue().tier() == PersistenceTier.STICKY && !includeSticky) {
                skippedSticky.add(entry.getKey());
                continue;
            }
            if (applyReset(entry.getKey(), entry.getValue(), source, null)) {
                reverted.add(entry.getKey());
            }
        }
        if (!reverted.isEmpty()) {
            safePersist(() -> stateStore.removeAll(reverted)); // one rewrite, not one per logger (issue #17)
            changeListener.onChange();
        }
        return new ResetOutcome(reverted, skippedSticky);
    }

    /**
     * Builds the usage error for a sticky exact-name reset target (doc/specs/
     * reset-command-surface.md, Decision #1) -- reuses {@code
     * IllegalArgumentException}, the same type {@link #rejectTrailingWildcard}
     * already throws for a different {@code reset}/{@code set} usage
     * mistake, so it needs no new exception type and reaches {@code logctl}
     * as the same exit-2 usage error (doc/specs/cli-transport.md's existing
     * {@code IllegalArgumentException} -&gt; exit 2 mapping, unchanged by
     * this slice).
     */
    private static IllegalArgumentException rejectSticky(String target) {
        return new IllegalArgumentException(
                "'" + target + "' is STICKY -- reset refused without --include-sticky.");
    }

    /**
     * Re-applies every currently-tracked override to {@code adapter} --
     * called from the composition root's reconfiguration-reset callback
     * (doc/specs/persistence.md "Reconfiguration re-application") once a
     * real reset event exists to drive it; also exercised directly by
     * tests, per doc/specs/level-control.md's re-appliability note.
     */
    public void reapplyActiveOverrides(LoggingAdapter targetAdapter) {
        // The vendor layer first, for loggers no override covers -- doc/specs/vendor-defaults.md
        // "Reconfiguration and the verification sweep": a framework reset erased it too.
        for (String name : baselines.vendorLoggerNames()) {
            if (overrides.get(name).isEmpty()) {
                targetAdapter.applyLevel(name, baselines.vendorLevel(name).orElseThrow());
            }
        }
        for (LevelOverride override : overrides.all().values()) {
            OverrideApplier.apply(override, targetAdapter);
        }
    }

    /**
     * Applies the vendor defaults file's logger levels -- doc/specs/vendor-defaults.md "Loggers".
     * Runs once, at composition-root install time, after native baseline capture and before
     * {@link #resumeFromStateStore}, so persisted overrides land on top. Each logger's native
     * baseline is captured first, so the application's own value is never read back as the
     * vendor's. Eager (Decision M1): a logger that doesn't exist yet is configured now. No
     * capability check (vendor-config-epic.md Decision #11); one {@code "vendor-defaults"} audit
     * record per logger. A logger whose apply throws is skipped, the rest still apply.
     */
    public void applyVendorDefaults(Instant now) {
        for (String name : baselines.vendorLoggerNames()) {
            try {
                baselines.captureIfAbsent(name, adapter);
                Level vendorLevel = baselines.vendorLevel(name).orElseThrow();
                String previousValue = adapter.effectiveLevel(name).toString();
                adapter.applyLevel(name, vendorLevel);
                auditLog.record(new AuditRecord(now, principal, VendorDefaults.AUDIT_SOURCE, name, previousValue,
                        vendorLevel.toString(), baselines.vendorReason(name).orElse(null),
                        AuditRecord.Action.MUTATION));
            } catch (RuntimeException e) {
                System.err.println("[logaperture] failed to apply the vendor default for logger '" + name
                        + "', skipping it: " + e);
            }
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
     * {@code setLogger}/{@code resetLevel} run on a control-plane thread. It
     * follows {@link #sweepExpiredOverrides}'s discipline — iterate a snapshot
     * of <em>names</em>, re-read the registry entry per iteration, and (here)
     * re-check the entry <em>after</em> applying — so a concurrent reset that
     * removed the override cannot be "resurrected" by a stale snapshot value,
     * and a concurrent {@code setLogger} that replaced it is honoured rather
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
                // A concurrent resetLevel/setLogger won the race between our
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
        return reapplied + verifyVendorLayer(now);
    }

    /**
     * The verification sweep's vendor half -- doc/specs/vendor-defaults.md "Reconfiguration and
     * the verification sweep": a vendor-defaulted logger with no override whose configured level
     * no longer matches the vendor level is re-applied. Same re-check-after-apply discipline as
     * the override half: if an override appeared meanwhile, it wins.
     */
    private int verifyVendorLayer(Instant now) {
        int reapplied = 0;
        for (String loggerName : baselines.vendorLoggerNames()) {
            if (overrides.get(loggerName).isPresent()) {
                continue;
            }
            Level vendorLevel = baselines.vendorLevel(loggerName).orElseThrow();
            Optional<Level> current = adapter.configuredLevel(loggerName);
            if (current.isPresent() && current.get() == vendorLevel) {
                continue;
            }
            adapter.applyLevel(loggerName, vendorLevel);
            Optional<LevelOverride> raced = overrides.get(loggerName);
            if (raced.isPresent()) {
                OverrideApplier.apply(raced.get(), adapter);
                continue;
            }
            auditLog.record(new AuditRecord(now, principal, "verification-sweep", loggerName,
                    current.map(Level::toString).orElse("<inherited>"), vendorLevel.toString(), null,
                    AuditRecord.Action.MUTATION));
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
     * already persisted it to the shared store). Deliberately does not fire
     * {@link #changeListener} — this runs once per rebroadcast override
     * during a redeploy, and firing a full AUTO recompute after each one
     * would compute against a partially-rebroadcast context; the caller
     * (doc/specs/handler-floor-control.md "Persistence and resume ordering")
     * does one recompute pass after every override for the new context has
     * been adopted instead.
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
     * session persisted; it is not a new operator action. Deliberately does
     * not fire {@link #changeListener} per entry, for the same reason {@link
     * #adoptOverride} doesn't (doc/specs/handler-floor-control.md
     * "Persistence and resume ordering") — the composition root does one
     * recompute pass after both this and the handler service's own resume
     * have finished.
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
        // by the time this loop reaches an entry (a concurrent setLogger may
        // have already replaced it), and applyReset's compare-and-remove
        // uses this same fresh value, not the (possibly stale) one below.
        List<String> reverted = new ArrayList<>();
        for (String loggerName : overrides.all().keySet()) {
            Optional<LevelOverride> current = overrides.get(loggerName);
            if (current.isPresent() && current.get().tier() == PersistenceTier.FOR
                    && !current.get().expiresAt().isAfter(now)
                    && applyReset(loggerName, current.get(), "expiry-sweep", null)) {
                reverted.add(loggerName);
            }
        }
        if (!reverted.isEmpty()) {
            safePersist(() -> stateStore.removeAll(reverted)); // one rewrite per sweep tick, not one per expiry (issue #17)
            // Only when something actually expired -- a quiet sweep tick
            // triggers no AUTO recompute, matching this method's own
            // "idempotent, no audit noise" bar for a system with nothing to do.
            changeListener.onChange();
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
                loggerName, level, opts.reason(), now, source, opts.tier(), expiresAt);
        installOverride(override, source, previousValue);
        return override;
    }

    /**
     * The part of "create an override" that a live {@code setLogger} call
     * shares regardless of whether the target was an exact name or a
     * pattern's resolved match -- mutate the adapter, commit to the
     * registry, persist per {@code override}'s tier, and audit it under
     * {@code auditSource}.
     */
    private void installOverride(LevelOverride override, String auditSource, String previousValue) {
        OverrideApplier.apply(override, adapter); // mutation: the point of no return

        overrides.put(override); // commit
        // state-store write -- SESSION also removes, in case this logger
        // had a previously-persisted FOR/STICKY entry that this plain
        // mutation now supersedes; leaving it would reappear on resume.
        if (override.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.save(override));
        } else {
            safePersist(() -> stateStore.remove(override.loggerName()));
        }
        auditLog.record(new AuditRecord(
                override.appliedAt(), principal, auditSource, override.loggerName(), previousValue,
                override.level().toString(), override.reason(), AuditRecord.Action.MUTATION)); // audit
    }

    /**
     * @return {@code true} if this call actually reverted {@code loggerName} (adapter
     *         mutated, registry entry removed, audit recorded) -- {@code false} if a
     *         concurrent change already superseded {@code toRevert}, nothing to do. The
     *         state-store write is the caller's responsibility (a single-target caller
     *         persists immediately; a batch caller like {@code resetAll} or the expiry
     *         sweep collects every {@code true} name and makes one {@code removeAll}
     *         call after its loop -- doc/specs/persistence.md "Batch removal", issue #17).
     */
    private boolean applyReset(String loggerName, LevelOverride toRevert, String auditSource, String reasonOverride) {
        // Atomic compare-and-remove first: if the registry's current entry
        // for this logger is no longer exactly `toRevert`, a concurrent
        // setLogger already replaced it (the expiry sweep's own race, per
        // doc/specs/persistence.md's review) -- bail out without touching
        // the adapter, so the newer override is never clobbered.
        if (!overrides.removeIfCurrent(loggerName, toRevert)) {
            return false;
        }

        String previousValue = toRevert.level().toString();
        Optional<Level> baseline = baselines.get(loggerName); // always captured -- setLogger/resume guarantees it

        adapter.applyLevel(loggerName, baseline.orElse(null)); // mutation

        String newValue = baseline.map(Level::toString).orElse("<inherited>");
        auditLog.record(new AuditRecord(
                Instant.now(), principal, auditSource, loggerName, previousValue, newValue, reasonOverride,
                AuditRecord.Action.REVERSION)); // audit
        return true;
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

    /**
     * The stricter (higher-ordinal, less verbose) of two {@link HandlerFloor}
     * readings for what turned out to be the same {@link HandlerRef} --
     * used to merge two targets' blocking-handler results without losing
     * whichever one actually needs the stricter fix (code-review finding;
     * see the merge call site above).
     */
    static HandlerFloor stricterFloor(HandlerFloor a, HandlerFloor b) {
        return a.currentLevel().compareTo(b.currentLevel()) >= 0 ? a : b;
    }
}
