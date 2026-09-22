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
 * Builds a concrete {@link LogRule} once {@link RuleService#attach} has
 * already generated its identity fields (id, timestamps, resolved expiry)
 * and passed every capability/suppression-floor check — doc/specs/
 * rule-pipeline-foundation.md "Rule identity". A caller with a concrete
 * action type (#72's {@code Drop}, #34's {@code Trim}) supplies its own
 * action-specific fields (a matcher-below-level bound, sample policy,
 * frame count, ...) on top of what this callback is handed; this slice's
 * own tests use it to build a {@code TestRule}.
 */
@FunctionalInterface
public interface RuleFactory {

    LogRule create(String id, String loggerName, CompiledMatchers matchers, String reason, PersistenceTier tier,
            Instant expiresAt, Instant createdAt);
}
