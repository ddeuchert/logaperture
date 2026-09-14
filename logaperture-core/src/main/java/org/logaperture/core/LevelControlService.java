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
import org.logaperture.api.PatternRule;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final PatternRuleRegistry patternRules = new PatternRuleRegistry();
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
                    override.map(LevelOverride::expiresAt).orElse(null)));
        }
        return List.copyOf(result);
    }

    @Override
    public SetLevelResult setLevel(String target, Level level, SetLevelOptions options) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(level, "level");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;

        if (NameFilter.isPattern(target)) {
            return setLevelForPattern(target, level, opts);
        }
        return setLevelForExactName(target, level, opts);
    }

    private SetLevelResult setLevelForExactName(String loggerName, Level level, SetLevelOptions opts) {
        checkSetLevelPermitted(List.of(loggerName), level, opts);

        baselines.captureIfAbsent(loggerName, adapter);
        Level previousEffective = adapter.effectiveLevel(loggerName);

        LevelOverride override = applyAndRecordMutation(loggerName, level, opts, null);

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
     * The standing-rule apply path (doc/specs/pattern-level-targeting.md
     * "Operations"). {@code pattern} is validated and resolved against
     * every currently-known logger name exactly like {@code listLoggers}
     * does; an unconfirmed call mutates nothing and throws {@link
     * ConfirmationRequiredException} instead.
     */
    private SetLevelResult setLevelForPattern(String pattern, Level level, SetLevelOptions opts) {
        List<String> matches = resolveConfirmedMatches(pattern, opts);

        // Precedence filtering happens BEFORE the capability check, not
        // after (a code-review finding): a logger already covered by an
        // exact-name override (originPattern == null) is never overwritten
        // by a rule, so it was never really a candidate to raise/lower in
        // the first place -- checking capability against it too could deny
        // the whole call over a logger this call will never touch. Anything
        // else -- no override yet, or one from an older pattern rule -- is
        // fair game, since this rule's appliedAt (now) is newest by
        // construction.
        List<String> targetsToMutate = filterByPrecedence(matches);
        checkSetLevelPermitted(targetsToMutate, level, opts);

        Instant now = Instant.now();
        Instant expiresAt = opts.tier() == PersistenceTier.FOR ? now.plus(opts.expiresIn()) : null;

        Map<String, Level> previousEffectiveByTarget = new LinkedHashMap<>();
        for (String name : targetsToMutate) {
            baselines.captureIfAbsent(name, adapter);
            previousEffectiveByTarget.put(name, adapter.effectiveLevel(name));
        }

        // The rule is persisted BEFORE any per-logger override (a
        // code-review finding): if the process stops in between, resume's
        // applyRulesToUncoveredLoggers self-heals a logger whose override
        // didn't make it to disk (it simply looks uncovered and gets swept
        // up again), where the reverse order could persist overrides tagged
        // with a rule that was never actually saved, orphaning them.
        PatternRule rule = new PatternRule(pattern, level, opts.reason(), now, source, opts.tier(), expiresAt);
        patternRules.put(rule);
        if (opts.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.savePatternRule(rule));
        } else {
            safePersist(() -> stateStore.removePatternRule(pattern));
        }

        List<LevelOverride> created = new ArrayList<>();
        for (String name : targetsToMutate) {
            created.add(applyAndRecordMutation(name, level, opts, pattern));
        }

        if (!created.isEmpty()) {
            changeListener.onChange();
        }

        Map<HandlerRef, HandlerFloor> blockingByRef = new LinkedHashMap<>();
        for (String name : targetsToMutate) {
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
     * pattern-level-targeting.md, Decision #2).
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
     * Runs {@code setLevel}'s capability pre-flight without mutating anything —
     * throws {@link CapabilityDeniedException} (or, for a pattern target,
     * {@link ConfirmationRequiredException}) exactly where {@code setLevel}
     * would. {@link AggregateLevelControl} calls this against <em>every</em>
     * context before broadcasting a {@code setLevel}, so a denial in any one
     * context fails the whole broadcast before any context is mutated
     * (doc/specs/wildfly-support.md, "all pass or all fail").
     */
    public void checkSetLevelPermitted(String target, Level level, SetLevelOptions options) {
        Objects.requireNonNull(target, "target");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;
        List<String> targets = NameFilter.isPattern(target)
                ? filterByPrecedence(resolveConfirmedMatches(target, opts))
                : List.of(target);
        checkSetLevelPermitted(targets, level, opts);
    }

    /**
     * Every {@code matches} entry not already covered by a higher-
     * precedence exact-name override — the set a pattern call will actually
     * mutate (doc/specs/pattern-level-targeting.md "Precedence"). Shared by
     * the real apply path and the pre-flight check so both judge capability
     * against the same, correctly-narrowed set.
     */
    private List<String> filterByPrecedence(List<String> matches) {
        List<String> targetsToMutate = new ArrayList<>();
        for (String name : matches) {
            Optional<LevelOverride> existing = overrides.get(name);
            if (existing.isPresent() && existing.get().originPattern() == null) {
                continue;
            }
            targetsToMutate.add(name);
        }
        return targetsToMutate;
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
        // currently-known logger, still creating a durable standing rule)
        // must not skip this check just because the loop above never ran
        // (a code-review finding -- this used to be nested inside it).
        if (opts.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }
    }

    @Override
    public ResetOutcome resetLevel(String target) {
        Objects.requireNonNull(target, "target");
        if (NameFilter.isPattern(target)) {
            return resetPattern(target);
        }
        Optional<LevelOverride> existing = overrides.get(target);
        if (existing.isEmpty()) {
            return ResetOutcome.nothingReset(); // no-op, not an error -- per spec
        }
        // Simplification for this slice: every reset requires LEVEL_LOWER,
        // regardless of whether reverting to baseline happens to raise or
        // lower the effective level for this particular logger. resetAll's
        // "get me back to normal" framing is the dominant use case; the
        // capability-direction nuance for a reset that's actually a raise
        // (reverting a manual silence) is a known, documented gap -- not
        // resolved by the spec, not addressed here.
        requireCapability(Capability.LEVEL_LOWER);
        applyReset(target, existing.get(), source, null);
        changeListener.onChange(); // this logger's override just went away -- an AUTO handler tracking it needs to know
        return new ResetOutcome(List.of(target), false);
    }

    /**
     * Retires a standing rule (doc/specs/pattern-level-targeting.md,
     * Decision #5): looked up by {@code pattern}'s exact string against the
     * tracked {@link PatternRule}, not by recomputing which loggers
     * currently match. No-op, not an error, if no rule is tracked under
     * that exact string -- same convention as resetting an unoverridden
     * logger. Reports exactly which loggers it reverted rather than
     * leaving the caller to reconstruct that by diffing two separate
     * {@code listLoggers} reads around this call -- a code-review finding
     * against the original slice: that diff was racy against concurrent
     * mutation, and had no way to distinguish "no rule existed" from "the
     * rule existed but matched nothing" (so the CLI always printed
     * "Standing rule retired" even on a no-op).
     */
    private ResetOutcome resetPattern(String pattern) {
        NameFilter.compile(pattern); // validated even though only an exact-string lookup follows
        Optional<PatternRule> existing = patternRules.get(pattern);
        if (existing.isEmpty()) {
            return ResetOutcome.nothingReset();
        }
        requireCapability(Capability.LEVEL_LOWER);
        PatternRule rule = existing.get();
        if (!patternRules.removeIfCurrent(pattern, rule)) {
            return ResetOutcome.nothingReset(); // a concurrent setLevel/resetLevel already replaced or removed it
        }
        List<String> reverted = new ArrayList<>();
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            if (pattern.equals(entry.getValue().originPattern())) {
                applyReset(entry.getKey(), entry.getValue(), source, null);
                reverted.add(entry.getKey());
            }
        }
        safePersist(() -> stateStore.removePatternRule(pattern));
        changeListener.onChange();
        return new ResetOutcome(reverted, true);
    }

    @Override
    public void resetAll() {
        requireCapability(Capability.LEVEL_LOWER);
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            applyReset(entry.getKey(), entry.getValue(), source, null);
        }
        for (String pattern : patternRules.all().keySet()) {
            patternRules.remove(pattern);
            safePersist(() -> stateStore.removePatternRule(pattern));
        }
        changeListener.onChange();
    }

    /**
     * The standing-rule sweep pass (doc/specs/pattern-level-targeting.md
     * "Sweep integration") — owned and scheduled by the composition root,
     * same as {@link #sweepExpiredOverrides} and {@link #verifyAndReapply}.
     * Two steps: retire any {@code FOR}-tier rule whose {@code expiresAt}
     * has passed (reverting every override it produced), then apply the
     * newest still-active rule that matches to every logger the adapter now
     * knows about that has no override at all -- one the sweep discovers
     * only now, or one whose exact-name/older-pattern override was reset
     * since the last tick.
     */
    public void applyStandingRules(Instant now) {
        for (PatternRule rule : List.copyOf(patternRules.all().values())) {
            if (rule.tier() == PersistenceTier.FOR && !rule.expiresAt().isAfter(now)) {
                expirePatternRule(rule);
            }
        }

        List<PatternRule> activeRules = new ArrayList<>(patternRules.all().values());
        activeRules.sort(Comparator.comparing(PatternRule::appliedAt).reversed());
        if (applyRulesToUncoveredLoggers(activeRules, now, "pattern-sweep")) {
            changeListener.onChange();
        }
    }

    /** A {@code FOR}-tier rule past its deadline: revert every override it produced, then remove it. */
    private void expirePatternRule(PatternRule rule) {
        if (!patternRules.removeIfCurrent(rule.pattern(), rule)) {
            return; // a concurrent setLevel/resetLevel already replaced or removed it
        }
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            if (rule.pattern().equals(entry.getValue().originPattern())) {
                applyReset(entry.getKey(), entry.getValue(), "expiry-sweep", null);
            }
        }
        safePersist(() -> stateStore.removePatternRule(rule.pattern()));
    }

    /**
     * For every logger the adapter knows about with no active override at
     * all, applies the first (i.e. newest-{@code appliedAt}) rule in {@code
     * rulesNewestFirst} that matches it — shared by the periodic sweep and
     * by {@link #resumeFromStateStore}, which reaches the identical "cover
     * every uncovered logger, newest rule wins" outcome for a JVM that was
     * simply never running to see the loggers created in between.
     *
     * @return whether any logger was actually covered
     */
    private boolean applyRulesToUncoveredLoggers(List<PatternRule> rulesNewestFirst, Instant now, String auditSource) {
        if (rulesNewestFirst.isEmpty()) {
            return false;
        }
        // Each rule's pattern is compiled once per call, not once per
        // (logger, rule) pair (a code-review finding): the periodic sweep
        // runs this over every known logger on every tick, so re-validating
        // and recompiling the same pattern string per logger scales badly
        // with logger count.
        List<Predicate<String>> matchers = new ArrayList<>(rulesNewestFirst.size());
        for (PatternRule rule : rulesNewestFirst) {
            matchers.add(NameFilter.compile(rule.pattern()));
        }
        boolean anyApplied = false;
        for (String loggerName : adapter.knownLoggerNames()) {
            if (overrides.get(loggerName).isPresent()) {
                continue;
            }
            for (int i = 0; i < rulesNewestFirst.size(); i++) {
                if (matchers.get(i).test(loggerName)) {
                    applyPatternDerivedOverride(loggerName, rulesNewestFirst.get(i), now, auditSource);
                    anyApplied = true;
                    break;
                }
            }
        }
        return anyApplied;
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
     * Every standing rule this context currently tracks — the {@link
     * PatternRule} counterpart to {@link #activeOverrides()}, for the same
     * redeploy-loop re-broadcast (doc/specs/wildfly-support.md, "The
     * redeploy loop"; doc/specs/pattern-level-targeting.md).
     */
    public List<PatternRule> activePatternRules() {
        return List.copyOf(patternRules.all().values());
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
     * The {@link PatternRule} counterpart to {@link #adoptOverride} — a
     * sibling context's already-active standing rule, re-broadcast onto
     * this (newly joined) context. Registers the rule here and immediately
     * covers every one of <em>this</em> context's own currently-known,
     * not-yet-overridden loggers it matches, same "newest rule wins"
     * precedence the periodic sweep and resume use. No state-store write —
     * the originating context already persisted it to the shared store
     * (same reasoning as {@link #adoptOverride}).
     */
    public void adoptPatternRule(PatternRule rule) {
        Objects.requireNonNull(rule, "rule");
        patternRules.put(rule);
        List<PatternRule> activeRules = new ArrayList<>(patternRules.all().values());
        activeRules.sort(Comparator.comparing(PatternRule::appliedAt).reversed());
        applyRulesToUncoveredLoggers(activeRules, Instant.now(), "resume");
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

        // Standing rules (doc/specs/pattern-level-targeting.md "Resume on
        // restart"): reload every persisted rule not yet expired, then --
        // same "newest rule wins" precedence the periodic sweep uses --
        // cover every logger the adapter already knows about with no
        // override at all (a per-logger override just resumed above, or an
        // exact-name one from a previous session, is left alone either way).
        List<PatternRule> stillActive = new ArrayList<>();
        for (PatternRule persisted : stateStore.loadAllPatternRules()) {
            try {
                if (persisted.tier() == PersistenceTier.FOR && !persisted.expiresAt().isAfter(now)) {
                    // Expired while this JVM was down -- never (re-)applied
                    // to any logger this session, but still recorded and
                    // dropped, same convention as a per-logger FOR override
                    // above; loggerName carries the pattern text here, the
                    // closest fit this record shape has for "a rule, not a
                    // single logger, expired unseen."
                    auditLog.record(new AuditRecord(
                            now, principal, "resume", persisted.pattern(), persisted.level().toString(),
                            persisted.level().toString(), "expired while stopped", AuditRecord.Action.REVERSION));
                    safePersist(() -> stateStore.removePatternRule(persisted.pattern()));
                    continue;
                }
                patternRules.put(persisted);
                stillActive.add(persisted);
            } catch (RuntimeException e) {
                System.err.println("[logaperture-state] failed to resume persisted pattern rule '"
                        + persisted.pattern() + "', skipping it: " + e);
            }
        }
        stillActive.sort(Comparator.comparing(PatternRule::appliedAt).reversed());
        applyRulesToUncoveredLoggers(stillActive, now, "resume");
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
        boolean anyReverted = false;
        for (String loggerName : overrides.all().keySet()) {
            Optional<LevelOverride> current = overrides.get(loggerName);
            if (current.isPresent() && current.get().tier() == PersistenceTier.FOR
                    && !current.get().expiresAt().isAfter(now)) {
                applyReset(loggerName, current.get(), "expiry-sweep", null);
                anyReverted = true;
            }
        }
        if (anyReverted) {
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

    private LevelOverride applyAndRecordMutation(String loggerName, Level level, SetLevelOptions opts, String originPattern) {
        baselines.captureIfAbsent(loggerName, adapter);
        String previousValue = adapter.effectiveLevel(loggerName).toString();

        Instant now = Instant.now();
        Instant expiresAt = opts.tier() == PersistenceTier.FOR ? now.plus(opts.expiresIn()) : null;
        LevelOverride override = new LevelOverride(
                loggerName, level, originPattern, opts.reason(), now, source, opts.tier(), expiresAt);
        installOverride(override, source, previousValue);
        return override;
    }

    /**
     * Creates one matched logger's override on behalf of a {@link
     * PatternRule} -- the sweep discovering a new logger, or resume
     * reinstating a rule's reach after a restart. Unlike {@link
     * #applyAndRecordMutation}, there is no live {@link SetLevelOptions}
     * here: every field comes from the rule itself, and {@code auditSource}
     * names the actual actor ({@code "pattern-sweep"} or {@code "resume"}),
     * distinct from the override's own {@code source} (the rule's original
     * {@code "jmx"}-style provenance, carried through unchanged).
     */
    private LevelOverride applyPatternDerivedOverride(String loggerName, PatternRule rule, Instant now, String auditSource) {
        baselines.captureIfAbsent(loggerName, adapter);
        String previousValue = adapter.effectiveLevel(loggerName).toString();

        LevelOverride override = new LevelOverride(
                loggerName, rule.level(), rule.pattern(), rule.reason(), now, rule.source(), rule.tier(), rule.expiresAt());
        installOverride(override, auditSource, previousValue);
        return override;
    }

    /**
     * The part of "create an override" that a live {@code setLevel} call
     * and a rule-derived one (the sweep, or resume) actually share, once
     * each has built its own {@link LevelOverride} from its own inputs
     * (opts vs. a {@link PatternRule}) -- mutate the adapter, commit to the
     * registry, persist per {@code override}'s tier, and audit it under
     * {@code auditSource}. Pulled out because {@link #applyAndRecordMutation}
     * and {@link #applyPatternDerivedOverride} used to duplicate this whole
     * sequence (a code-review finding).
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
