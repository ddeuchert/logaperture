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
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleResetOutcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 */
public final class RuleService {

    private final RuleRegistry registry = new RuleRegistry();
    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final String principal;
    private final String source;
    private final ProtectedCategories protectedCategories;
    private final AtomicLong idSequence = new AtomicLong(1);

    private volatile RulePlan plan = RulePlan.empty();

    public RuleService(CapabilityPolicy policy, AuditLog auditLog, String principal, String source) {
        this(policy, auditLog, principal, source, ProtectedCategories.none());
    }

    public RuleService(CapabilityPolicy policy, AuditLog auditLog, String principal, String source,
            ProtectedCategories protectedCategories) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.source = Objects.requireNonNull(source, "source");
        this.protectedCategories = Objects.requireNonNull(protectedCategories, "protectedCategories");
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
        auditRemoval(rule);
        return Optional.of(rule);
    }

    /** {@code reset rules} — bulk, skip-and-report shape (mirrors {@code reset loggers}). */
    public RuleResetOutcome resetAllRules(boolean includeSticky) {
        requireCapability(Capability.RULES_AUTHOR);
        return removeMatching(registry.all(), includeSticky);
    }

    /**
     * The rules attached <em>directly</em> to {@code loggerName} (not its
     * descendants' own separately-attached rules) — {@code reset logger X}'s
     * side effect, doc/specs/rule-pipeline-foundation.md "Command surface".
     */
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

    /** Every attached rule, across every logger — {@code logctl list rules}. */
    public List<LogRule> list() {
        requireCapability(Capability.VIEW);
        return registry.all();
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
