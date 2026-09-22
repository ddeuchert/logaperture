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

import java.time.Instant;

/**
 * A minimal {@link LogRule} double — doc/specs/rule-pipeline-foundation.md
 * "Testing": this slice has no concrete action type (#72's {@code Drop},
 * #34's {@code Trim}), so its own suite exercises the generic
 * attach/list/reset machinery against this instead.
 */
final class TestRule implements LogRule {

    private final String id;
    private final String loggerName;
    private final CompiledMatchers matchers;
    private final String reason;
    private final PersistenceTier tier;
    private final Instant expiresAt;
    private final Instant createdAt;

    TestRule(String id, String loggerName, CompiledMatchers matchers, String reason, PersistenceTier tier,
            Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.loggerName = loggerName;
        this.matchers = matchers;
        this.reason = reason;
        this.tier = tier;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** A {@link RuleFactory} that builds a plain {@code TestRule} — ignores {@code payload}, which it has no use for. */
    static final RuleFactory FACTORY = (id, loggerName, matchers, reason, tier, expiresAt, createdAt, payload) ->
            new TestRule(id, loggerName, matchers, reason, tier, expiresAt, createdAt);

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
}
