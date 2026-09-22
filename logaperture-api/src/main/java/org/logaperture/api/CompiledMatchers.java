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

/**
 * The §7.2 matcher subset shared by every {@link LogRule} implementation —
 * doc/specs/filtering-epic.md "Matchers", doc/specs/
 * rule-pipeline-foundation.md "Matcher library". Compiled once at attach
 * time and held immutably by the {@link LogRule} it belongs to; every field
 * is optional (unset means "don't constrain on this"), and every set field
 * on a rule must hold for a match (AND) — no {@code not}/{@code any}/
 * {@code all} composition in this slice. {@code logger} is deliberately
 * absent here: a rule's logger is its attachment point (see "Logger scope
 * and inheritance"), not a per-event check this type would express.
 *
 * @param levelAtMost          the matching event's level must be at or below
 *                             this bound; {@code null} means no level bound
 * @param messageContains      the formatted message must contain this text;
 *                             {@code null} means no message constraint
 * @param messageIgnoreCase    whether {@code messageContains} is matched
 *                             case-insensitively; meaningless if {@code
 *                             messageContains} is {@code null}
 * @param throwableType        the event's throwable (or, if {@code
 *                             anyCause}, one in its cause chain) must be an
 *                             instance of this class name, subclasses
 *                             included; {@code null} means no type
 *                             constraint
 * @param throwableMessageContains the matched throwable's own message must
 *                             contain this text; {@code null} means no
 *                             constraint
 * @param anyCause             when {@code true}, {@code throwableType}/
 *                             {@code throwableMessageContains} may match any
 *                             throwable in the cause chain, not only the
 *                             top-level one
 */
public record CompiledMatchers(
        Level levelAtMost,
        String messageContains,
        boolean messageIgnoreCase,
        String throwableType,
        String throwableMessageContains,
        boolean anyCause) {

    /** No constraints at all — matches every event reaching the rule's logger. */
    public static CompiledMatchers matchAll() {
        return new CompiledMatchers(null, null, false, null, null, false);
    }
}
