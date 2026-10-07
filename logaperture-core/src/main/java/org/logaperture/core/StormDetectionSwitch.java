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

import org.logaperture.api.PersistenceTier;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.spi.StateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether storm detection is enabled -- doc/specs/storm-detection-toggle.md. One per agent,
 * shared by every context's {@link StormService} (T4), so a context installed later follows the
 * current position. The storm filter stays installed in both positions and reads {@link
 * #isEnabled()} first on every event (T5); disabled, it does nothing else.
 *
 * <p>{@link #set} is the only operator mutation: it needs {@link Capability#DIAGNOSTICS} (T7), and
 * {@link Capability#PERSIST} too for {@code for} or {@code sticky}; it writes one audit record per
 * actual change (T8). A {@code for} or {@code sticky} setting is saved in the state store, {@link
 * #resume}d at the next start ahead of the agent argument (T14), and a {@code for} one switches to
 * the other position when {@link #sweepExpired} finds its time up (T12). Enabling runs every
 * registered listener, which is how each context clears what it tracked and starts a new
 * measurement window (T6).
 */
public final class StormDetectionSwitch {

    /** The audit target name (T8): {@code switch=storm-detection}. */
    public static final String AUDIT_NAME = "storm-detection";

    private final boolean startedEnabled;
    private final CapabilityPolicy policy;
    private final AuditLog auditLog;
    private final String principal;
    private final StateStore stateStore;
    private final List<Runnable> enableListeners = new CopyOnWriteArrayList<>();

    private volatile boolean enabled;
    /** The last runtime change, or {@code null} while the switch is still in its starting position. */
    private Instant changedAt;
    private PersistenceTier tier = PersistenceTier.SESSION;
    /** When a {@code FOR} setting switches over; {@code null} for any other tier. */
    private Instant expiresAt;

    /** Nothing saved: every setting lasts until the JVM stops, as {@code session} does. */
    public StormDetectionSwitch(boolean startedEnabled, CapabilityPolicy policy, AuditLog auditLog, String principal) {
        this(startedEnabled, policy, auditLog, principal, StateStore.noOp());
    }

    /**
     * @param startedEnabled the agent argument's starting position ({@code --storm-detection=on|off},
     *                       off when absent -- T1, T2)
     * @param stateStore     where a {@code for} or {@code sticky} setting is saved (slice 2)
     */
    public StormDetectionSwitch(boolean startedEnabled, CapabilityPolicy policy, AuditLog auditLog, String principal,
            StateStore stateStore) {
        this.startedEnabled = startedEnabled;
        this.enabled = startedEnabled;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    /**
     * Enabled, with no capability policy, audit sink or state store of its own -- for a {@link
     * StormService} or container built without an agent-supplied switch (tests, and the paths that
     * predate it).
     */
    public static StormDetectionSwitch alwaysEnabled() {
        return new StormDetectionSwitch(true, CapabilityPolicy.allowAll(), record -> { }, "unknown");
    }

    /** Read on every event by the storm filter: a single volatile read. */
    public boolean isEnabled() {
        return enabled;
    }

    public boolean startedEnabled() {
        return startedEnabled;
    }

    /**
     * The current position, its tier, and when it last changed at runtime.
     *
     * @throws CapabilityDeniedException without {@link Capability#VIEW}
     */
    public State state() {
        if (!policy.isGranted(Capability.VIEW)) {
            throw new CapabilityDeniedException(Capability.VIEW);
        }
        return current();
    }

    /** {@link #state()} without the capability check -- for the agent's own startup banner. */
    public synchronized State current() {
        return new State(enabled, startedEnabled, changedAt, tier, expiresAt);
    }

    /** {@link #set(boolean, String, PersistenceTier, Duration)} for this run of the JVM ({@code session}). */
    public Change set(boolean enable, String reason) {
        return set(enable, reason, PersistenceTier.SESSION, null);
    }

    /**
     * Sets the switch to {@code enable}, whatever its current position (T10), replacing the current
     * setting and its tier (T13). A request for the position and tier it already has changes
     * nothing and writes no audit record (T8); a {@code for} request always sets a new deadline.
     *
     * @param tier        {@code SESSION}, {@code FOR} or {@code STICKY}
     * @param forDuration how long a {@code FOR} setting lasts before switching over; ignored otherwise
     * @return the outcome: the new position and tier, the position before, and whether anything changed
     * @throws CapabilityDeniedException without {@link Capability#DIAGNOSTICS}, or without {@link
     *                                   Capability#PERSIST} for {@code FOR}/{@code STICKY}
     */
    public Change set(boolean enable, String reason, PersistenceTier tier, Duration forDuration) {
        Objects.requireNonNull(tier, "tier");
        if (tier == PersistenceTier.FOR && (forDuration == null || forDuration.isNegative() || forDuration.isZero())) {
            throw new IllegalArgumentException("'for' needs a positive duration");
        }
        // Checked before taking the lock: the policy is caller-supplied code.
        if (!policy.isGranted(Capability.DIAGNOSTICS)) {
            throw new CapabilityDeniedException(Capability.DIAGNOSTICS);
        }
        if (tier != PersistenceTier.SESSION && !policy.isGranted(Capability.PERSIST)) {
            throw new CapabilityDeniedException(Capability.PERSIST);
        }
        Instant now = Instant.now();
        boolean previous;
        synchronized (this) {
            previous = enabled;
            if (previous == enable && this.tier == tier && tier != PersistenceTier.FOR) {
                return new Change(enable, previous, false, changedAt, startedEnabled, tier, expiresAt);
            }
            changedAt = now;
            this.tier = tier;
            expiresAt = tier == PersistenceTier.FOR ? now.plus(forDuration) : null;
            if (tier == PersistenceTier.SESSION) {
                stateStore.removeStormDetection();
            } else {
                stateStore.saveStormDetection(new StormDetectionSetting(enable, tier, expiresAt, reason, now, "jmx"));
            }
            moveTo(enable);
        }
        auditLog.record(new AuditRecord(now, principal, "jmx", AUDIT_NAME, position(previous), position(enable),
                reason, AuditRecord.Action.MUTATION, null, AuditRecord.Target.SWITCH));
        return new Change(enable, previous, true, now, startedEnabled, tier, expiresAt);
    }

    /**
     * A {@code for} setting whose time is up switches to the other position (T12), now a {@code
     * session} setting, and its saved entry is removed. Run from the container's periodic sweep,
     * so it lands within one sweep interval of the deadline.
     */
    public void sweepExpired(Instant now) {
        boolean previous;
        synchronized (this) {
            if (tier != PersistenceTier.FOR || expiresAt.isAfter(now)) {
                return;
            }
            previous = enabled;
            changedAt = now;
            tier = PersistenceTier.SESSION;
            expiresAt = null;
            stateStore.removeStormDetection();
            moveTo(!previous);
        }
        auditLog.record(new AuditRecord(now, principal, "expiry-sweep", AUDIT_NAME, position(previous),
                position(!previous), null, AuditRecord.Action.REVERSION, null, AuditRecord.Target.SWITCH));
    }

    /**
     * Applies the saved setting, if any, ahead of the agent argument (T14) -- once, before any
     * context installs, so nothing observes the starting position first ("Restart"). A {@code for}
     * setting whose deadline passed while the JVM was down isn't applied: its switch-over is audited
     * and the entry removed, and the agent argument decides. Never throws: an unreadable saved
     * setting leaves the agent argument in charge.
     */
    public void resume(Instant now) {
        Optional<StormDetectionSetting> saved;
        try {
            saved = stateStore.loadStormDetection();
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to read the saved storm-detection setting, starting from the agent argument", e);
            return;
        }
        if (saved.isEmpty()) {
            return;
        }
        StormDetectionSetting setting = saved.get();
        if (setting.tier() == PersistenceTier.FOR && !setting.expiresAt().isAfter(now)) {
            stateStore.removeStormDetection();
            auditLog.record(new AuditRecord(now, principal, "resume", AUDIT_NAME, position(setting.enabled()),
                    position(startedEnabled), "the 'for' setting expired while the JVM was stopped",
                    AuditRecord.Action.REVERSION, null, AuditRecord.Target.SWITCH));
            return;
        }
        boolean previous;
        synchronized (this) {
            previous = enabled;
            enabled = setting.enabled();
            changedAt = setting.appliedAt();
            tier = setting.tier();
            expiresAt = setting.expiresAt();
        }
        auditLog.record(new AuditRecord(now, principal, "resume", AUDIT_NAME, position(previous),
                position(setting.enabled()), setting.reason(), AuditRecord.Action.MUTATION, null,
                AuditRecord.Target.SWITCH));
    }

    /** Run, on the calling thread and still disabled, each time the switch is about to turn on (T6). */
    void onEnable(Runnable listener) {
        enableListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Sets the position, under this switch's lock. Turning on, every context starts its new window
     * (T6) <em>before</em> the volatile write that the storm filter reads, so no event is counted
     * into a window about to be discarded and no report sweeps the frozen storms as live. Each
     * listener is isolated: one context's failure doesn't leave the others with stale state.
     */
    private void moveTo(boolean enable) {
        if (enable && !enabled) {
            for (Runnable listener : enableListeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    Diagnostics.warn("failed to start a new storm measurement window for a context", e);
                }
            }
        }
        enabled = enable;
    }

    private static String position(boolean enabled) {
        return enabled ? "on" : "off";
    }

    /**
     * @param changedAt the last runtime change, or {@code null} while still in the starting position
     * @param tier      {@code SESSION} unless a {@code for} or {@code sticky} setting is in effect
     * @param expiresAt when a {@code FOR} setting switches over; {@code null} otherwise
     */
    public record State(boolean enabled, boolean startedEnabled, Instant changedAt, PersistenceTier tier,
            Instant expiresAt) {

        /** A {@code session} setting. */
        public State(boolean enabled, boolean startedEnabled, Instant changedAt) {
            this(enabled, startedEnabled, changedAt, PersistenceTier.SESSION, null);
        }
    }

    /**
     * @param changed        {@code false} when the switch already had this position and tier
     * @param changedAt      the change just made, or the last one when nothing changed ({@code null} if none)
     * @param startedEnabled the starting position, from the agent argument
     * @param tier           the tier now in effect
     * @param expiresAt      when a {@code FOR} setting switches over; {@code null} otherwise
     */
    public record Change(boolean enabled, boolean previous, boolean changed, Instant changedAt,
            boolean startedEnabled, PersistenceTier tier, Instant expiresAt) {
    }
}
