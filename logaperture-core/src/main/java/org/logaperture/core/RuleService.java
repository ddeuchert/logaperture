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
import org.logaperture.api.LogRule;
import org.logaperture.api.PersistedRule;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rule-pipeline engine — framework- and action-agnostic, per
 * doc/specs/rule-pipeline-foundation.md. Owns attachment (id assignment,
 * capability/suppression-floor checks, audit), {@code useParentRules}
 * inheritance resolution, and the compiled {@link RulePlan} every gate
 * filter reads. No concrete rule type is built here — {@link #attach}
 * takes a {@link RuleFactory} so #72's {@code Drop} and #34's {@code Trim}
 * (and this slice's own test double) all go through the identical
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
    private final String principal;
    private final String source;
    private final ProtectedCategories protectedCategories;
    private final AtomicLong idSequence = new AtomicLong(1);
    private final Map<String, RuleFactory> actionFactories = new ConcurrentHashMap<>();

    private volatile RulePlan plan = RulePlan.empty();

    public RuleService(LoggingAdapter adapter, CapabilityPolicy policy, AuditLog auditLog, StateStore stateStore,
            String principal, String source) {
        this(adapter, policy, auditLog, stateStore, principal, source, ProtectedCategories.none());
    }

    public RuleService(LoggingAdapter adapter, CapabilityPolicy policy, AuditLog auditLog, StateStore stateStore,
            String principal, String source, ProtectedCategories protectedCategories) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
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
     * Installs (or re-confirms) the gate-stage rule filter. Called once at
     * context-install time, and again on every reconfiguration re-arm — safe
     * either way, since {@link LoggingAdapter#installRulePipeline} is itself
     * required to be idempotent.
     */
    public void installPipeline() {
        adapter.installRulePipeline(planSource());
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
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(matchers, "matchers");
        Objects.requireNonNull(factory, "factory");
        RuleAttachOptions opts = options == null ? RuleAttachOptions.defaults() : options;

        requireCapability(Capability.RULES_AUTHOR);
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
        LogRule rule = factory.create(id, loggerName, matchers, opts.reason(), opts.tier(), expiresAt, now);

        registry.attach(rule);
        recompilePlan();
        if (rule.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.saveRule(toPersisted(rule)));
        }
        auditLog.record(new AuditRecord(now, principal, source, loggerName, null, describe(rule), opts.reason(),
                AuditRecord.Action.MUTATION));
        return rule;
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
    public Optional<LogRule> resetRule(String id, boolean includeSticky) {
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
        registry.removeById(id);
        recompilePlan();
        safePersist(() -> stateStore.removeRule(id));
        auditRemoval(rule);
        return Optional.of(rule);
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
     */
    @Override
    public RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky) {
        Objects.requireNonNull(loggerName, "loggerName");
        requireCapability(Capability.RULES_AUTHOR);
        return removeMatching(registry.directRulesFor(loggerName), includeSticky);
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
            removed.add(rule.id());
            auditRemoval(rule);
        }
        if (!removed.isEmpty()) {
            recompilePlan();
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
                persisted.reason(), persisted.tier(), persisted.expiresAt(), persisted.createdAt());
        registry.attach(rule);
        recompilePlan();
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

    private static PersistedRule toPersisted(LogRule rule) {
        return new PersistedRule(rule.id(), rule.loggerName(), rule.actionName(), rule.matchers(), rule.reason(),
                rule.tier(), rule.expiresAt(), rule.createdAt());
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

    /** Every attached rule, across every logger — {@code logctl list rules}. Untagged ({@code context == null}); {@link AggregateLevelControl} stamps the real key. */
    @Override
    public List<RuleView> listRules() {
        requireCapability(Capability.VIEW);
        return registry.all().stream().map(rule -> new RuleView(rule, null)).toList();
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

    /** The live, swapped-on-every-mutation plan reader an adapter's gate filter is installed with. */
    public RulePlanSource planSource() {
        return () -> plan;
    }

    private void recompilePlan() {
        plan = new RulePlan(registry.all());
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
