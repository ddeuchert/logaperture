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

import org.logaperture.api.HandlerRef;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code DEFAULT_HANDLERS}'s membership — one per logging context, mirroring
 * {@link HandlerBaselineRegistry}'s per-context shape (doc/specs/
 * handler-floor-control.md "Default handler group", issue #28). Holds an
 * <em>optional</em> membership set, not an always-present one:
 *
 * <ul>
 * <li><b>No explicit assignment ever made.</b> {@link #members} computes
 *     the answer fresh, every call, via {@link DefaultHandlerSelector} —
 *     nothing is stored, nothing to go stale.</li>
 * <li><b>An explicit assignment exists</b> ({@code logctl set handlers
 *     default ...} ran). {@link #members} returns it as-is, after pruning
 *     any member whose handler no longer resolves — a partial staleness
 *     prunes silently and keeps the survivors; a <em>total</em> staleness
 *     (every member gone) discards the assignment entirely and reverts to
 *     the stateless/rule-driven case, "starting over" rather than picking
 *     a new value and persisting it.</li>
 * </ul>
 *
 * <p>This class only holds the value — persistence (writing/removing the
 * state-file record) and audit (recording an explicit change) are {@link
 * HandlerLevelControlService}'s job, same division of labour {@link
 * HandlerOverrideRegistry} already keeps with its owning service.
 *
 * <p>Between the explicit assignment and the deterministic rule sits the vendor defaults file's
 * {@code defaultHandlers} list (doc/specs/vendor-defaults.md "Default handlers"): explicit
 * &rarr; vendor &rarr; rule. The vendor list is fixed for the JVM's life and never pruned or
 * persisted; its members that don't resolve right now are simply skipped, and if none resolve
 * the rule decides.
 */
public final class DefaultHandlerGroupRegistry {

    /** {@code null} = stateless/rule-driven; non-null = explicit, persisted. */
    private final AtomicReference<Set<HandlerRef>> explicit = new AtomicReference<>();

    /** The vendor defaults file's list; empty when the file doesn't set one. */
    private final List<HandlerRef> vendorMembers;

    public DefaultHandlerGroupRegistry() {
        this(List.of());
    }

    /**
     * @param vendorMembers the vendor defaults file's {@code defaultHandlers}, or empty
     */
    public DefaultHandlerGroupRegistry(List<HandlerRef> vendorMembers) {
        this.vendorMembers = List.copyOf(vendorMembers);
    }

    /** The vendor defaults file's list, before resolution; empty if the file doesn't set one. */
    public List<HandlerRef> vendorMembers() {
        return vendorMembers;
    }

    /**
     * Whether, with no explicit assignment, {@link #members} currently comes from the vendor
     * list -- i.e. at least one of its members resolves against {@code adapter}.
     */
    public boolean vendorMembersInEffect(LoggingAdapter adapter) {
        return explicit.get() == null && !resolvedVendorMembers(adapter).isEmpty();
    }

    private List<HandlerRef> resolvedVendorMembers(LoggingAdapter adapter) {
        if (vendorMembers.isEmpty()) {
            return List.of();
        }
        Set<HandlerRef> reals = Set.copyOf(adapter.realHandlers());
        return vendorMembers.stream().filter(reals::contains).toList();
    }

    private List<HandlerRef> baselineMembers(LoggingAdapter adapter) {
        List<HandlerRef> vendor = resolvedVendorMembers(adapter);
        if (!vendor.isEmpty()) {
            return vendor;
        }
        return DefaultHandlerSelector.select(adapter).map(List::of).orElse(List.of());
    }

    /** The current explicit assignment, if any -- before any staleness pruning. */
    public Optional<Set<HandlerRef>> explicit() {
        return Optional.ofNullable(explicit.get());
    }

    /** Replaces the explicit assignment. An empty set is never passed here -- see {@link #clearExplicit()}. */
    public void setExplicit(Set<HandlerRef> members) {
        explicit.set(Set.copyOf(members));
    }

    /** Reverts to the stateless/rule-driven case. */
    public void clearExplicit() {
        explicit.set(null);
    }

    /**
     * Renames {@code oldRef} to {@code newRef} within the explicit
     * assignment, if one exists and {@code oldRef} is a member -- the
     * {@code DEFAULT_HANDLERS} counterpart to {@link
     * HandlerBaselineRegistry#migrateKey}/{@link
     * HandlerOverrideRegistry#migrateKey} (doc/specs/
     * handler-floor-control.md "Resume resilience and baseline-key
     * migration", issue #29). Without this, a WildFly token&rarr;friendly-name
     * promotion would look identical to the member simply vanishing, and
     * the next {@link #members} call would silently prune a handler the
     * user explicitly chose. A no-op for the stateless case -- nothing
     * stored, nothing to rename.
     */
    public void migrateMember(HandlerRef oldRef, HandlerRef newRef) {
        while (true) {
            Set<HandlerRef> current = explicit.get();
            if (current == null || !current.contains(oldRef)) {
                return;
            }
            Set<HandlerRef> updated = new LinkedHashSet<>(current);
            updated.remove(oldRef);
            updated.add(newRef);
            if (explicit.compareAndSet(current, Set.copyOf(updated))) {
                return;
            }
        }
    }

    /**
     * {@code DEFAULT_HANDLERS}'s current members, resolved per the rules in
     * the class doc. Never empty when {@code adapter.realHandlers()} isn't
     * empty: the deterministic rule ({@link DefaultHandlerSelector}) always
     * picks exactly one handler when no explicit assignment survives.
     */
    public List<HandlerRef> members(LoggingAdapter adapter) {
        while (true) {
            Set<HandlerRef> current = explicit.get();
            if (current == null) {
                return baselineMembers(adapter);
            }
            Set<HandlerRef> reals = Set.copyOf(adapter.realHandlers());
            Set<HandlerRef> survivors = new LinkedHashSet<>();
            for (HandlerRef ref : current) {
                if (reals.contains(ref)) {
                    survivors.add(ref);
                }
            }
            if (survivors.isEmpty()) {
                // Total staleness -- "start over": discard, don't re-persist a
                // freshly-computed value (doc/specs/handler-floor-control.md
                // "Staleness"). The caller (HandlerLevelControlService) is
                // responsible for clearing the state-file record too.
                if (!explicit.compareAndSet(current, null)) {
                    continue; // lost the race to a concurrent assignment/migration -- reread and retry
                }
                return baselineMembers(adapter);
            }
            if (survivors.size() < current.size()) {
                // Partial staleness -- prune and keep the rest as the explicit
                // assignment; the caller persists the pruned set.
                if (!explicit.compareAndSet(current, Set.copyOf(survivors))) {
                    continue; // lost the race -- reread and retry against the newer value
                }
            }
            return List.copyOf(survivors);
        }
    }
}
