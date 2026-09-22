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

    static boolean matches(CompiledMatchers matchers, RuleCandidateEvent event) {
        if (matchers.levelAtMost() != null && event.level().compareTo(matchers.levelAtMost()) > 0) {
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
        for (Class<?> c = actual; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(expectedTypeName)) {
                return true;
            }
        }
        return false;
    }

    /** {@code Throwable.getCause()} can cycle back to itself; guards the same way {@code printStackTrace} does. */
    private static Throwable causeOf(Throwable t) {
        Throwable cause = t.getCause();
        return cause == t ? null : cause;
    }
}
