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
import org.logaperture.api.RuleChange;
import org.logaperture.api.RuleExpression;
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.Trim;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.time.Duration;
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

    /**
     * The vendor defaults file's rules as the file defines them, by id -- each rule's baseline
     * (doc/specs/alter-rule.md "Vendor rules"). Every id is prefixed {@code vendor:}, so never
     * colliding with an {@code r<N>} id. The registry holds either this exact object (unaltered)
     * or an alteration under the same id.
     */
    private final Map<String, LogRule> vendorBaselines = new ConcurrentHashMap<>();

    /** Vendor rules switched off until restart with {@code reset … --to-native} (A8) -- detached, still listed. */
    private final Map<String, LogRule> toNativeVendorRules = new ConcurrentHashMap<>();

    /** Set by {@link #attachVendorRules}; see its {@code vendorDefaultsLoaded} parameter. */
    private volatile boolean vendorDefaultsLoaded;

    /** doc/specs/alter-rule.md A7: a vendor rule's first alteration with no tier -- {@code set logger}'s default. */
    static final Duration VENDOR_ALTERATION_DEFAULT_FOR = Duration.ofHours(4);

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
     * Registers {@link Trim}'s own resume factory under its {@code
     * actionName()} ({@code "trim"}) — doc/specs/trim-rule.md, mirroring
     * {@link #registerDropSupport} exactly.
     */
    public void registerTrimSupport() {
        registerActionFactory("trim", TrimFactories.resume()); // matches Trim#actionName()'s literal
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
     * Installs (or re-confirms) the render-stage trim seam — doc/specs/
     * trim-rule.md "Evaluation". Called once at context-install time, and
     * again on every reconfiguration re-arm, same idempotency contract as
     * {@link #installPipeline}. Must run <em>before</em> {@link
     * TopService#startMeasuring} on every call site (doc/specs/trim-rule.md
     * "Interaction with top": trim's formatter wrap installs inside {@code
     * top}'s, so {@code top} measures the bytes actually written).
     */
    public void installTrimRendering() {
        adapter.installTrimRendering(gate());
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
     * {@code logctl add rule trim} -- doc/specs/trim-rule.md "Command
     * surface". Requires {@link Capability#SUPPRESS} in addition to {@link
     * Capability#RULES_AUTHOR}, same capability shape as {@link
     * #addRuleDrop}.
     */
    @Override
    public RuleView addRuleTrim(String loggerName, CompiledMatchers matchers, RuleAttachOptions options, int frames,
            boolean collapseCauses) {
        LogRule rule = attach(loggerName, matchers, options, TrimFactories.attach(frames, collapseCauses),
                Capability.SUPPRESS);
        return new RuleView(rule, context, hitCount(rule.id()));
    }

    /**
     * Attaches the vendor defaults file's rules -- doc/specs/vendor-defaults.md "Rules". Runs
     * once, at composition-root install time, before {@link #resumeFromStateStore}. Built through
     * the same {@link DropFactories}/{@link TrimFactories} a live {@code add rule} uses; never
     * written to the state file (the vendor file is their persistence), so their tier is {@code
     * SESSION} internally and they report as vendor rules instead. No capability check
     * (vendor-config-epic.md Decision #11); one {@code "vendor-defaults"} audit record per rule.
     * Each rule as attached here is its <em>baseline</em>: what {@code reset rule} returns it to
     * (doc/specs/alter-rule.md "Vendor rules").
     */
    public void attachVendorRules(List<VendorDefaults.RuleDefault> rules, Instant now) {
        attachVendorRules(rules, true, now);
    }

    /**
     * @param vendorDefaultsLoaded whether the vendor defaults file loaded. When it didn't (not
     *                             configured, or rejected), {@link #resumeFromStateStore} leaves a
     *                             saved vendor-rule alteration in the state file untouched instead
     *                             of dropping it as orphaned (doc/specs/alter-rule.md "Restart", A9)
     */
    public void attachVendorRules(List<VendorDefaults.RuleDefault> rules, boolean vendorDefaultsLoaded, Instant now) {
        this.vendorDefaultsLoaded = vendorDefaultsLoaded;
        for (VendorDefaults.RuleDefault vendor : rules) {
            try {
                RuleFactory factory = switch (vendor.action()) {
                    case "drop" -> DropFactories.attach(vendor.sampleFull());
                    case "trim" -> TrimFactories.attach(vendor.frames(), vendor.collapseCauses());
                    default -> throw new IllegalArgumentException("unknown action '" + vendor.action() + "'");
                };
                if (protectedCategories.isProtected(vendor.loggerName())) {
                    throw new IllegalArgumentException("'" + vendor.loggerName() + "' is a protected category");
                }
                LogRule rule = factory.create(vendor.id(), vendor.loggerName(), vendor.matchers(), vendor.reason(),
                        PersistenceTier.SESSION, null, now, Map.of());
                vendorBaselines.put(rule.id(), rule);
                registry.attach(rule);
                auditLog.record(new AuditRecord(now, principal, VendorDefaults.AUDIT_SOURCE, rule.loggerName(), null,
                        describe(rule), rule.reason(), AuditRecord.Action.MUTATION));
            } catch (RuntimeException e) {
                System.err.println("[logaperture] failed to attach vendor rule '" + vendor.id() + "', skipping it: "
                        + e);
            }
        }
    }

    private boolean isVendorRule(String id) {
        return vendorBaselines.containsKey(id);
    }

    /** A vendor rule currently carrying an {@code alter rule} override rather than its baseline. */
    private boolean isAlteredVendorRule(LogRule rule) {
        LogRule baseline = vendorBaselines.get(rule.id());
        return baseline != null && baseline != rule;
    }

    private RuleView view(LogRule rule) {
        boolean vendor = isVendorRule(rule.id());
        return new RuleView(rule, context, hitCount(rule.id()), vendor ? VendorDefaults.AUDIT_SOURCE : null,
                vendor && toNativeVendorRules.containsKey(rule.id()), isAlteredVendorRule(rule));
    }

    /** Whether this service holds {@code id} at all -- attached, or a vendor rule switched off until restart. */
    public boolean holds(String id) {
        return registry.findById(id).isPresent() || toNativeVendorRules.containsKey(id);
    }

    /**
     * {@code logctl alter rule <id>} -- doc/specs/alter-rule.md. Changes only the parts {@code
     * change} names (A3); the resulting rule must pass {@code add rule}'s checks (A4). An operator
     * rule is changed in place (A6); a vendor rule gets an override on top of its baseline (A7).
     * The swap is atomic, the id is kept, and the hit count starts over only if the definition
     * changed (A5).
     *
     * @param tier      the new lifetime, or {@code null} to keep the current one -- except for an
     *                  unaltered vendor rule, whose first alteration defaults to {@code for 4h}
     * @param expiresIn required iff {@code tier} is {@code FOR}
     * @return empty if no rule with this id exists here
     * @throws IllegalArgumentException if the change is empty, doesn't fit the rule's action, would
     *                                  leave a drop with no content matcher, or targets a vendor
     *                                  rule switched off until restart
     */
    @Override
    public Optional<RuleAlteration> alterRule(String id, RuleChange change, PersistenceTier tier, Duration expiresIn) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(change, "change");
        if (change.isEmpty() && tier == null) {
            throw new IllegalArgumentException("nothing to alter -- name at least one part of the rule, a tier, "
                    + "or --reason.");
        }
        if (tier == PersistenceTier.FOR) {
            if (expiresIn == null || expiresIn.isZero() || expiresIn.isNegative()) {
                throw new IllegalArgumentException("tier FOR requires a positive duration");
            }
        } else if (expiresIn != null) {
            throw new IllegalArgumentException("a duration applies only to tier FOR");
        }
        requireCapability(Capability.RULES_AUTHOR);
        requireCapability(Capability.SUPPRESS);
        if (toNativeVendorRules.containsKey(id)) {
            throw new IllegalArgumentException(id + " is switched off until restart -- 'reset rule " + id
                    + "' switches it back on first.");
        }
        Optional<LogRule> found = registry.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LogRule current = found.get();
        boolean firstVendorAlteration = isVendorRule(id) && !isAlteredVendorRule(current);

        Instant now = Instant.now();
        PersistenceTier newTier;
        Instant newExpiresAt;
        if (tier != null) {
            newTier = tier;
            newExpiresAt = tier == PersistenceTier.FOR ? now.plus(expiresIn) : null;
        } else if (firstVendorAlteration) {
            newTier = PersistenceTier.FOR;
            newExpiresAt = now.plus(VENDOR_ALTERATION_DEFAULT_FOR);
        } else {
            newTier = current.tier();
            newExpiresAt = current.expiresAt();
        }
        String newReason = change.reason() != null ? change.reason() : current.reason();
        Instant createdAt = firstVendorAlteration ? now : current.createdAt();
        LogRule candidate = rebuild(current, change, newReason, newTier, newExpiresAt, createdAt);

        boolean definitionChanged = !sameDefinition(current, candidate);
        boolean reasonChanged = !Objects.equals(current.reason(), candidate.reason());
        boolean lifetimeChanged = tier != null
                && (tier == PersistenceTier.FOR || tier != current.tier() || firstVendorAlteration);
        RuleView before = view(current);
        if (!definitionChanged && !reasonChanged && !lifetimeChanged) {
            return Optional.of(new RuleAlteration(before, before, false));
        }
        if (candidate.tier() != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }

        String previousValue = describeFull(current, true);
        if (!registry.replaceIfCurrent(current, candidate)) {
            throw new IllegalStateException("rule " + id + " changed while it was being altered -- try again.");
        }
        if (definitionChanged) {
            forgetEvaluationState(id);
        }
        if (candidate.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.saveRule(toPersisted(candidate)));
        } else if (current.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.removeRule(id));
        }
        auditLog.record(new AuditRecord(now, principal, source, current.loggerName(), previousValue,
                describeFull(candidate, false), newReason, AuditRecord.Action.MUTATION));
        return Optional.of(new RuleAlteration(before, view(candidate), true));
    }

    /** {@code current} with {@code change} applied -- same id, logger and action (A2). */
    private static LogRule rebuild(LogRule current, RuleChange change, String reason, PersistenceTier tier,
            Instant expiresAt, Instant createdAt) {
        CompiledMatchers matchers = change.applyTo(current.matchers());
        if (current instanceof Drop drop) {
            if (change.touchesTrimOnly()) {
                throw new IllegalArgumentException("--frames / --collapse-causes apply only to a trim rule; "
                        + current.id() + " is a drop.");
            }
            if (matchers.messageContains() == null && matchers.throwableType() == null
                    && matchers.throwableMessageContains() == null) {
                throw new IllegalArgumentException(current.id() + " would be left with no content matcher, and a "
                        + "drop needs one -- 'reset rule " + current.id() + "' removes the rule, 'set logger' "
                        + "changes the logger's level.");
            }
            SampleFullPolicy sampleFull = drop.sampleFull();
            if (change.sampleFull() != null) {
                // --no-sample-full keeps the interval, so a later --sample-full without one... has one.
                sampleFull = change.sampleFull().enabled() ? change.sampleFull()
                        : new SampleFullPolicy(false, drop.sampleFull().every());
            }
            return new Drop(current.id(), current.loggerName(), matchers, reason, tier, expiresAt, createdAt,
                    sampleFull);
        }
        if (current instanceof Trim trim) {
            if (change.touchesDropOnly()) {
                throw new IllegalArgumentException("--sample-full / --no-sample-full apply only to a drop rule; "
                        + current.id() + " is a trim.");
            }
            int frames = change.frames() != null ? change.frames() : trim.frames();
            boolean collapseCauses = change.collapseCauses() != null ? change.collapseCauses() : trim.collapseCauses();
            return new Trim(current.id(), current.loggerName(), matchers, reason, tier, expiresAt, createdAt, frames,
                    collapseCauses);
        }
        throw new IllegalArgumentException("rule " + current.id() + " (" + current.actionName()
                + ") can't be altered.");
    }

    /** Same action, matchers and action options -- what the hit count and sampling state describe (A5). */
    private static boolean sameDefinition(LogRule a, LogRule b) {
        if (a.getClass() != b.getClass() || !a.matchers().equals(b.matchers())) {
            return false;
        }
        if (a instanceof Drop da && b instanceof Drop db) {
            return da.sampleFull().equals(db.sampleFull());
        }
        if (a instanceof Trim ta && b instanceof Trim tb) {
            return ta.frames() == tb.frames() && ta.collapseCauses() == tb.collapseCauses();
        }
        return true;
    }

    /**
     * {@code reset rule <id>} — a single named id is "one specific thing":
     * refuses outright if it's {@code STICKY} and {@code includeSticky}
     * wasn't passed, mirroring {@code reset-command-surface.md}'s Decision
     * #1 exactly. Empty if there was nothing to reset (a no-op, not an
     * error, same "unknown target" discipline every other reset already
     * follows).
     *
     * <p>A vendor rule is reset, never removed (doc/specs/alter-rule.md "Reset", A8): back to the
     * vendor's definition (and back on, if it was switched off), or with {@code toNative} switched
     * off until restart. On an operator rule {@code toNative} is the same as a plain reset.
     */
    @Override
    public Optional<RuleView> resetRule(String id, boolean includeSticky, boolean toNative) {
        Objects.requireNonNull(id, "id");
        requireCapability(Capability.RULES_AUTHOR);
        if (isVendorRule(id)) {
            return resetVendorRule(id, includeSticky, toNative);
        }
        Optional<LogRule> existing = registry.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty(); // unknown
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

    private Optional<RuleView> resetVendorRule(String id, boolean includeSticky, boolean toNative) {
        LogRule baseline = vendorBaselines.get(id);
        if (toNativeVendorRules.containsKey(id)) {
            if (toNative) {
                return Optional.empty(); // already off
            }
            switchVendorRuleBackOn(baseline);
            return Optional.of(view(baseline));
        }
        Optional<LogRule> existing = registry.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        LogRule current = existing.get();
        boolean altered = current != baseline;
        if (altered && current.tier() == PersistenceTier.STICKY && !includeSticky) {
            throw new IllegalArgumentException(id + " has a STICKY alteration -- reset refused without "
                    + "--include-sticky.");
        }
        if (toNative) {
            switchVendorRuleOff(current, baseline);
        } else if (altered) {
            restoreVendorDefinition(current, baseline, source, null);
        } else {
            return Optional.empty(); // already at its baseline
        }
        if (current.tier() != PersistenceTier.SESSION) {
            safePersist(() -> stateStore.removeRule(id));
        }
        return Optional.of(view(baseline));
    }

    /** {@code reset … --to-native} on a vendor rule: detached until restart, still listed. The caller removes a persisted alteration's row. */
    private void switchVendorRuleOff(LogRule current, LogRule baseline) {
        if (!registry.removeIfCurrent(current)) {
            throw new IllegalStateException("rule " + current.id() + " changed while it was being reset -- try again.");
        }
        toNativeVendorRules.put(baseline.id(), baseline);
        if (!sameDefinition(current, baseline)) {
            forgetEvaluationState(current.id());
        }
        // Otherwise evaluation state is left alone: the vendor set is small and fixed, and keeping the hit
        // counter lets `list rules` show what the rule had matched before it was switched off.
        auditLog.record(new AuditRecord(Instant.now(), principal, source, current.loggerName(),
                describeFull(current, true), null, "vendor default switched off until restart",
                AuditRecord.Action.REVERSION));
    }

    /** A plain reset of a vendor rule switched off with {@code --to-native}: back on, with the vendor's definition. */
    private void switchVendorRuleBackOn(LogRule baseline) {
        if (toNativeVendorRules.remove(baseline.id(), baseline)) {
            registry.attach(baseline);
            auditLog.record(new AuditRecord(Instant.now(), principal, source, baseline.loggerName(), null,
                    describeFull(baseline, false), "vendor default switched back on", AuditRecord.Action.MUTATION));
        }
    }

    /** Replaces an alteration with the vendor's definition. The caller removes a persisted alteration's row. */
    private void restoreVendorDefinition(LogRule current, LogRule baseline, String auditSource, String auditReason) {
        if (!registry.replaceIfCurrent(current, baseline)) {
            throw new IllegalStateException("rule " + current.id() + " changed while it was being reset -- try again.");
        }
        if (!sameDefinition(current, baseline)) {
            forgetEvaluationState(current.id());
        }
        auditLog.record(new AuditRecord(Instant.now(), principal, auditSource, current.loggerName(),
                describeFull(current, true), describeFull(baseline, false), auditReason,
                AuditRecord.Action.REVERSION));
    }

    /** {@code reset rules} — bulk, skip-and-report shape (mirrors {@code reset loggers}). */
    @Override
    public RuleResetOutcome resetAllRules(boolean includeSticky, boolean toNative) {
        requireCapability(Capability.RULES_AUTHOR);
        return resetMatching(registry.all(), List.copyOf(toNativeVendorRules.values()), includeSticky, toNative);
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
     * only when there is actually something to reset; a target with no
     * attached rules costs nothing to authorize (a code-review finding).
     */
    @Override
    public RuleResetOutcome resetRulesForLogger(String loggerName, boolean includeSticky, boolean toNative) {
        Objects.requireNonNull(loggerName, "loggerName");
        List<LogRule> candidates = registry.directRulesFor(loggerName);
        List<LogRule> switchedOff = toNativeVendorRules.values().stream()
                .filter(rule -> rule.loggerName().equals(loggerName))
                .toList();
        boolean anythingToReset = candidates.stream().anyMatch(rule -> !isVendorRule(rule.id())
                || isAlteredVendorRule(rule) || toNative) || (!switchedOff.isEmpty() && !toNative);
        if (!anythingToReset) {
            return RuleResetOutcome.nothingReset();
        }
        requireCapability(Capability.RULES_AUTHOR);
        return resetMatching(candidates, switchedOff, includeSticky, toNative);
    }

    /**
     * @param switchedOff vendor rules in scope that are switched off until restart -- a plain reset
     *                    switches them back on (A8)
     */
    private RuleResetOutcome resetMatching(List<LogRule> candidates, List<LogRule> switchedOff,
            boolean includeSticky, boolean toNative) {
        List<String> removed = new ArrayList<>();
        List<String> skippedSticky = new ArrayList<>();
        List<String> vendorReset = new ArrayList<>();
        List<String> persistedRemovals = new ArrayList<>();
        for (LogRule rule : candidates) {
            if (rule.tier() == PersistenceTier.STICKY && !includeSticky) {
                skippedSticky.add(rule.id()); // an operator rule, or a vendor rule's sticky alteration
                continue;
            }
            if (isVendorRule(rule.id())) {
                LogRule baseline = vendorBaselines.get(rule.id());
                if (toNative) {
                    switchVendorRuleOff(rule, baseline);
                } else if (rule != baseline) {
                    restoreVendorDefinition(rule, baseline, source, null);
                } else {
                    continue; // already at its baseline
                }
                vendorReset.add(rule.id());
            } else {
                registry.removeById(rule.id());
                forgetEvaluationState(rule.id());
                removed.add(rule.id());
                auditRemoval(rule);
            }
            if (rule.tier() != PersistenceTier.SESSION) {
                persistedRemovals.add(rule.id());
            }
        }
        if (!toNative) {
            for (LogRule baseline : switchedOff) {
                switchVendorRuleBackOn(baseline);
                vendorReset.add(baseline.id());
            }
        }
        if (!persistedRemovals.isEmpty()) {
            // One rewrite for the whole batch, not one per rule -- doc/specs/
            // persistence.md "Batch removal" (issue #17)'s precedent.
            safePersist(() -> stateStore.removeAllRules(persistedRemovals));
        }
        return new RuleResetOutcome(removed, skippedSticky, vendorReset);
    }

    /**
     * Removes every {@code FOR} rule whose {@code expiresAt} is at or before {@code now}, drops
     * it from the state store, and audits a {@code REVERSION} with source {@code expiry-sweep}
     * -- doc/specs/rule-pipeline-foundation.md "Persistence" (issue #95: until this existed, a
     * {@code for <duration>} rule kept acting until the next restart). The rule counterpart of
     * {@link LevelControlService#sweepExpiredOverrides}; scheduled by the composition root's
     * sweep tick via {@link AggregateLevelControl#sweepExpiredOverrides}. Vendor rules are
     * {@code SESSION} internally and never expire here.
     */
    public void sweepExpiredRules(Instant now) {
        List<String> expired = new ArrayList<>();
        for (LogRule rule : registry.all()) {
            if (rule.tier() != PersistenceTier.FOR || rule.expiresAt() == null || rule.expiresAt().isAfter(now)) {
                continue;
            }
            if (isVendorRule(rule.id())) {
                // doc/specs/alter-rule.md A7: an expired vendor-rule alteration returns the rule to the vendor's
                // definition rather than removing it.
                try {
                    restoreVendorDefinition(rule, vendorBaselines.get(rule.id()), "expiry-sweep", "expired");
                } catch (IllegalStateException concurrentlyChanged) {
                    continue; // reset or altered again since the snapshot -- nothing expired
                }
                expired.add(rule.id());
            } else if (registry.removeIfCurrent(rule)) {
                forgetEvaluationState(rule.id());
                expired.add(rule.id());
                auditLog.record(new AuditRecord(now, principal, "expiry-sweep", rule.loggerName(), describe(rule),
                        null, "expired", AuditRecord.Action.REVERSION));
            }
        }
        if (!expired.isEmpty()) {
            safePersist(() -> stateStore.removeAllRules(expired)); // one rewrite per tick (issue #17)
        }
    }

    private void auditRemoval(LogRule rule) {
        auditLog.record(new AuditRecord(Instant.now(), principal, source, rule.loggerName(), describe(rule), null,
                rule.reason(), AuditRecord.Action.REVERSION));
    }

    private static String describe(LogRule rule) {
        return rule.id() + " (" + rule.actionName() + ")";
    }

    /**
     * An audit value naming the whole definition -- doc/specs/alter-rule.md "Audit": id, action,
     * the {@code list rules --verbose} expression and the lifetime, plus the hit count so far when
     * {@code withHits} (the count an alteration is about to start over).
     */
    private String describeFull(LogRule rule, boolean withHits) {
        String lifetime = vendorBaselines.get(rule.id()) == rule ? VendorDefaults.AUDIT_SOURCE
                : rule.tier().name() + (rule.expiresAt() == null ? "" : " until " + rule.expiresAt());
        return describe(rule) + " " + RuleExpression.of(rule) + " [" + lifetime
                + (withHits ? ", " + hitCount(rule.id()) + " hits" : "") + "]";
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

        if (persisted.id().startsWith(VendorDefaults.RULE_ID_PREFIX)) {
            resumeVendorAlteration(persisted, now);
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

    /**
     * A saved {@code alter rule} override of a vendor rule -- doc/specs/alter-rule.md "Restart"
     * (A9). Applied over the vendor rule {@link #attachVendorRules} just attached. Dropped (row
     * removed, audited, warned) when the vendor defaults file loaded but no longer has a rule with
     * this id, action and logger; left untouched in the store when the file didn't load at all, so
     * a broken or missing file on one start doesn't destroy the alterations.
     */
    private void resumeVendorAlteration(PersistedRule persisted, Instant now) {
        if (!vendorDefaultsLoaded) {
            System.err.println("[logaperture-state] alteration of vendor rule '" + persisted.id()
                    + "' not applied: the vendor defaults file isn't loaded; kept in the state file");
            return;
        }
        LogRule baseline = vendorBaselines.get(persisted.id());
        if (baseline == null || !baseline.actionName().equals(persisted.action())
                || !baseline.loggerName().equals(persisted.loggerName())) {
            System.err.println("[logaperture-state] WARN dropping the saved alteration of vendor rule '"
                    + persisted.id() + "': the vendor defaults file no longer has that " + persisted.action()
                    + " rule on " + persisted.loggerName());
            safePersist(() -> stateStore.removeRule(persisted.id()));
            auditLog.record(new AuditRecord(now, principal, "resume", persisted.loggerName(),
                    persisted.id() + " (" + persisted.action() + ")", null,
                    "vendor rule no longer in the vendor defaults file", AuditRecord.Action.REVERSION));
            return;
        }
        RuleFactory factory = actionFactories.get(persisted.action());
        if (factory == null) {
            System.err.println("[logaperture-state] alteration of vendor rule '" + persisted.id() + "' ("
                    + persisted.action() + ") not applied: no rule type registered for that action");
            return;
        }
        LogRule alteration = factory.create(persisted.id(), persisted.loggerName(), persisted.matchers(),
                persisted.reason(), persisted.tier(), persisted.expiresAt(), persisted.createdAt(),
                persisted.payload());
        if (registry.replaceIfCurrent(baseline, alteration)) {
            auditLog.record(new AuditRecord(now, principal, "resume", persisted.loggerName(),
                    describeFull(baseline, false), describeFull(alteration, false), persisted.reason(),
                    AuditRecord.Action.MUTATION));
        }
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
        List<RuleView> views = new ArrayList<>();
        for (LogRule rule : registry.all()) {
            views.add(view(rule));
        }
        for (LogRule switchedOff : toNativeVendorRules.values()) {
            views.add(view(switchedOff));
        }
        return List.copyOf(views);
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
     * First-match-terminal {@link Drop} among the event's effective rules
     * (doc/specs/filtering-epic.md "Evaluation"); if none denies, the
     * <em>most restrictive</em> matching {@link Trim} (fewest {@code
     * frames}) is computed and carried on the verdict for the render stage
     * -- doc/specs/trim-rule.md "Evaluation": "drop first and terminal ...
     * trim runs on survivors, most restrictive trim wins". Any other {@link
     * LogRule} type reaching this loop is simply not a candidate for a
     * gate-stage verdict.
     */
    private GateVerdict computeVerdict(RuleCandidateEvent event) {
        List<LogRule> effective = effectiveRules(event.loggerName());
        for (LogRule rule : effective) {
            if (!(rule instanceof Drop drop)) {
                continue;
            }
            if (!RuleMatching.matches(drop.matchers(), event)) {
                continue;
            }
            hitCounters.computeIfAbsent(drop.id(), id -> new LongAdder()).increment();
            if (shouldSampleFull(drop)) {
                pendingSampledCounters.computeIfAbsent(drop.id(), id -> new LongAdder()).increment();
                break; // sampled through -- fall through to the trim pass below, same as any other survivor
            }
            pendingSummaryCounters.computeIfAbsent(drop.id(), id -> new LongAdder()).increment();
            return GateVerdict.deny(drop.id());
        }
        return GateVerdict.allowWithTrim(mostRestrictiveTrim(effective, event));
    }

    /**
     * The matching {@link Trim} with the fewest {@code frames} among {@code
     * effective} -- doc/specs/filtering-epic.md "Evaluation": "among several
     * trims, the most restrictive wins (fewest frames)". {@code null} if
     * none matches, so {@link GateVerdict#allowWithTrim} carries a {@code
     * null} trim exactly like {@link GateVerdict#allow()} would.
     *
     * <p>An event with no throwable at all is never a candidate, regardless
     * of matchers -- there is nothing for the render stage to trim (a bare
     * level-bounded {@code Trim} matches every qualifying event on its
     * logger, throwable or not), so this returns before touching a rule's
     * hit counter: a code-review finding against the first cut of this
     * method, which counted a "hit" for events {@code JulTrimFormatter}
     * would never actually apply the decision to, inflating {@code list
     * rules}' hit count with ordinary non-exception log traffic.
     */
    private TrimDecision mostRestrictiveTrim(List<LogRule> effective, RuleCandidateEvent event) {
        if (event.thrown() == null) {
            return null;
        }
        Trim winner = null;
        for (LogRule rule : effective) {
            if (!(rule instanceof Trim trim)) {
                continue;
            }
            if (!RuleMatching.matches(trim.matchers(), event)) {
                continue;
            }
            if (winner == null || trim.frames() < winner.frames()) {
                winner = trim;
            }
        }
        if (winner == null) {
            return null;
        }
        hitCounters.computeIfAbsent(winner.id(), id -> new LongAdder()).increment();
        return new TrimDecision(winner.id(), winner.frames(), winner.collapseCauses());
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
