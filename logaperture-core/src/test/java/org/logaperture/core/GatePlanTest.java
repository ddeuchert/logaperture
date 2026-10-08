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
import org.logaperture.api.Level;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;

import java.time.Instant;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/rule-pipeline-foundation.md "Evaluation cost", R4 and R5 (issue #148): the
 * character-pair prefilter only ever skips a rule whose needle can't be in the message, and an
 * event no rule matches is answered without the decision cache.
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

    /** What {@link RuleMatching} decides for a message matcher alone. */
    private static boolean contains(String message, String needle, boolean ignoreCase) {
        return ignoreCase
                ? message.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))
                : message.contains(needle);
    }

    private static void assertNeverRulesOut(String message, String needle, boolean ignoreCase) {
        int[] probes = GatePlan.probesFor(message(needle, ignoreCase));
        if (probes != null && contains(message, needle, ignoreCase)) {
            assertTrue(GatePlan.containsAll(GatePlan.pairsOf(message), probes),
                    "needle '" + needle + "' (ignoreCase=" + ignoreCase + ") ruled out of '" + message + "'");
        }
    }

    @Test
    void prefilter_neverRulesOutANeedleTheMessageContains_randomText() {
        Random random = new Random(148);
        String alphabet = "abcABC xyz()[]0123:-_.KkSsIiİıΣσςΚ";
        for (int round = 0; round < 50_000; round++) {
            StringBuilder message = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) {
                message.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = message.toString();
            if (text.isEmpty()) {
                continue;
            }
            int from = random.nextInt(text.length());
            int to = from + 1 + random.nextInt(Math.min(8, text.length() - from));
            String needle = text.substring(from, to);
            assertNeverRulesOut(text, needle, false);
            assertNeverRulesOut(text, needle, true);
            assertNeverRulesOut(text, needle.toUpperCase(Locale.ROOT), true);
            assertNeverRulesOut(text, needle.toLowerCase(Locale.ROOT), true);
        }
    }

    @Test
    void prefilter_ignoreCase_characterThatLowerCasesToAscii() {
        // U+212A KELVIN SIGN lower-cases to 'k'; U+0130 to "i" plus a combining dot
        assertNeverRulesOut("OKK done", "okk", true);
        assertNeverRulesOut("Xİ", "xi", true);
        assertNeverRulesOut("ΟΔΟΣ ok", "ος", false);
    }

    @Test
    void prefilter_skipsNeedlesItCantCheck() {
        assertNull(GatePlan.probesFor(message("x", false)), "one character has no pair");
        assertNull(GatePlan.probesFor(message("οδος", true)), "non-ASCII ignore-case needle");
        assertNull(GatePlan.probesFor(CompiledMatchers.matchAll()));
        assertEquals(GatePlan.PROBES_PER_NEEDLE, GatePlan.probesFor(message("connection pool", false)).length);
    }

    @Test
    void prefilter_onlyWithEnoughMessageRules() {
        for (int i = 0; i < GatePlan.PREFILTER_MIN_RULES - 1; i++) {
            drop("needle " + i, false);
        }
        assertFalse(plan().usesPrefilter());
        drop("needle last", false);
        assertTrue(plan().usesPrefilter());
    }

    @Test
    void gate_withPrefilter_sameVerdictsAsMatching() {
        for (int i = 0; i < 20; i++) {
            drop("connection pool exhausted (" + i + ")", i % 2 == 0);
        }
        assertTrue(plan().usesPrefilter());
        assertSame(GateVerdict.allow(), evaluate("Processed order 48213 for customer 7f3a9c21 in 12 ms"));
        assertTrue(evaluate("warn: Connection Pool Exhausted (12) after 3 s").deny(), "ignore-case rule 12");
        assertFalse(evaluate("warn: Connection Pool Exhausted (13) after 3 s").deny(), "rule 13 is case-sensitive");
        assertTrue(evaluate("warn: connection pool exhausted (13) after 3 s").deny());
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
