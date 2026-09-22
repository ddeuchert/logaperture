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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Drop;
import org.logaperture.api.LogRule;
import org.logaperture.api.PersistedRule;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The rule-pipeline engine — framework- and action-agnostic, per
 * doc/specs/rule-pipeline-foundation.md. Owns attachment (id assignment,
 * capability/suppression-floor checks, audit), {@code useParentRules}
 * inheritance resolution, and gate-stage evaluation ({@link #gate()}),
 * read live off the registry rather than a separately compiled plan
 * (doc/specs/drop-rule.md "Evaluation" retired the compiled-{@code
 * RulePlan}-swap design {@code rule-pipeline-foundation.md} originally
 * sketched, once real evaluation turned out to need mutable per-rule state
 * — hit counters, {@code sampleFull} clocks — a plan snapshot doesn't
 * carry anyway). No concrete rule type is built here — {@link #attach}
 * takes a {@link RuleFactory} so {@link Drop} and #34's {@code Trim} (and
 * this slice's own test double) all go through the identical
 * identity-assignment and safety-check path.
 *
 * <p>Every mutating method follows the same ordering
 * {@link LevelControlService} already establishes: capability check &rarr;
 * suppression-floor check &rarr; registry commit &rarr; audit record — so a
 * denial at any step leaves no partial state.
 *
 * <p>Mirrors {@link StormService}/{@link TopService}'s shape: {@link
 * #installPipeline()} installs the gate-stage filter the moment a context
 * comes up, and is safe to call again on every reconfiguration re-arm
 * (folded into the same {@code reapplyOnReset} lambda those two join) since
 * {@link LoggingAdapter#installRulePipeline} is itself required to be
 * idempotent.
 */
public final class RuleService implements RuleOperations {

    private final LoggingAdapter adapter;
    private final RuleRegistry registry = new RuleRegistry();
    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final StateStore stateStore;
    private final String context;
    private final String principal;
    private final String source;
    private final ProtectedCategories protectedCategories;
    private final AtomicLong idSequence = new AtomicLong(1);
    private final Map<String, RuleFactory> actionFactories = new ConcurrentHashMap<>();

    // --- Evaluation-time state (doc/specs/drop-rule.md "Evaluation") -- keyed by rule id;
    // cleaned up in forgetEvaluationState whenever a rule is removed, so a long-running process
    // doing ordinary attach/reset traffic doesn't grow these maps without bound (a code-review
    // finding).
    /** Per-event, not per-handler (rule-pipeline-foundation.md "Evaluation"): a verdict computed for one handler's filter is reused by every sibling handler's filter evaluating the same framework record. */
    private final Map<Object, GateVerdict> decisionCache = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, LongAdder> hitCounters = new ConcurrentHashMap<>();
    /** {@code Long.MIN_VALUE} sentinel = "never sampled yet" -- doc/specs/drop-rule.md "The keep-one-in-N escape hatch": the very first match is always kept. */
    private final Map<String, AtomicLong> nextSampleAtNanos = new ConcurrentHashMap<>();
    /** Suppressed (denied) hits since the rule's last-emitted summary line -- distinct from {@link #hitCounters}, which never resets. */
    private final Map<String, LongAdder> pendingSummaryCounters = new ConcurrentHashMap<>();
    /** {@code sampleFull}-kept hits since the rule's last-emitted summary line -- doc/specs/drop-rule.md's own worked example ("41,209 suppressed ..., 8 sampled through") reports these as a separate figure, never folded into "suppressed". */
    private final Map<String, LongAdder> pendingSampledCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastSummaryAtNanos = new ConcurrentHashMap<>();

    public RuleService(LoggingAdapter adapter, CapabilityPolicy policy, AuditLog auditLog, StateStore stateStore,
            String context, String principal, String source) {
        this(adapter, policy, auditLog, stateStore, context, principal, source, ProtectedCategories.none());
    }

    /**
     * @param context this instance's owning logging context's stable key
     *                (e.g. {@code "system"}, a deployment name) — every
     *                context in a JVM shares one {@code stateStore}, so
     *                this is what {@link #resumeFromStateStore} filters a
     *                persisted row by (a row belongs to the context that
     *                wrote it, never resumed into a different one sharing
     *                the same store) and what {@link #toPersisted} stamps
     *                on save.
     */
    public RuleService(LoggingAdapter adapter, CapabilityPolicy policy, AuditLog auditLog, StateStore stateStore,
            String context, String principal, String source, ProtectedCategories protectedCategories) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.context = Objects.requireNonNull(context, "context");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.source = Objects.requireNonNull(source, "source");
        this.protectedCategories = Objects.requireNonNull(protectedCategories, "protectedCategories");
    }

    /**
     * Registers the {@link RuleFactory} used to reconstruct a resumed {@code
     * action}'s concrete rule type on {@link #resumeFromStateStore} — doc/specs/
     * rule-pipeline-foundation.md "Persistence". Nothing calls this in this
     * slice (there is no concrete rule type yet); #72/#34 register their own
     * ({@code "Drop"}/{@code "Trim"}) at composition-root time, the same way
     * this slice's own tests register {@code TestRule}'s.
     */
    public void registerActionFactory(String action, RuleFactory factory) {
        actionFactories.put(Objects.requireNonNull(action, "action"), Objects.requireNonNull(factory, "factory"));
    }

    /**
     * Registers {@link Drop}'s own resume factory under its {@code
     * actionName()} ({@code "drop"}) — doc/specs/drop-rule.md "Persistence".
     * Each container's composition root calls this once, before {@link
     * #resumeFromStateStore}, the same way this slice's own tests call
     * {@link #registerActionFactory} directly for {@code TestRule}.
     */
    public void registerDropSupport() {
        registerActionFactory("drop", DropFactories.resume()); // matches Drop#actionName()'s literal
    }

    /**
     * Installs (or re-confirms) the gate-stage rule filter. Called once at
     * context-install time, and again on every reconfiguration re-arm — safe
     * either way, since {@link LoggingAdapter#installRulePipeline} is itself
     * required to be idempotent.
     */
    public void installPipeline() {
        adapter.installRulePipeline(gate());
    }

    /**
     * Attaches a new rule to {@code loggerName} — doc/specs/
     * rule-pipeline-foundation.md "Rule identity", "Capability and audit".
     * {@code factory} is handed this call's generated id/timestamps/resolved
     * expiry and builds the concrete {@link LogRule}; {@code matchers}/
     * {@code reason} are passed through to it unchanged.
     *
     * @throws CapabilityDeniedException if {@link Capability#RULES_AUTHOR}
     *                                    (or, for a non-{@code SESSION}
     *                                    tier, {@link Capability#PERSIST})
     *                                    is denied
     * @throws IllegalArgumentException  if {@code loggerName} is on the
     *                                    suppression floor
     */
    public LogRule attach(String loggerName, CompiledMatchers matchers, RuleAttachOptions options,
            RuleFactory factory) {
        return attach(loggerName, matchers, options, factory, null);
    }

    /**
     * Same as the four-arg {@link #attach}, requiring one additional
     * capability beyond {@link Capability#RULES_AUTHOR} (and, for a
     * non-{@code SESSION} tier, {@link Capability#PERSIST}) -- {@link Drop}'s
     * own attach path requires {@link Capability#SUPPRESS} this way
     * (doc/specs/drop-rule.md "Capability and audit"). {@code
     * additionalRequired} is {@code null} for a rule type with no
     * action-specific capability of its own.
     */
    public LogRule attach(String loggerName, CompiledMatchers matchers, RuleAttachOptions options,
            RuleFactory factory, Capability additionalRequired) {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(matchers, "matchers");
        Objects.requireNonNull(factory, "factory");
        RuleAttachOptions opts = options == null ? RuleAttachOptions.defaults() : options;

        requireCapability(Capability.RULES_AUTHOR);
        if (additionalRequired != null) {
            requireCapability(additionalRequired);
        }
        if (opts.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }
        if (protectedCategories.isProtected(loggerName)) {
            throw new IllegalArgumentException(
                    "'" + loggerName + "' is a protected category -- no rule may be attached to it.");
        }

        String id = "r" + idSequence.getAndIncrement();
        Instant now = Instant.now();
        Instant expiresAt = opts.tier() == PersistenceTier.FOR ? now.plus(opts.expiresIn()) : null;
        LogRule rule = factory.create(id, loggerName, matchers, opts.reason(), opts.tier(), expiresAt, now,
                Map.of());

        registry.attach(rule);
        if (rule.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.saveRule(toPersisted(rule)));
        }
        auditLog.record(new AuditRecord(now, principal, source, loggerName, null, describe(rule), opts.reason(),
                AuditRecord.Action.MUTATION));
        return rule;
    }

    /**
     * {@code logctl add rule drop} -- doc/specs/drop-rule.md "Command
     * surface". The first action type with a real CLI verb to create one
     * with; requires {@link Capability#SUPPRESS} in addition to {@link
     * Capability#RULES_AUTHOR}.
     */
    @Override
    public RuleView addRuleDrop(String loggerName, CompiledMatchers matchers, RuleAttachOptions options,
            SampleFullPolicy sampleFull) {
        LogRule rule = attach(loggerName, matchers, options, DropFactories.attach(sampleFull), Capability.SUPPRESS);
        return new RuleView(rule, context, hitCount(rule.id()));
    }

    /**
     * {@code reset rule <id>} — a single named id is "one specific thing":
     * refuses outright if it's {@code STICKY} and {@code includeSticky}
     * wasn't passed, mirroring {@code reset-command-surface.md}'s Decision
     * #1 exactly. Empty if no rule with this id exists (a no-op, not an
     * error, same "unknown target" discipline every other reset already
     * follows).
     */
    @Override
    public Optional<RuleView> resetRule(String id, boolean includeSticky) {
        Objects.requireNonNull(id, "id");
        requireCapability(Capability.RULES_AUTHOR);
        Optional<LogRule> existing = registry.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        LogRule rule = existing.get();
        if (rule.tier() == PersistenceTier.STICKY && !includeSticky) {
            throw new IllegalArgumentException(id + " is STICKY -- reset refused without --include-sticky.");
        }
        long finalHitCount = hitCount(id);
        registry.removeById(id);
        forgetEvaluationState(id);
        safePersist(() -> stateStore.removeRule(id));
        auditRemoval(rule);
        return Optional.of(new RuleView(rule, context, finalHitCount));
    }

    /** {@code reset rules} — bulk, skip-and-report shape (mirrors {@code reset loggers}). */
    @Override
    public RuleResetOutcome resetAllRules(boolean includeSticky) {
        requireCapability(Capability.RULES_AUTHOR);
        return removeMatching(registry.all(), includeSticky);
    }

    /**
     * The rules attached <em>directly</em> to {@code loggerName} (not its
     * descendants' own separately-attached rules) — {@code reset logger X}'s
     * side effect, doc/specs/rule-pipeline-foundation.md "Command surface".
     * Unlike {@link #resetRule}/{@link #resetAllRules} (an operator's own
     * direct, deliberate call), this runs unconditionally as a side effect
     * of a plain {@code reset logger} — a caller who was already authorized
     * for that (via {@code LEVEL_LOWER}) must not be newly blocked by a
     * capability {@code reset logger} never used to need, just because rule
     * cleanup exists now. So the {@link Capability#RULES_AUTHOR} check runs
     * only when there is actually something to remove; a target with no
     * attached rules costs nothing to authorize (a code-review finding).
     */
    @Override
    public RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky) {
        Objects.requireNonNull(loggerName, "loggerName");
        List<LogRule> candidates = registry.directRulesFor(loggerName);
        if (candidates.isEmpty()) {
            return RuleResetOutcome.nothingReset();
        }
        requireCapability(Capability.RULES_AUTHOR);
        return removeMatching(candidates, includeSticky);
    }

    private RuleResetOutcome removeMatching(List<LogRule> candidates, boolean includeSticky) {
        List<String> removed = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        for (LogRule rule : candidates) {
            if (rule.tier() == PersistenceTier.STICKY && !includeSticky) {
                skippedSticky.add(rule.id());
                continue;
            }
            registry.removeById(rule.id());
            forgetEvaluationState(rule.id());
            removed.add(rule.id());
            auditRemoval(rule);
        }
        if (!removed.isEmpty()) {
            // One rewrite for the whole batch, not one per rule -- doc/specs/
            // persistence.md "Batch removal" (issue #17)'s precedent.
            safePersist(() -> stateStore.removeAllRules(removed));
        }
        return new RuleResetOutcome(removed, skippedSticky);
    }

    private void auditRemoval(LogRule rule) {
        auditLog.record(new AuditRecord(Instant.now(), principal, source, rule.loggerName(), describe(rule), null,
                rule.reason(), AuditRecord.Action.REVERSION));
    }

    private static String describe(LogRule rule) {
        return rule.id() + " (" + rule.actionName() + ")";
    }

    /**
     * Runs once, at composition-root install time, after {@link
     * #registerActionFactory} calls have been made — doc/specs/
     * rule-pipeline-foundation.md "Persistence", mirroring {@link
     * LevelControlService#resumeFromStateStore}'s ordering and "bypasses
     * capability checks deliberately" reasoning exactly: this reinstates
     * state a previous, already-authorized session persisted, not a new
     * operator action.
     *
     * @param now injected so tests can simulate "time has passed since the
     *            rule was persisted" without a real sleep
     */
    public void resumeFromStateStore(Instant now) {
        for (PersistedRule persisted : stateStore.loadAllRules()) {
            try {
                resumeOne(persisted, now);
            } catch (RuntimeException e) {
                System.err.println("[logaperture-state] failed to resume persisted rule '" + persisted.id()
                        + "', skipping it: " + e);
            }
        }
    }

    private void resumeOne(PersistedRule persisted, Instant now) {
        if (persisted.context() != null && !persisted.context().equals(context)) {
            // Belongs to a different context sharing this JVM's one StateStore
            // -- doc/specs/rule-pipeline-foundation.md "Persistence": a rule
            // is attached to exactly one context's RuleService, never
            // broadcast the way a level override is, so resuming it must be
            // scoped the same way (a code-review finding: without this
            // check, every context resumed every other context's rules
            // too). Left untouched in the store for whichever context's own
            // resumeFromStateStore call actually matches it.
            return;
        }
        advanceIdSequencePast(persisted.id());

        if (persisted.tier() == PersistenceTier.FOR && !persisted.expiresAt().isAfter(now)) {
            // Expired while this JVM was down -- never (re-)applied this
            // session, but still recorded as a reversion and dropped from
            // the store, mirroring LevelControlService's identical case.
            auditLog.record(new AuditRecord(now, principal, "resume", persisted.loggerName(),
                    persisted.id() + " (" + persisted.action() + ")", null, "expired while stopped",
                    AuditRecord.Action.REVERSION));
            safePersist(() -> stateStore.removeRule(persisted.id()));
            return;
        }

        RuleFactory factory = actionFactories.get(persisted.action());
        if (factory == null) {
            // No rule type registered for this action (this slice ships none)
            // -- leave the persisted row untouched so a later resume, once
            // #72/#34 register one, picks it up. "Skipped, not failed" --
            // doc/specs/doctor.md's own discipline for an unresolvable fact.
            System.err.println("[logaperture-state] rule '" + persisted.id() + "' (" + persisted.action()
                    + ") not resumed: no rule type registered for that action");
            return;
        }
        LogRule rule = factory.create(persisted.id(), persisted.loggerName(), persisted.matchers(),
                persisted.reason(), persisted.tier(), persisted.expiresAt(), persisted.createdAt(),
                persisted.payload());
        registry.attach(rule);
        auditLog.record(new AuditRecord(now, principal, "resume", persisted.loggerName(), null, describe(rule),
                persisted.reason(), AuditRecord.Action.MUTATION));
    }

    /** So a fresh {@link #attach} after resume never mints an id that collides with a just-resumed one. */
    private void advanceIdSequencePast(String id) {
        if (id == null || !id.startsWith("r")) {
            return; // not one of this service's own ids (e.g. a hand-edited state file) -- leave the sequence alone
        }
        try {
            long n = Long.parseLong(id.substring(1));
            idSequence.updateAndGet(current -> Math.max(current, n + 1));
        } catch (NumberFormatException ignored) {
            // not a plain "r<N>" id -- nothing to advance past
        }
    }

    private PersistedRule toPersisted(LogRule rule) {
        return new PersistedRule(rule.id(), rule.loggerName(), rule.actionName(), rule.matchers(), rule.reason(),
                rule.tier(), rule.expiresAt(), rule.createdAt(), context, rule.persistedPayload());
    }

    /**
     * Guards a {@link StateStore} call against a misbehaving implementation
     * -- same discipline as {@link LevelControlService#safePersist}: the
     * in-memory mutation this call follows already succeeded, so degrading
     * silently to session-only behavior here is a safe direction to fail in.
     */
    private void safePersist(Runnable stateStoreCall) {
        try {
            stateStoreCall.run();
        } catch (RuntimeException e) {
            System.err.println("[logaperture-state] state store operation failed, continuing in-memory only: " + e);
        }
    }

    /** Every attached rule, across every logger — {@code logctl list rules}. */
    @Override
    public List<RuleView> listRules() {
        requireCapability(Capability.VIEW);
        return registry.all().stream().map(rule -> new RuleView(rule, context, hitCount(rule.id()))).toList();
    }

    /** How many candidate events {@code ruleId} has matched so far -- {@code 0} for a rule that's never matched. */
    public long hitCount(String ruleId) {
        LongAdder counter = hitCounters.get(ruleId);
        return counter == null ? 0L : counter.sum();
    }

    public Optional<LogRule> find(String id) {
        requireCapability(Capability.VIEW);
        return registry.findById(id);
    }

    /**
     * The rules that actually apply to {@code loggerName} right now: its
     * own directly-attached rules, plus every ancestor's own rules
     * accumulated on the way up, stopping at the first ancestor (inclusive)
     * whose {@code useParentRules} is {@code false} — doc/specs/
     * rule-pipeline-foundation.md "Logger scope and inheritance". No
     * capability check: this is core evaluation-path logic, not a CLI-facing
     * read.
     */
    public List<LogRule> effectiveRules(String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName");
        List<LogRule> effective = new ArrayList<>();
        String current = loggerName;
        while (current != null) {
            effective.addAll(registry.directRulesFor(current));
            if (!registry.useParentRules(current)) {
                break;
            }
            current = parentOf(current);
        }
        return List.copyOf(effective);
    }

    /** {@code true} by default — modelled on {@code use-parent-handlers}. */
    public boolean useParentRules(String loggerName) {
        return registry.useParentRules(loggerName);
    }

    /**
     * No CLI verb sets this in this slice (doc/specs/
     * rule-pipeline-foundation.md's own build order doesn't include one —
     * #72/#34 are the first callers with a reason to expose a command-line
     * flag for it); exposed as a core primitive so it's tested and ready
     * when one is added.
     */
    public void setUseParentRules(String loggerName, boolean useParentRules) {
        Objects.requireNonNull(loggerName, "loggerName");
        registry.setUseParentRules(loggerName, useParentRules);
    }

    /**
     * The seam an adapter's gate {@code Filter} evaluates every candidate
     * event against -- doc/specs/drop-rule.md "Evaluation".
     */
    public RuleGate gate() {
        return this::evaluateGate;
    }

    /**
     * {@code computeIfAbsent} on the (synchronized) cache, not a separate
     * {@code get}-then-{@code put} pair -- doc/specs/rule-pipeline-foundation.md
     * "Evaluation" requires one verdict per event across every sibling
     * handler's filter, and a plain get/put pair lets two threads racing on
     * the same {@code recordIdentity} (e.g. an async handler dispatching to
     * a delegate on another thread) both miss the cache, both run {@link
     * #computeVerdict}, and double-count a single event (a code-review
     * finding). {@code Collections.synchronizedMap}'s {@code
     * computeIfAbsent} holds its lock for the whole call, including the
     * mapping function, making this atomic.
     */
    private GateVerdict evaluateGate(Object recordIdentity, RuleCandidateEvent event) {
        return decisionCache.computeIfAbsent(recordIdentity, identity -> computeVerdict(event));
    }

    /**
     * First-match-terminal among the event's effective rules (doc/specs/
     * filtering-epic.md "Evaluation") -- only {@link Drop} denies in this
     * slice; any other {@link LogRule} type reaching this loop (none exist
     * yet) is simply not a candidate for a gate-stage verdict.
     */
    private GateVerdict computeVerdict(RuleCandidateEvent event) {
        for (LogRule rule : effectiveRules(event.loggerName())) {
            if (!(rule instanceof Drop drop)) {
                continue;
            }
            if (!RuleMatching.matches(drop.matchers(), event)) {
                continue;
            }
            hitCounters.computeIfAbsent(drop.id(), id -> new LongAdder()).increment();
            if (shouldSampleFull(drop)) {
                pendingSampledCounters.computeIfAbsent(drop.id(), id -> new LongAdder()).increment();
                return GateVerdict.allow();
            }
            pendingSummaryCounters.computeIfAbsent(drop.id(), id -> new LongAdder()).increment();
            return GateVerdict.deny(drop.id());
        }
        return GateVerdict.allow();
    }

    /**
     * doc/specs/drop-rule.md "The keep-one-in-N escape hatch": the very
     * first match is always kept; after that, one kept event per {@link
     * SampleFullPolicy#every()}, resetting the clock from the kept event.
     */
    private boolean shouldSampleFull(Drop drop) {
        SampleFullPolicy policy = drop.sampleFull();
        if (!policy.enabled()) {
            return false;
        }
        AtomicLong next = nextSampleAtNanos.computeIfAbsent(drop.id(), id -> new AtomicLong(Long.MIN_VALUE));
        long now = System.nanoTime();
        long current = next.get();
        boolean due = current == Long.MIN_VALUE || now - current >= 0;
        if (!due) {
            return false;
        }
        // Lost a race with another thread's concurrent sample on this same rule -- treat as "not
        // due" rather than double-sampling; the next matching event re-checks against whichever
        // clock won.
        return next.compareAndSet(current, now + policy.every().toNanos());
    }

    /**
     * The periodic drop-summary line (doc/specs/drop-rule.md "Periodic
     * summary line") -- called from the same sweep tick that already drives
     * expiry and reconfiguration re-application (see {@link
     * AggregateLevelControl#reportDueDropSummaries}), never a background
     * thread of this class's own. Routed through this process's own stderr
     * diagnostic convention (the same one {@link #resumeFromStateStore}
     * already uses), not the target application's own logging pipeline --
     * writing into a framework this agent instruments from inside it is a
     * re-entrancy risk {@code logaperture-bridge}'s {@code Diagnostics}
     * class doc already calls out, and {@code core} does not depend on that
     * module (doc/specs/drop-rule.md "Divergence from prior specs").
     */
    public void reportDueDropSummaries(Instant now) {
        long nowNanos = System.nanoTime();
        for (LogRule rule : registry.all()) {
            if (!(rule instanceof Drop drop)) {
                continue;
            }
            LongAdder pending = pendingSummaryCounters.get(drop.id());
            if (pending == null) {
                continue;
            }
            AtomicLong lastAt = lastSummaryAtNanos.computeIfAbsent(drop.id(), id -> new AtomicLong(Long.MIN_VALUE));
            long last = lastAt.get();
            boolean due = last == Long.MIN_VALUE || nowNanos - last >= SampleFullPolicy.DEFAULT_INTERVAL.toNanos();
            if (!due) {
                continue;
            }
            // sumThenReset(), not a separate sum() followed by reset() -- LongAdder's own
            // documented pattern for exactly this "read the interval's total, then start the next
            // one" use, closing the window where a recordHit landing between a plain sum() and
            // reset() would be silently dropped from every future summary (a code-review finding).
            long suppressed = pending.sumThenReset();
            LongAdder sampledAdder = pendingSampledCounters.get(drop.id());
            long sampled = sampledAdder == null ? 0L : sampledAdder.sumThenReset();
            if (suppressed == 0L && sampled == 0L) {
                continue;
            }
            if (!lastAt.compareAndSet(last, nowNanos)) {
                continue; // lost a race with a concurrent tick -- the counts above are already
                          // consumed either way, and the next due tick reports whatever accrues next
            }
            System.err.println("[logaperture] " + now + " WARN drop summary: " + drop.id() + " ("
                    + drop.loggerName() + ") -- " + suppressed + " suppressed since the last summary, " + sampled
                    + " sampled through");
        }
    }

    /** Drops every per-rule evaluation-time entry keyed by {@code ruleId} -- called whenever a rule is actually removed, so these maps don't grow for the life of the process (a code-review finding). {@link #decisionCache} needs no equivalent: it's keyed by framework record identity, already bounded by {@link WeakHashMap}'s own GC-driven eviction. */
    private void forgetEvaluationState(String ruleId) {
        hitCounters.remove(ruleId);
        nextSampleAtNanos.remove(ruleId);
        pendingSummaryCounters.remove(ruleId);
        pendingSampledCounters.remove(ruleId);
        lastSummaryAtNanos.remove(ruleId);
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }

    /** The dot-segment parent of {@code loggerName} (JBoss LogManager/JUL's own hierarchy convention), or {@code null} past the root logger ({@code ""}). */
    private static String parentOf(String loggerName) {
        if (loggerName.isEmpty()) {
            return null; // already at the root logger
        }
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot < 0 ? "" : loggerName.substring(0, lastDot);
    }
}
