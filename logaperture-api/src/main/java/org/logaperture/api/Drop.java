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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The gate-stage content rule — doc/specs/drop-rule.md (issue #72), the
 * first concrete {@link LogRule}. A per-event gate evaluated after the
 * level check passes: a matching event is discarded before it is formatted
 * or written. Not a level change — other events from the same logger flow
 * as before.
 *
 * <p>The keep-floor bound ("drop below ERROR") is not a separate field —
 * it's {@link CompiledMatchers#levelAtMost()}, the bound the caller already
 * compiled in (defaulted to the level just more verbose than {@code ERROR}
 * when {@code --below} is omitted; see doc/specs/drop-rule.md "Data model").
 */
public final class Drop implements LogRule {

    private static final String PAYLOAD_SAMPLE_ENABLED = "sampleFullEnabled";
    private static final String PAYLOAD_SAMPLE_EVERY_MILLIS = "sampleFullEveryMillis";

    private final String id;
    private final String loggerName;
    private final CompiledMatchers matchers;
    private final String reason;
    private final PersistenceTier tier;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final SampleFullPolicy sampleFull;

    public Drop(String id, String loggerName, CompiledMatchers matchers, String reason, PersistenceTier tier,
            Instant expiresAt, Instant createdAt, SampleFullPolicy sampleFull) {
        this.id = Objects.requireNonNull(id, "id");
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.matchers = Objects.requireNonNull(matchers, "matchers");
        this.reason = reason;
        this.tier = Objects.requireNonNull(tier, "tier");
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.sampleFull = Objects.requireNonNull(sampleFull, "sampleFull");
    }

    /**
     * Decodes {@link #persistedPayload()}'s shape back into a {@link
     * SampleFullPolicy} — used by {@code core}'s own {@code RuleFactory}
     * for resuming a persisted {@code Drop} (kept here, next to {@link
     * #persistedPayload()}, as the inverse of that encoding; {@code api}
     * itself has no {@code RuleFactory} type to build one against, per
     * doc/specs/drop-rule.md "Divergence from prior specs" — {@code core}'s
     * {@code DropFactories} calls this directly).
     */
    public static SampleFullPolicy sampleFullFromPayload(Map<String, String> payload) {
        String enabled = payload.get(PAYLOAD_SAMPLE_ENABLED);
        String everyMillis = payload.get(PAYLOAD_SAMPLE_EVERY_MILLIS);
        if (enabled == null || everyMillis == null) {
            // A hand-edited row, or one written before this field existed --
            // tolerant default, same convention every other optional
            // persisted field in this codebase follows.
            return SampleFullPolicy.defaults();
        }
        return new SampleFullPolicy(Boolean.parseBoolean(enabled), Duration.ofMillis(Long.parseLong(everyMillis)));
    }

    /** doc/specs/drop-rule.md Decision #4: the opaque payload bag {@code PersistedRule} carries this rule's own fields in. */
    @Override
    public Map<String, String> persistedPayload() {
        return Map.of(
                PAYLOAD_SAMPLE_ENABLED, Boolean.toString(sampleFull.enabled()),
                PAYLOAD_SAMPLE_EVERY_MILLIS, Long.toString(sampleFull.every().toMillis()));
    }

    public SampleFullPolicy sampleFull() {
        return sampleFull;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public CompiledMatchers matchers() {
        return matchers;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public PersistenceTier tier() {
        return tier;
    }

    @Override
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public String actionName() {
        return "drop";
    }
}
