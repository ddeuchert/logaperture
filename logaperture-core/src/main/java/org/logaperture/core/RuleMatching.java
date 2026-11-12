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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Evaluates one {@link CompiledMatchers} against one {@link
 * RuleCandidateEvent} -- doc/specs/rule-pipeline-foundation.md "Matcher
 * library" (checks ordered cheapest-first: level, then throwable type, then
 * message), doc/specs/drop-rule.md "Matchers in use". Every set field on
 * the matcher must hold (AND); an unset field imposes no constraint.
 */
final class RuleMatching {

    private RuleMatching() {
    }

    /**
     * The names of every class from {@code type} up through its superclass
     * chain, cached per {@link Class} so {@link #isInstanceByName} does a
     * hash lookup instead of walking the hierarchy for each rule's type
     * matcher every time (issue #177). A class's supertype chain never
     * changes, so the cache is never stale. {@link ClassValue} is designed
     * for exactly this pattern: one computed value per class, lazily, with
     * no expiration.
     */
    private static final ClassValue<Set<String>> SUPERTYPE_NAMES = new ClassValue<>() {
        @Override
        protected Set<String> computeValue(Class<?> type) {
            Set<String> names = new HashSet<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                names.add(c.getName());
            }
            return Collections.unmodifiableSet(names);
        }
    };

    static boolean matches(CompiledMatchers matchers, RuleCandidateEvent event) {
        // matchers.levelAtMost().isMoreVerboseThan(event.level()) -- not the reverse -- reuses
        // Level's own named comparator (every other severity comparison in this codebase does)
        // instead of re-deriving the ordinal-comparison direction by hand (a code-review finding).
        if (matchers.levelAtMost() != null && matchers.levelAtMost().isMoreVerboseThan(event.level())) {
            return false; // event is more severe than the keep-floor bound -- spared
        }
        if (matchers.throwableType() != null || matchers.throwableMessageContains() != null) {
            if (!throwableMatches(matchers, event.thrown())) {
                return false;
            }
        }
        if (matchers.messageContains() != null) {
            String formatted = event.formattedMessageSupplier().get();
            if (formatted == null) {
                return false;
            }
            boolean contains = matchers.messageIgnoreCase()
                    ? formatted.toLowerCase(java.util.Locale.ROOT)
                            .contains(matchers.messageContains().toLowerCase(java.util.Locale.ROOT))
                    : formatted.contains(matchers.messageContains());
            if (!contains) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks {@code thrown} (top-level only, unless {@code anyCause}) looking
     * for one whose type/message satisfies both throwable matchers that are
     * set. {@code type} compares by class name up the real class hierarchy
     * (subclasses included) -- a plain {@code String} comparison per frame,
     * no reflection beyond the {@code Class} objects already on hand from
     * the live {@code Throwable} (doc/specs/rule-pipeline-foundation.md
     * "Matcher library").
     */
    private static boolean throwableMatches(CompiledMatchers matchers, Throwable thrown) {
        if (thrown == null) {
            return false;
        }
        if (matchers.anyCause()) {
            for (Throwable t = thrown; t != null; t = causeOf(t)) {
                if (oneThrowableMatches(matchers, t)) {
                    return true;
                }
            }
            return false;
        }
        return oneThrowableMatches(matchers, thrown);
    }

    private static boolean oneThrowableMatches(CompiledMatchers matchers, Throwable t) {
        if (matchers.throwableType() != null && !isInstanceByName(t.getClass(), matchers.throwableType())) {
            return false;
        }
        if (matchers.throwableMessageContains() != null) {
            String message = t.getMessage();
            if (message == null || !message.contains(matchers.throwableMessageContains())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInstanceByName(Class<?> actual, String expectedTypeName) {
        return SUPERTYPE_NAMES.get(actual).contains(expectedTypeName);
    }

    /** {@code Throwable.getCause()} can cycle back to itself; guards the same way {@code printStackTrace} does. */
    private static Throwable causeOf(Throwable t) {
        Throwable cause = t.getCause();
        return cause == t ? null : cause;
    }
}
