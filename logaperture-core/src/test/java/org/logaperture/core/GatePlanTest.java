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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Drop;
import org.logaperture.api.Level;
import org.logaperture.api.LogRule;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/rule-pipeline-foundation.md "Evaluation cost", R4 and R5 (issue #148): the
 * character-pair index only ever skips a rule whose needle can't be in the message.
 */
class GatePlanTest {

    private static final String WORKER = "com.acme.Worker";

    private RuleService service;

    @BeforeEach
    void setUp() {
        service = new RuleService(new FakeLoggingAdapter(Level.INFO), CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), new InMemoryStateStore(), "system", "alice", "jmx");
    }

    private static CompiledMatchers message(String needle, boolean ignoreCase) {
        return new CompiledMatchers(null, needle, ignoreCase, null, null, false);
    }

    /** What {@link GatePlan#anyCandidateMatches} must answer: every rule checked, no index. */
    private static boolean bruteForce(List<LogRule> rules, RuleCandidateEvent event) {
        for (LogRule rule : rules) {
            if (rule instanceof Drop drop && RuleMatching.matches(drop.matchers(), event)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void index_sameAnswerAsCheckingEveryRule_randomText() {
        Random random = new Random(148);
        int indexedRounds = 0;
        String alphabet = "abcABC xyz()0123:-.KkSsIiİıΣσςΚ\u212A";
        for (int round = 0; round < 300; round++) {
            setUp();
            List<String> corpus = new ArrayList<>();
            for (int m = 0; m < 40; m++) {
                corpus.add(randomText(random, alphabet, 30));
            }
            int ruleCount = GatePlan.INDEX_MIN_RULES + random.nextInt(20);
            for (int r = 0; r < ruleCount; r++) {
                String source = corpus.get(random.nextInt(corpus.size()));
                String needle = source.isEmpty() || random.nextInt(4) == 0
                        ? randomText(random, alphabet, 4)
                        : substring(random, source);
                if (needle.isEmpty()) {
                    needle = "a";
                }
                switch (random.nextInt(3)) {
                    case 0 -> drop(needle, false);
                    case 1 -> drop(needle.toUpperCase(Locale.ROOT), true);
                    default -> drop(needle, true);
                }
            }
            GatePlan plan = plan();
            if (plan.usesIndex()) {
                indexedRounds++;
            }
            for (String text : corpus) {
                RuleCandidateEvent event = new RuleCandidateEvent(WORKER, Level.INFO, null, () -> text, Instant.EPOCH);
                assertEquals(bruteForce(plan.rules(), event), plan.anyCandidateMatches(event),
                        "message '" + text + "', rules " + plan.rules());
            }
        }
        assertTrue(indexedRounds > 100, "most rounds exercise the index: " + indexedRounds);
    }

    @Test
    void index_ignoreCase_charactersThatLowerCaseToAscii() {
        // U+212A KELVIN SIGN lower-cases to 'k'; U+0130 to "i" plus a combining dot
        for (int i = 0; i < GatePlan.INDEX_MIN_RULES; i++) {
            drop("unrelated " + i, false);
        }
        drop("okk", true);
        drop("xi", true);
        assertTrue(plan().usesIndex());
        assertTrue(evaluate("OK\u212A done").deny());
        assertTrue(evaluate("X\u0130").deny());
    }

    @Test
    void index_skipsNeedlesItCantFile() {
        assertNull(GatePlan.needlePairs(message("x", false)), "one character has no pair");
        assertNull(GatePlan.needlePairs(message("οδος", true)), "non-ASCII ignore-case needle");
        assertNull(GatePlan.needlePairs(CompiledMatchers.matchAll()));
        assertEquals(14, GatePlan.needlePairs(message("connection pool", false)).length);
    }

    @Test
    void index_onlyWithEnoughMessageRules() {
        for (int i = 0; i < GatePlan.INDEX_MIN_RULES - 1; i++) {
            drop("needle " + i, false);
        }
        assertFalse(plan().usesIndex());
        drop("needle last", false);
        assertTrue(plan().usesIndex());
    }

    @Test
    void gate_withIndex_sameVerdictsAsMatching() {
        for (int i = 0; i < 20; i++) {
            drop("connection pool exhausted (" + i + ")", i % 2 == 0);
        }
        assertTrue(plan().usesIndex());
        assertSame(GateVerdict.allow(), evaluate("Processed order 48213 for customer 7f3a9c21 in 12 ms"));
        assertTrue(evaluate("warn: Connection Pool Exhausted (12) after 3 s").deny(), "ignore-case rule 12");
        assertFalse(evaluate("warn: Connection Pool Exhausted (13) after 3 s").deny(), "rule 13 is case-sensitive");
        assertTrue(evaluate("warn: connection pool exhausted (13) after 3 s").deny());
    }

    @Test
    void index_eventNoIndexedRuleCouldMatch_isNotFormatted() {
        for (int i = 0; i < GatePlan.INDEX_MIN_RULES; i++) {
            service.attach(WORKER, new CompiledMatchers(Level.DEBUG, "needle " + i, false, null, null, false),
                    RuleAttachOptions.defaults(), DropFactories.attach(SampleFullPolicy.disabled()), Capability.SUPPRESS);
        }
        assertTrue(plan().usesIndex());
        AtomicInteger formatted = new AtomicInteger();
        Supplier<String> message = () -> {
            formatted.incrementAndGet();
            return "needle 3";
        };
        assertSame(GateVerdict.allow(), service.gate().evaluate(new Object(),
                new RuleCandidateEvent(WORKER, Level.INFO, null, message, Instant.EPOCH)));
        assertEquals(0, formatted.get(), "every indexed rule is bounded at DEBUG, the event is INFO");
        assertTrue(service.gate().evaluate(new Object(),
                new RuleCandidateEvent(WORKER, Level.DEBUG, null, message, Instant.EPOCH)).deny());
    }

    @Test
    void index_trimsOnly_eventWithoutThrowable_isNotFormatted() {
        for (int i = 0; i < GatePlan.INDEX_MIN_RULES; i++) {
            service.attach(WORKER, message("needle " + i, false), RuleAttachOptions.defaults(),
                    TrimFactories.attach(5, false), Capability.SUPPRESS);
        }
        assertTrue(plan().usesIndex());
        AtomicInteger formatted = new AtomicInteger();
        Supplier<String> message = () -> {
            formatted.incrementAndGet();
            return "needle 3";
        };
        assertSame(GateVerdict.allow(), service.gate().evaluate(new Object(),
                new RuleCandidateEvent(WORKER, Level.INFO, null, message, Instant.EPOCH)));
        assertEquals(0, formatted.get());
        GateVerdict withThrowable = service.gate().evaluate(new Object(),
                new RuleCandidateEvent(WORKER, Level.INFO, new IllegalStateException(), message, Instant.EPOCH));
        assertEquals(5, withThrowable.trim().frames());
    }

    @Test
    void descendantsInheritingTheSameRules_shareOnePlan() {
        for (int i = 0; i < GatePlan.INDEX_MIN_RULES; i++) {
            service.attach("com.acme", message("needle " + i, false), RuleAttachOptions.defaults(),
                    DropFactories.attach(SampleFullPolicy.disabled()), Capability.SUPPRESS);
        }
        assertSame(service.effectiveRules("com.acme.a.One"), service.effectiveRules("com.acme.b.Two"));
    }

    private static String randomText(Random random, String alphabet, int maxLength) {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(maxLength + 1);
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    private static String substring(Random random, String source) {
        int from = random.nextInt(source.length());
        int to = from + 1 + random.nextInt(Math.min(8, source.length() - from));
        return source.substring(from, to);
    }

    private void drop(String needle, boolean ignoreCase) {
        service.attach(WORKER, message(needle, ignoreCase), RuleAttachOptions.defaults(),
                DropFactories.attach(SampleFullPolicy.disabled()), Capability.SUPPRESS);
    }

    private GatePlan plan() {
        return new GatePlan(service.effectiveRules(WORKER));
    }

    private GateVerdict evaluate(String message) {
        return service.gate().evaluate(new Object(),
                new RuleCandidateEvent(WORKER, Level.INFO, null, () -> message, Instant.now()));
    }
}
