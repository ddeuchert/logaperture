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

import java.util.List;

/**
 * One logger's effective rules, compiled for the gate -- doc/specs/rule-pipeline-foundation.md
 * "Evaluation cost", R2 and R5. Cached per logger name by {@link RuleService#effectiveRules}'s
 * resolution cache, so the compile step runs once per logger per rule change.
 *
 * <p>R5, the character-pair prefilter: with enough message-contains rules, the gate hashes every
 * adjacent pair of characters in the message into a 1,024-bit set once per event, and a rule whose
 * needle has a pair missing from the set is skipped without its {@code contains} scan. It never
 * changes a result: a missing pair proves the needle isn't in the message, and a present one
 * just falls through to the rule's ordinary matchers.
 */
final class GatePlan {

    /** R5: below this many prefiltered rules, plain {@code contains} scans cost less than the hashing. */
    static final int PREFILTER_MIN_RULES = 6;
    /** R5: pairs of a needle checked against the set, spread from its first pair to its last. */
    static final int PROBES_PER_NEEDLE = 4;

    private static final int BITS_LOG2 = 10;
    private static final int WORDS = (1 << BITS_LOG2) / Long.SIZE;

    static final GatePlan EMPTY = new GatePlan(List.of());

    private final List<LogRule> rules;
    /** Per rule, its needle's probe bits, or {@code null} when the prefilter can't rule it out. */
    private final int[][] probes;
    private final boolean prefilter;

    GatePlan(List<LogRule> rules) {
        this.rules = rules;
        this.probes = new int[rules.size()][];
        int prefiltered = 0;
        for (int i = 0; i < rules.size(); i++) {
            probes[i] = probesFor(matchersOf(rules.get(i)));
            if (probes[i] != null) {
                prefiltered++;
            }
        }
        this.prefilter = prefiltered >= PREFILTER_MIN_RULES;
    }

    List<LogRule> rules() {
        return rules;
    }

    boolean usesPrefilter() {
        return prefilter;
    }

    /**
     * Whether {@link RuleService}'s verdict for {@code event} could be anything but "allow, no
     * trim": some {@link Drop} matches, or the event has a throwable and some {@link Trim} matches.
     * Free of side effects -- R4: only a {@code true} here sends the event to the decision cache.
     */
    boolean anyCandidateMatches(RuleCandidateEvent event) {
        long[] pairs = null;
        for (int i = 0; i < rules.size(); i++) {
            LogRule rule = rules.get(i);
            CompiledMatchers matchers;
            if (rule instanceof Drop drop) {
                matchers = drop.matchers();
            } else if (rule instanceof Trim trim && event.thrown() != null) {
                matchers = trim.matchers();
            } else {
                continue;
            }
            if (prefilter && probes[i] != null) {
                if (pairs == null) {
                    String message = event.formattedMessageSupplier().get();
                    if (message == null) {
                        continue; // RuleMatching rejects a message matcher on a null message too
                    }
                    pairs = pairsOf(message);
                }
                if (!containsAll(pairs, probes[i])) {
                    continue;
                }
            }
            if (RuleMatching.matches(matchers, event)) {
                return true;
            }
        }
        return false;
    }

    private static CompiledMatchers matchersOf(LogRule rule) {
        if (rule instanceof Drop drop) {
            return drop.matchers();
        }
        if (rule instanceof Trim trim) {
            return trim.matchers();
        }
        return null;
    }

    /**
     * {@code null} unless the rule has a needle of at least two characters. An ignore-case needle
     * must also be ASCII: {@link RuleMatching} lower-cases the whole message as a {@code String},
     * which for a few characters (final sigma, dotted capital I) isn't the per-character folding
     * {@link #fold} does, so a non-ASCII pair could be missed. An ASCII pair can't: every
     * character the {@code String} lower-cases to ASCII folds to the same character here.
     */
    static int[] probesFor(CompiledMatchers matchers) {
        if (matchers == null || matchers.messageContains() == null) {
            return null;
        }
        String needle = matchers.messageIgnoreCase()
                ? matchers.messageContains().toLowerCase(java.util.Locale.ROOT)
                : matchers.messageContains();
        int pairCount = needle.length() - 1;
        if (pairCount < 1 || (matchers.messageIgnoreCase() && !isAscii(needle))) {
            return null;
        }
        int count = Math.min(PROBES_PER_NEEDLE, pairCount);
        int[] bits = new int[count];
        for (int p = 0; p < count; p++) {
            int at = count == 1 ? 0 : (int) ((long) p * (pairCount - 1) / (count - 1));
            bits[p] = bit(fold(needle.charAt(at)), fold(needle.charAt(at + 1)));
        }
        return bits;
    }

    /** Every adjacent character pair of {@code message}, folded and hashed into the set. */
    static long[] pairsOf(String message) {
        long[] pairs = new long[WORDS];
        int length = message.length();
        if (length < 2) {
            return pairs;
        }
        char previous = fold(message.charAt(0));
        for (int i = 1; i < length; i++) {
            char current = fold(message.charAt(i));
            int bit = bit(previous, current);
            pairs[bit >>> 6] |= 1L << bit;
            previous = current;
        }
        return pairs;
    }

    static boolean containsAll(long[] pairs, int[] bits) {
        for (int bit : bits) {
            if ((pairs[bit >>> 6] & (1L << bit)) == 0) {
                return false;
            }
        }
        return true;
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

    private static int bit(char first, char second) {
        return ((first << 16 | second) * 0x9E3779B1) >>> (Integer.SIZE - BITS_LOG2);
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
