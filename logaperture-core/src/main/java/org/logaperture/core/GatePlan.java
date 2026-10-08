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
import org.logaperture.api.Drop;
import org.logaperture.api.LogRule;
import org.logaperture.api.Trim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One logger's effective rules, compiled for the gate -- doc/specs/rule-pipeline-foundation.md
 * "Evaluation cost", R2 and R5. Cached per logger name by {@link RuleService#effectiveRules}'s
 * resolution cache, so the compile step runs once per logger per rule change.
 *
 * <p>R5, the character-pair index: with enough message-contains rules, each one is filed under one
 * adjacent pair of characters from its needle. Per event the gate walks the message's pairs once
 * and runs the matchers only of rules filed under a pair the message has, so its cost follows the
 * message's length, not the number of rules. It never changes a result: a needle in the message
 * brings every one of its pairs with it, so its rule is always reached.
 */
final class GatePlan {

    /** R5: below this many indexed rules, checking each one costs less than walking the message. */
    static final int INDEX_MIN_RULES = 6;

    private static final int BUCKETS_LOG2 = 10;
    private static final int BUCKETS = 1 << BUCKETS_LOG2;

    static final GatePlan EMPTY = new GatePlan(List.of());

    private final List<LogRule> rules;
    /** Per rule, its matchers if it's a {@link Drop} or a {@link Trim}, else {@code null}. */
    private final CompiledMatchers[] matchers;
    private final boolean[] trim;
    /** Rules checked one by one on every event: all of them, unless the index is in use. */
    private final int[] direct;
    /** Pair bucket to the rules filed under it; {@code null} when the index is not in use. */
    private final int[][] index;

    GatePlan(List<LogRule> rules) {
        this.rules = rules;
        int size = rules.size();
        this.matchers = new CompiledMatchers[size];
        this.trim = new boolean[size];
        int[][] pairsByRule = new int[size][];
        int indexable = 0;
        for (int i = 0; i < size; i++) {
            LogRule rule = rules.get(i);
            if (rule instanceof Drop drop) {
                matchers[i] = drop.matchers();
            } else if (rule instanceof Trim t) {
                matchers[i] = t.matchers();
                trim[i] = true;
            }
            pairsByRule[i] = needlePairs(matchers[i]);
            if (pairsByRule[i] != null) {
                indexable++;
            }
        }
        List<Integer> unindexed = new ArrayList<>();
        if (indexable >= INDEX_MIN_RULES) {
            List<List<Integer>> buckets = new ArrayList<>(BUCKETS);
            for (int b = 0; b < BUCKETS; b++) {
                buckets.add(new ArrayList<>());
            }
            for (int i = 0; i < size; i++) {
                if (pairsByRule[i] == null) {
                    if (matchers[i] != null) {
                        unindexed.add(i);
                    }
                    continue;
                }
                // File under the least crowded of the needle's pairs, so rules sharing a common
                // pair don't all land in one bucket and get checked together.
                int best = pairsByRule[i][0];
                for (int pair : pairsByRule[i]) {
                    if (buckets.get(pair).size() < buckets.get(best).size()) {
                        best = pair;
                    }
                }
                buckets.get(best).add(i);
            }
            this.index = new int[BUCKETS][];
            for (int b = 0; b < BUCKETS; b++) {
                if (!buckets.get(b).isEmpty()) {
                    index[b] = buckets.get(b).stream().mapToInt(Integer::intValue).toArray();
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                if (matchers[i] != null) {
                    unindexed.add(i);
                }
            }
            this.index = null;
        }
        this.direct = unindexed.stream().mapToInt(Integer::intValue).toArray();
    }

    List<LogRule> rules() {
        return rules;
    }

    boolean usesIndex() {
        return index != null;
    }

    /**
     * Whether {@link RuleService}'s verdict for {@code event} could be anything but "allow, no
     * trim": some {@link Drop} matches, or the event has a throwable and some {@link Trim} matches.
     * Free of side effects -- R4: only a {@code true} here sends the event to the decision cache.
     */
    boolean anyCandidateMatches(RuleCandidateEvent event) {
        for (int i : direct) {
            if (candidateMatches(i, event)) {
                return true;
            }
        }
        if (index == null) {
            return false;
        }
        String message = event.formattedMessageSupplier().get();
        if (message == null || message.length() < 2) {
            return false; // every indexed rule needs at least two characters of message
        }
        long[] visited = new long[BUCKETS / Long.SIZE];
        char previous = fold(message.charAt(0));
        for (int c = 1; c < message.length(); c++) {
            char current = fold(message.charAt(c));
            int bucket = bucket(previous, current);
            previous = current;
            int[] filed = index[bucket];
            if (filed == null || (visited[bucket >>> 6] & (1L << bucket)) != 0) {
                continue;
            }
            visited[bucket >>> 6] |= 1L << bucket;
            for (int i : filed) {
                if (candidateMatches(i, event)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean candidateMatches(int i, RuleCandidateEvent event) {
        if (trim[i] && event.thrown() == null) {
            return false; // RuleService.mostRestrictiveTrim never trims an event without a throwable
        }
        return RuleMatching.matches(matchers[i], event);
    }

    /**
     * The bucket of every adjacent pair in the rule's needle, or {@code null} when the rule can't
     * be indexed: no needle, a one-character needle, or an ignore-case needle that isn't ASCII.
     * {@link RuleMatching} lower-cases the whole message as a {@code String}, which for a few
     * characters (final sigma, dotted capital I) isn't the per-character folding {@link #fold}
     * does, so a non-ASCII pair could be missed. An ASCII pair can't: every character the {@code
     * String} lower-cases to ASCII folds to the same character here.
     */
    static int[] needlePairs(CompiledMatchers matchers) {
        if (matchers == null || matchers.messageContains() == null) {
            return null;
        }
        String needle = matchers.messageIgnoreCase()
                ? matchers.messageContains().toLowerCase(Locale.ROOT)
                : matchers.messageContains();
        if (needle.length() < 2 || (matchers.messageIgnoreCase() && !isAscii(needle))) {
            return null;
        }
        int[] buckets = new int[needle.length() - 1];
        for (int p = 0; p < buckets.length; p++) {
            buckets[p] = bucket(fold(needle.charAt(p)), fold(needle.charAt(p + 1)));
        }
        return buckets;
    }

    /**
     * Case-folds one character the same way for message and needle, so a case-sensitive needle's
     * pairs are present whenever the needle is, and an ASCII ignore-case needle's pairs whenever
     * the lower-cased message contains it.
     */
    private static char fold(char c) {
        if (c < 128) {
            return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
        }
        return Character.toLowerCase(c);
    }

    private static int bucket(char first, char second) {
        return ((first << 16 | second) * 0x9E3779B1) >>> (Integer.SIZE - BUCKETS_LOG2);
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }
}
