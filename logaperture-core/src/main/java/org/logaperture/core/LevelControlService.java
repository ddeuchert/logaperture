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
                    override.map(LevelOverride::expiresAt).orElse(null),
                    override.map(o -> o.originPattern() != null).orElse(false)));
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
    public ResetOutcome resetLevel(String target, boolean includeSticky, String reason) {
        Objects.requireNonNull(target, "target");
        boolean isPattern = NameFilter.isPattern(target);
        if (isPattern) {
            NameFilter.compile(target); // validated even though the exact-string branch below only does a lookup
            Optional<PatternRule> exact = patternRules.get(target);
            if (exact.isPresent()) {
                return retireWholeRule(target, exact.get(), includeSticky, reason);
            }
        }
        return resetScopedTarget(target, isPattern, includeSticky, reason);
    }

    /**
     * {@code target} is exactly a tracked rule's own pattern string —
     * doc/specs/pattern-level-targeting.md's original, #41-shipped path
     * (looked up by exact string, not by recomputing which loggers
     * currently match), now gated by {@code includeSticky} and reporting
     * the richer {@link ResetOutcome} shape doc/specs/
     * reset-command-surface.md adds.
     */
    private ResetOutcome retireWholeRule(String pattern, PatternRule rule, boolean includeSticky, String reason) {
        if (rule.tier() == PersistenceTier.STICKY && !includeSticky) {
            // Left alone entirely, not even capability-checked -- nothing is
            // attempted against a sticky rule without the flag (doc/specs/
            // reset-command-surface.md "Capability and audit"). Every
            // logger this rule currently covers is reported skipped, since
            // none of them get to revert either.
            List<String> skipped = new ArrayList<>();
            for (Map.Entry<String, LevelOverride> e : overrides.all().entrySet()) {
                if (pattern.equals(e.getValue().originPattern())) {
                    skipped.add(e.getKey());
                }
            }
            if (skipped.isEmpty()) {
                // The rule itself is live and STICKY even though nothing it
                // currently covers has an override (never yet swept onto a
                // logger, or every prior match was individually excluded) --
                // report the rule's own pattern so the caller can tell this
                // apart from "no such rule was ever tracked" (a code-review
                // finding: both cases used to return an identical, entirely
                // empty ResetOutcome, so the CLI rendered "nothing was
                // overridden" for a still-live, still-cascading rule).
                skipped.add(pattern);
            }
            return new ResetOutcome(List.of(), List.of(), List.of(), skipped);
        }
        requireCapability(Capability.LEVEL_LOWER);
        if (!patternRules.removeIfCurrent(pattern, rule)) {
            return ResetOutcome.nothingReset(); // a concurrent setLevel/resetLevel already replaced or removed it
        }
        List<String> reverted = new ArrayList<>();
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            if (pattern.equals(entry.getValue().originPattern())) {
                applyReset(entry.getKey(), entry.getValue(), source, reason);
                reverted.add(entry.getKey());
            }
        }
        safePersist(() -> stateStore.removePatternRule(pattern));
        changeListener.onChange();
        return new ResetOutcome(reverted, List.of(pattern), List.of(), List.of());
    }

    /**
     * {@code target} is an exact name, or a sub-pattern that isn't itself a
     * tracked rule's own pattern string — the general case of doc/specs/
     * reset-command-surface.md's "Partial reset — scoped exclusions":
     * revert whatever is currently overridden within {@code target}'s
     * scope, and for any rule that governs part of it, either carve {@code
     * target} into that rule's exclusion set or, if that carve-out (added
     * to whatever the rule was already missing) leaves nothing of the
     * rule's own coverage standing, retire the rule outright (a code-review
     * finding against the original slice: it always recorded the raw,
     * un-narrowed {@code target} as the exclusion, even for a rule whose
     * pattern {@code target} itself fully contained, permanently blinding
     * that rule to everything it could ever match instead of retiring it
     * the way {@link #retireWholeRule}'s exact-string path already would
     * have for the same pattern spelled out directly; the original slice
     * also never retired a rule this way at all, however thoroughly its
     * exclusions accumulated). See {@link #shouldRetire} and {@link
     * #exclusionsFor} for the two decisions this makes per affected rule.
     * {@code STICKY} filtering happens per logger, before grouping by rule
     * — every override a given rule produces shares its tier by
     * construction ({@link #applyPatternDerivedOverride}), so this needs no
     * separate rule-level check the way {@link #retireWholeRule} does (that
     * path has no override to read a tier from when the rule currently
     * covers zero loggers).
     */
    private ResetOutcome resetScopedTarget(String target, boolean isPattern, boolean includeSticky, String reason) {
        List<String> targetMatches = isPattern ? matchesFor(target) : List.of(target);

        Map<String, List<String>> loggersByRule = new LinkedHashMap<>();
        List<String> plain = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        // Carries each matched logger's already-read LevelOverride from
        // classification into the apply loops below, so applying a reset
        // costs one registry lookup per logger, not two (a code-review
        // finding against the original two-pass shape, which re-fetched by
        // name a second time at apply). Safe to trust at apply time despite
        // being read here, before requireCapability and before any mutation:
        // applyReset's own removeIfCurrent compare-and-remove still catches
        // a concurrent change in between and reports it via its boolean
        // return, which is what actually decides `reverted` membership below
        // -- this map only saves the redundant re-read, it never bypasses
        // the freshness check.
        Map<String, LevelOverride> overridesByName = new LinkedHashMap<>();
        for (String name : targetMatches) {
            Optional<LevelOverride> existing = overrides.get(name);
            if (existing.isEmpty()) {
                continue;
            }
            LevelOverride override = existing.get();
            if (override.tier() == PersistenceTier.STICKY && !includeSticky) {
                skippedSticky.add(name);
                continue;
            }
            overridesByName.put(name, override);
            if (override.originPattern() != null) {
                loggersByRule.computeIfAbsent(override.originPattern(), k -> new ArrayList<>()).add(name);
            } else {
                plain.add(name);
            }
        }
        if (loggersByRule.isEmpty() && plain.isEmpty()) {
            return new ResetOutcome(List.of(), List.of(), List.of(), skippedSticky);
        }
        requireCapability(Capability.LEVEL_LOWER);

        List<String> reverted = new ArrayList<>();
        for (String name : plain) {
            // Only reported reverted when applyReset's own compare-and-
            // remove actually succeeded (a code-review finding): a
            // concurrent reset between the classification scan above and
            // here would otherwise silently no-op while this call still
            // claimed credit for it.
            if (applyReset(name, overridesByName.get(name), source, reason)) {
                reverted.add(name);
            }
        }

        List<String> excludedFrom = new ArrayList<>();
        List<String> retiredByCarveOut = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : loggersByRule.entrySet()) {
            String pattern = entry.getKey();
            Optional<PatternRule> ruleOpt = patternRules.get(pattern);
            if (ruleOpt.isEmpty()) {
                continue; // concurrently retired between the scan above and now
            }
            PatternRule rule = ruleOpt.get();
            List<String> matchedUnderRule = entry.getValue();

            List<String> actuallyReverted = new ArrayList<>();
            for (String name : matchedUnderRule) {
                // Same "only claim what actually happened" fix as the plain
                // loop above.
                if (applyReset(name, overridesByName.get(name), source, reason)) {
                    actuallyReverted.add(name);
                }
            }
            reverted.addAll(actuallyReverted);

            // What this carve-out would add to the rule's exclusions if it
            // doesn't retire the rule outright -- computed before the retire
            // decision below so both share the exact same value: the
            // "would this exhaust the rule's own coverage" check needs to
            // know precisely what's being added, not just that something is.
            List<String> exclusionsToAdd = exclusionsFor(target, rule, matchedUnderRule);
            if (shouldRetire(target, rule, exclusionsToAdd)) {
                if (patternRules.removeIfCurrent(pattern, rule)) {
                    safePersist(() -> stateStore.removePatternRule(pattern));
                    retiredByCarveOut.add(pattern);
                }
                // A lost removeIfCurrent race (a concurrent setLevel/
                // resetLevel already replaced or removed the rule) leaves
                // the reverts above standing -- they're real -- but records
                // no retirement for this call; the winner of that race owns
                // this rule's own outcome, same as retireWholeRule's own
                // removeIfCurrent guard.
                continue;
            }

            PatternRule updated = rule.withExclusions(exclusionsToAdd);
            if (patternRules.replaceIfCurrent(pattern, rule, updated)) {
                if (rule.tier() != PersistenceTier.SESSION) {
                    safePersist(() -> stateStore.savePatternRule(updated));
                }
                excludedFrom.add(pattern);
            }
            // A lost replaceIfCurrent race (a concurrent setLevel/resetLevel
            // already replaced the rule) leaves the reverts above standing
            // -- they're real -- but records no exclusion for this call;
            // the winner of that race is responsible for its own outcome.
        }

        changeListener.onChange(); // reaching here means plain or loggersByRule was non-empty -- something changed
        return new ResetOutcome(reverted, retiredByCarveOut, excludedFrom, skippedSticky);
    }

    /**
     * Whether carving {@code exclusionsToAdd} into {@code rule} (on top of
     * whatever it already excludes) leaves the rule with nothing left to
     * do, per doc/specs/reset-command-surface.md's "Partial reset — scoped
     * exclusions" step 4 ("if target's scope, together with that rule's
     * existing exclusions, now accounts for the rule's entire original
     * coverage, the rule has nothing left to do — remove it outright") —
     * true via either of two tests, both sound (never claim retirement
     * unless nothing of the rule's own reach genuinely remains):
     *
     * <ol>
     *   <li><b>{@code target} abstractly contains the rule's own pattern</b>
     *       ({@link NameFilter#covers}) — {@code target}'s scope is a
     *       superset of everything {@code rule.pattern()} could ever match,
     *       present or future, so nothing an exclusion could preserve is
     *       left standing regardless of what's currently known. This is the
     *       primary case doc/specs/reset-command-surface.md's own worked
     *       example covers (two independent rules each fully contained by a
     *       broader reset target) and the one existing behavior this method
     *       must not regress: recording {@code target} as those rules'
     *       exclusion instead of retiring them left both permanently unable
     *       to match anything ever again (the bug this whole fix exists
     *       for).</li>
     *   <li><b>{@code exclusionsToAdd} is a finite set of exact names, and no
     *       currently-known logger matching the rule's pattern escapes it
     *       plus the rule's existing exclusions</b> — a pragmatic,
     *       concrete-state fallback for the case abstract containment can't
     *       decide (e.g. an exact-name {@code target} narrower than a
     *       trailing-star rule pattern, which can never abstractly contain
     *       it, but which happens to be the rule's <em>only</em>
     *       currently-known match). This is a real design call flagged in
     *       doc/specs/reset-command-surface.md (search "single
     *       currently-known logger"): it retires a rule that, in the
     *       abstract, could still match a logger discovered later under the
     *       same pattern -- forgoing that specific future-coverage
     *       guarantee in exchange for not leaving a rule that currently
     *       matches nothing sitting in {@code patternRules} (persisted,
     *       recompiled, and evaluated on every sweep tick) forever.
     *       <b>Restricted to exact-name additions on purpose</b> -- if
     *       {@code exclusionsToAdd} itself carries a sub-pattern (a
     *       wildcard carve-out that step 1 above already found doesn't
     *       abstractly contain the whole rule), then by definition some
     *       sibling scope the sub-pattern doesn't reach still belongs to the
     *       rule; "no currently-known logger escapes" in that case would
     *       only be a coincidence of which loggers happen to exist right
     *       now, not a fact about the rule's remaining reach, and retiring
     *       on it would wrongly cut off a sibling like {@code
     *       "org.apache.other"} that simply hasn't been created yet. Never
     *       wrongly <em>keeps</em> a rule around past this point either way:
     *       every name this test walks is re-read fresh via {@link
     *       #matchesFor}, and every name it treats as "already excluded" was
     *       reverted either by this very call or by whichever earlier
     *       {@code resetLevel} call first added it to {@code
     *       rule.exclusions()} (this method's own invariant -- an exclusion
     *       is never recorded here without reverting whatever it covers in
     *       the same call), so no currently-overridden logger is ever left
     *       orphaned by a retirement this test triggers.</li>
     * </ol>
     */
    private boolean shouldRetire(String target, PatternRule rule, List<String> exclusionsToAdd) {
        if (NameFilter.covers(target, rule.pattern())) {
            return true;
        }
        if (exclusionsToAdd.stream().anyMatch(NameFilter::isPattern)) {
            return false;
        }
        List<String> allExclusions = new ArrayList<>(rule.exclusions());
        allExclusions.addAll(exclusionsToAdd);
        Predicate<String> excluded = anyOf(allExclusions);
        for (String name : matchesFor(rule.pattern())) {
            if (!excluded.test(name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a carve-out that does <em>not</em> retire {@code rule} outright
     * should add to its exclusion set. {@code target} verbatim whenever
     * it's provably safe (narrower than or equal to {@code rule}'s own
     * pattern, per {@link NameFilter#covers}) — the common, spec-documented
     * case ({@code org.apache.tomcat} excluded from a live {@code
     * org.apache.*} rule, still covering the excluded name's own
     * descendants present and future). Otherwise {@code target}'s scope and
     * {@code rule}'s own aren't in a provable subset relationship either
     * way (a genuine partial overlap, e.g. a leading-star target crossing a
     * trailing-star rule, or a shape {@link NameFilter#covers} doesn't
     * reason about at all) — recording {@code target} itself here risks
     * exactly the bug this whole fix exists for (an over-broad exclusion
     * that outlives what this call actually had the right to carve out), so
     * this falls back to the individual currently-matched logger names
     * instead ({@code matchedUnderRule}, already known safe: every one of
     * them is a concrete name this call is reverting right now). Narrower
     * than {@code target} itself, and therefore never over-broad, at the
     * cost of not excluding a not-yet-discovered descendant of {@code
     * target} in this specific overlap shape -- an accepted, documented
     * trade-off (doc/specs/reset-command-surface.md, "Partial reset —
     * scoped exclusions").
     */
    private List<String> exclusionsFor(String target, PatternRule rule, List<String> matchedUnderRule) {
        if (NameFilter.covers(rule.pattern(), target)) {
            return List.of(target);
        }
        return matchedUnderRule;
    }

    @Override
    public ResetOutcome resetAllLoggers(boolean includeSticky) {
        List<String> candidates = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            if (entry.getValue().tier() == PersistenceTier.STICKY && !includeSticky) {
                skippedSticky.add(entry.getKey());
            } else {
                candidates.add(entry.getKey());
            }
        }
        List<String> retiredPatterns = new ArrayList<>();
        for (PatternRule rule : patternRules.all().values()) {
            if (rule.tier() == PersistenceTier.STICKY && !includeSticky) {
                continue; // left entirely alone -- its overrides are already in skippedSticky above
            }
            retiredPatterns.add(rule.pattern());
        }
        // Checked unconditionally, even when classification above found
        // nothing addressable -- a broad reset's pre-existing "get me back
        // to normal" contract means a policy denying LEVEL_LOWER denies
        // this call even against an empty registry (unlike a targeted
        // reset, which has nothing to check capability against when there's
        // nothing addressable). Placed after classification, not before --
        // classification is a read-only scan, so this doesn't change what
        // gets checked, only when, and it means the capability check (like
        // resetScopedTarget's own) sits between classification and apply
        // rather than in front of both.
        requireCapability(Capability.LEVEL_LOWER);
        if (candidates.isEmpty() && retiredPatterns.isEmpty()) {
            return new ResetOutcome(List.of(), List.of(), List.of(), skippedSticky);
        }
        List<String> reverted = new ArrayList<>();
        for (String loggerName : candidates) {
            // Only reported reverted when an override was actually still
            // there to revert (a code-review finding, shared with
            // resetScopedTarget's identical fix): a concurrent reset
            // between the classification scan above and here would
            // otherwise silently no-op through applyReset's own compare-
            // and-remove while this call still claimed credit for it.
            Optional<LevelOverride> current = overrides.get(loggerName);
            if (current.isEmpty()) {
                continue; // concurrently reset between the scan above and now
            }
            applyReset(loggerName, current.get(), source, null);
            reverted.add(loggerName);
        }
        for (String pattern : retiredPatterns) {
            patternRules.remove(pattern);
            safePersist(() -> stateStore.removePatternRule(pattern));
        }
        changeListener.onChange();
        return new ResetOutcome(reverted, retiredPatterns, List.of(), skippedSticky);
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
        // Each rule's pattern -- and now its exclusion set (doc/specs/
        // reset-command-surface.md "Partial reset — scoped exclusions") --
        // is compiled once per call, not once per (logger, rule) pair (a
        // code-review finding): the periodic sweep runs this over every
        // known logger on every tick, so re-validating and recompiling the
        // same pattern strings per logger scales badly with logger count.
        List<Predicate<String>> matchers = new ArrayList<>(rulesNewestFirst.size());
        List<Predicate<String>> exclusionMatchers = new ArrayList<>(rulesNewestFirst.size());
        for (PatternRule rule : rulesNewestFirst) {
            matchers.add(NameFilter.compile(rule.pattern()));
            exclusionMatchers.add(anyOf(rule.exclusions()));
        }
        boolean anyApplied = false;
        for (String loggerName : adapter.knownLoggerNames()) {
            if (overrides.get(loggerName).isPresent()) {
                continue;
            }
            for (int i = 0; i < rulesNewestFirst.size(); i++) {
                if (matchers.get(i).test(loggerName) && !exclusionMatchers.get(i).test(loggerName)) {
                    applyPatternDerivedOverride(loggerName, rulesNewestFirst.get(i), now, auditSource);
                    anyApplied = true;
                    break;
                }
            }
        }
        return anyApplied;
    }

    /**
     * A predicate matching any of {@code exclusions} (each entry using the
     * same {@code NameFilter} grammar as a rule's own pattern) — {@code
     * false} for every name when {@code exclusions} is empty, the common
     * case for a rule nothing has been carved out of.
     */
    private static Predicate<String> anyOf(List<String> exclusions) {
        return exclusions.stream()
                .map(LevelControlService::exclusionMatcher)
                .reduce(Predicate::or)
                .orElse(name -> false);
    }

    /**
     * One exclusion entry's own matcher. A {@code '*'}-bearing entry is a
     * real sub-pattern, matched the same way a rule's own pattern is
     * ({@link NameFilter#compile}). A plain entry names one exact logger --
     * matched by equality, <em>not</em> by handing it to {@link
     * NameFilter#compile} as-is, whose own no-{@code '*'} branch is a loose,
     * non-dot-aware prefix (documented on {@link NameFilter} itself as
     * {@code listLoggers}' own display-filter convenience, not this
     * segment-anchored exclusion grammar) — a code-review finding: excluding
     * {@code "org.apache.tomcat"} must not also swallow an unrelated
     * descendant like {@code "org.apache.tomcat.connector"} by raw string
     * prefix, which would contradict this very mechanism's "excluded name's
     * own descendants keep inheriting the rule" guarantee.
     */
    private static Predicate<String> exclusionMatcher(String exclusion) {
        return NameFilter.isPattern(exclusion) ? NameFilter.compile(exclusion) : exclusion::equals;
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

    /**
     * @return whether this call actually reverted {@code toRevert} -- {@code
     *         false} means a concurrent mutation won the compare-and-remove
     *         below, so nothing here touched the adapter, the state store,
     *         or the audit log. Every caller in a classification-then-apply
     *         pair (e.g. {@link #resetScopedTarget}) uses this, not a second
     *         registry read, to decide whether a name actually belongs in
     *         its own reported {@code reverted} list (a code-review
     *         finding's "only claim what actually happened" fix, and --
     *         since the {@link LevelOverride} classification already read
     *         is passed straight through rather than re-fetched -- also the
     *         fix for a separate finding: each matched logger cost two
     *         registry lookups, one at classification and one at apply,
     *         when this compare-and-remove already makes the second one
     *         redundant).
     */
    private boolean applyReset(String loggerName, LevelOverride toRevert, String auditSource, String reasonOverride) {
        // Atomic compare-and-remove first: if the registry's current entry
        // for this logger is no longer exactly `toRevert`, a concurrent
        // setLevel already replaced it (the expiry sweep's own race, per
        // doc/specs/persistence.md's review) -- bail out without touching
        // the adapter, so the newer override is never clobbered.
        if (!overrides.removeIfCurrent(loggerName, toRevert)) {
            return false;
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
