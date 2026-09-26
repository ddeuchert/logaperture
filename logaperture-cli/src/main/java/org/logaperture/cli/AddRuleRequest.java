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
package org.logaperture.cli;

/**
 * What an {@code add rule} command line gave, part by part -- {@code null} (or {@code false} for a
 * flag) meaning "not given", so guided mode knows what is left to ask (doc/specs/guided-add-rule.md
 * G1). A complete request needs no questions; {@link #complete()} is the same test the parser and
 * the guided flow both apply.
 *
 * @param action            {@code "drop"}, {@code "trim"}, or {@code null}
 * @param target            an exact logger name, a leading-star pattern, or {@code null}
 * @param belowLevel        the compiled "at most" level {@code --below} resolved to, or {@code null}
 * @param sampleFullEnabled {@code --sample-full} ({@code true}) / {@code --no-sample-full} ({@code false}), or {@code null}
 * @param tier              the lifetime token(s), or {@code null}
 */
record AddRuleRequest(String action, String target, String messageContains, boolean messageIgnoreCase,
        String throwableType, String throwableMessageContains, boolean anyCause, String belowLevel,
        Boolean sampleFullEnabled, Long sampleFullEveryMillis, Integer frames, boolean collapseCauses,
        Parser.TierChoice tier, String reason, boolean yes, boolean json) {

    static final String DROP = "drop";
    static final String TRIM = "trim";

    boolean hasContentMatcher() {
        return messageContains != null || throwableType != null || throwableMessageContains != null;
    }

    /** Every required part is present: the type, the target, and -- for a drop -- a content matcher. */
    boolean complete() {
        return action != null && target != null && (action.equals(TRIM) || hasContentMatcher());
    }

    AddRuleRequest withAction(String newAction) {
        return new AddRuleRequest(newAction, target, messageContains, messageIgnoreCase, throwableType,
                throwableMessageContains, anyCause, belowLevel, sampleFullEnabled, sampleFullEveryMillis, frames,
                collapseCauses, tier, reason, yes, json);
    }

    AddRuleRequest withMatchers(String newMessageContains, boolean newMessageIgnoreCase, String newThrowableType,
            String newThrowableMessageContains, boolean newAnyCause) {
        return new AddRuleRequest(action, target, newMessageContains, newMessageIgnoreCase, newThrowableType,
                newThrowableMessageContains, newAnyCause, belowLevel, sampleFullEnabled, sampleFullEveryMillis,
                frames, collapseCauses, tier, reason, yes, json);
    }

    AddRuleRequest withOptions(String newBelowLevel, Boolean newSampleFullEnabled, Long newSampleFullEveryMillis,
            Integer newFrames, boolean newCollapseCauses, Parser.TierChoice newTier, String newReason) {
        return new AddRuleRequest(action, target, messageContains, messageIgnoreCase, throwableType,
                throwableMessageContains, anyCause, newBelowLevel, newSampleFullEnabled, newSampleFullEveryMillis,
                newFrames, newCollapseCauses, newTier, newReason, yes, json);
    }
}
