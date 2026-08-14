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

import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.spi.LoggingAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The level-control engine — framework- and agent-agnostic, per
 * doc/specs/level-control.md. Every method follows the same ordering rule:
 * <b>capability check &rarr; adapter mutation &rarr; registry commit &rarr;
 * audit record</b>, so a failure at any step leaves no partial
 * registry/audit state for the logger in question.
 */
public final class LevelControlService implements LevelControlOperations {

    private final LoggingAdapter adapter;
    private final BaselineRegistry baselines;
    private final OverrideRegistry overrides;
    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final String principal;
    private final String source;

    public LevelControlService(
            LoggingAdapter adapter,
            BaselineRegistry baselines,
            OverrideRegistry overrides,
            CapabilityPolicy policy,
            AuditLog auditLog,
            String principal,
            String source) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.baselines = Objects.requireNonNull(baselines, "baselines");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
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
                    override.map(LevelOverride::reason).orElse(null)));
        }
        return List.copyOf(result);
    }

    @Override
    public LevelOverride setLevel(String loggerName, Level level, SetLevelOptions options) {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        SetLevelOptions opts = options == null ? SetLevelOptions.defaults() : options;

        List<String> targets = new ArrayList<>();
        targets.add(loggerName);
        if (opts.includeChildren()) {
            targets.addAll(LoggerHierarchy.descendantsOf(loggerName, adapter.knownLoggerNames()));
        }

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

        LevelOverride primary = null;
        for (String target : targets) {
            LevelOverride applied = applyAndRecordMutation(target, level, opts.reason(), opts.includeChildren());
            if (target.equals(loggerName)) {
                primary = applied;
            }
        }
        return primary;
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
        applyReset(loggerName, existing.get());
    }

    @Override
    public void resetAll() {
        requireCapability(Capability.LEVEL_LOWER);
        for (Map.Entry<String, LevelOverride> entry : overrides.all().entrySet()) {
            applyReset(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Re-applies every currently-tracked override to {@code adapter} --
     * unused by any production wiring in the {@code none} container (no
     * reset event exists there), but exists and is exercised by tests, per
     * doc/specs/level-control.md's re-appliability note. A future
     * container's {@code LoggerContextListener} (or equivalent) calls this
     * on reset, rather than requiring a redesign of the override model.
     */
    public void reapplyActiveOverrides(LoggingAdapter targetAdapter) {
        for (LevelOverride override : overrides.all().values()) {
            OverrideApplier.apply(override, targetAdapter);
        }
    }

    private Capability requiredCapabilityFor(String loggerName, Level newLevel) {
        baselines.captureIfAbsent(loggerName, adapter);
        Level current = adapter.effectiveLevel(loggerName);
        return newLevel.isMoreVerboseThan(current) ? Capability.LEVEL_RAISE : Capability.LEVEL_LOWER;
    }

    private LevelOverride applyAndRecordMutation(String loggerName, Level level, String reason, boolean includeChildren) {
        baselines.captureIfAbsent(loggerName, adapter);
        String previousValue = adapter.effectiveLevel(loggerName).toString();

        LevelOverride override = new LevelOverride(loggerName, level, includeChildren, reason, Instant.now(), source);
        OverrideApplier.apply(override, adapter); // mutation: the point of no return

        overrides.put(override); // commit
        auditLog.record(new AuditRecord(
                Instant.now(), principal, source, loggerName, previousValue, level.toString(), reason,
                AuditRecord.Action.MUTATION)); // audit

        return override;
    }

    private void applyReset(String loggerName, LevelOverride toRevert) {
        String previousValue = toRevert.level().toString();
        Optional<Level> baseline = baselines.get(loggerName); // always captured -- setLevel guarantees it

        adapter.applyLevel(loggerName, baseline.orElse(null)); // mutation

        overrides.remove(loggerName); // commit

        String newValue = baseline.map(Level::toString).orElse("<inherited>");
        auditLog.record(new AuditRecord(
                Instant.now(), principal, source, loggerName, previousValue, newValue, null,
                AuditRecord.Action.REVERSION)); // audit
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
