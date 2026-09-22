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
 */
public record PersistedRule(
        String id,
        String loggerName,
        String action,
        CompiledMatchers matchers,
        String reason,
        PersistenceTier tier,
        Instant expiresAt,
        Instant createdAt) {
}
