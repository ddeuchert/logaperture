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

import org.logaperture.api.LogRule;

/**
 * A {@link LogRule} tagged with its owning context's stable key — {@link
 * AggregateLevelControl#listRules()}'s row shape. {@link LogRule} is an
 * interface implemented by varying concrete types (unlike {@code Storm}/
 * {@code LoggerByteCount}, which are records with their own {@code
 * withContext} copy method), so context-stamping happens at this wrapper
 * level instead of on the domain object itself. {@code context} is {@code
 * null} only when produced directly by a single-context {@link RuleService}
 * — {@code AggregateLevelControl} always stamps the real key, same
 * convention as every other multi-context row in this codebase.
 */
public record RuleView(LogRule rule, String context) {
}
