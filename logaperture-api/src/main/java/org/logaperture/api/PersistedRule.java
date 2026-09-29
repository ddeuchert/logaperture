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
package org.logaperture.api;

import java.time.Instant;
import java.util.Map;

/**
 * The on-disk shape of an attached {@link LogRule} — doc/specs/
 * rule-pipeline-foundation.md "Persistence". Generic across every action
 * (unlike {@code LogRule} itself, which is an interface with one concrete
 * type per action): {@code action} is a discriminator string (this slice's
 * own {@code actionName()}, e.g. {@code "Drop"}/{@code "Trim"} once #72/#34
 * exist) a {@code RuleFactory} is looked up by on resume — see {@code
 * RuleService#registerActionFactory}. A {@code STICKY}/{@code FOR} rule
 * round-trips through this shape; a resumed rule keeps this exact {@code
 * id}, never a freshly-generated one (doc/specs/rule-pipeline-foundation.md
 * "Rule identity").
 *
 * @param context the owning logging context's stable key (e.g. {@code
 *                "system"}) this rule was attached in — every context in a
 *                JVM shares one {@code StateStore}, so this is what a
 *                single-context {@code RuleService} filters {@code
 *                resumeFromStateStore} by: a row belongs to the context
 *                that wrote it, never resumed into a different one sharing
 *                the same store. {@code null} only for a row written before
 *                this field existed (there are none in practice, this
 *                schema having never shipped without it) or a hand-edited
 *                file — treated as "resume anywhere" for tolerance, the
 *                same convention every other optional field here follows
 * @param payload the attaching {@link LogRule}'s own {@link
 *                LogRule#persistedPayload()} — opaque to this type, decoded
 *                only by the {@link RuleFactory} its {@code action}
 *                discriminator resolves to on resume (doc/specs/
 *                drop-rule.md Decision #4). {@code null} only for a row
 *                written before this field existed, or a hand-edited file —
 *                read back as an empty map, same tolerant convention as
 *                every other optional field here
 * @param stateId the id the state file knows this rule by -- assigned by the
 *                {@code StateStore} the first time it is saved, kept across later saves of the
 *                same rule, and written into an exported vendor defaults file so a restart with
 *                that file can take the entry over (doc/specs/export-round-trip.md); {@code null}
 *                until it has been persisted
 */
public record PersistedRule(
        String id,
        String loggerName,
        String action,
        CompiledMatchers matchers,
        String reason,
        PersistenceTier tier,
        Instant expiresAt,
        Instant createdAt,
        String context,
        Map<String, String> payload,
        String stateId,
        String recipe) {

    public PersistedRule {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** A rule not yet persisted: no {@link #stateId()} until a {@code StateStore} saves it. */
    public PersistedRule(String id, String loggerName, String action, CompiledMatchers matchers, String reason,
            PersistenceTier tier, Instant expiresAt, Instant createdAt, String context,
            Map<String, String> payload) {
        this(id, loggerName, action, matchers, reason, tier, expiresAt, createdAt, context, payload, null);
    }

    /** This rule with {@code stateId}, as the {@code StateStore} assigns or restores it. */
    /** Not made by a recipe. */
    public PersistedRule(String id, String loggerName, String action, CompiledMatchers matchers, String reason,
            PersistenceTier tier, Instant expiresAt, Instant createdAt, String context,
            Map<String, String> payload, String stateId) {
        this(id, loggerName, action, matchers, reason, tier, expiresAt, createdAt, context, payload, stateId, null);
    }

    public PersistedRule withStateId(String stateId) {
        return new PersistedRule(id, loggerName, action, matchers, reason, tier, expiresAt, createdAt, context, payload,
                stateId, recipe);
    }
}
