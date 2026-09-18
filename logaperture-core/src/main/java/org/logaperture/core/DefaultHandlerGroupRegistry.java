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
 */
public final class DefaultHandlerGroupRegistry {

    /** {@code null} = stateless/rule-driven; non-null = explicit, persisted. */
    private final AtomicReference<Set<HandlerRef>> explicit = new AtomicReference<>();

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
        Set<HandlerRef> current = explicit.get();
        if (current == null || !current.contains(oldRef)) {
            return;
        }
        Set<HandlerRef> updated = new LinkedHashSet<>(current);
        updated.remove(oldRef);
        updated.add(newRef);
        explicit.set(Set.copyOf(updated));
    }

    /**
     * {@code DEFAULT_HANDLERS}'s current members, resolved per the rules in
     * the class doc. Never empty when {@code adapter.realHandlers()} isn't
     * empty: the deterministic rule ({@link DefaultHandlerSelector}) always
     * picks exactly one handler when no explicit assignment survives.
     */
    public List<HandlerRef> members(LoggingAdapter adapter) {
        Set<HandlerRef> current = explicit.get();
        if (current == null) {
            return DefaultHandlerSelector.select(adapter).map(List::of).orElse(List.of());
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
            explicit.set(null);
            return DefaultHandlerSelector.select(adapter).map(List::of).orElse(List.of());
        }
        if (survivors.size() < current.size()) {
            // Partial staleness -- prune and keep the rest as the explicit
            // assignment; the caller persists the pruned set.
            explicit.set(survivors);
        }
        return List.copyOf(survivors);
    }
}
