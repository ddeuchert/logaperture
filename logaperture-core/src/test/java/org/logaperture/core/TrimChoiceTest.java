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

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * doc/specs/log4j2-adapter.md A3: {@link RuleGate#trimChoice} chooses a trim exactly as
 * {@link RuleGate#evaluate} does, and never counts a hit.
 */
class TrimChoiceTest {

    private static final String WORKER = "com.acme.Worker";
    private static final Throwable BOOM = new RuntimeException("boom");

    private RuleService service;
    private RuleGate gate;

    @BeforeEach
    void setUp() {
        service = new RuleService(new FakeLoggingAdapter(Level.INFO), CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), new InMemoryStateStore(), "system", "alice", "jmx");
        service.registerDropSupport();
        service.registerTrimSupport();
        gate = service.gate();
    }

    private String trim(String logger, String message, int frames) {
        return service.addRuleTrim(logger, new CompiledMatchers(Level.INFO, message, false, null, null, false),
                RuleAttachOptions.defaults(), frames, false).rule().id();
    }

    private static RuleCandidateEvent event(String logger, Throwable thrown, String message) {
        return new RuleCandidateEvent(logger, Level.INFO, thrown, () -> message, Instant.EPOCH);
    }

    @Test
    void noRules_noChoice() {
        assertNull(gate.trimChoice(event(WORKER, BOOM, "x")));
    }

    @Test
    void matchingTrim_isChosen_andNeverCounted() {
        String id = trim(WORKER, "needle", 5);
        TrimDecision decision = gate.trimChoice(event(WORKER, BOOM, "a needle here"));
        assertNotNull(decision);
        assertEquals(id, decision.matchedRuleId());
        assertEquals(0, service.hitCount(id), "the query must never count");
        assertEquals(id, gate.evaluate(new Object(), event(WORKER, BOOM, "a needle here")).trim().matchedRuleId());
        assertEquals(1, service.hitCount(id), "the gate counts, once");
        gate.trimChoice(event(WORKER, BOOM, "a needle here"));
        assertEquals(1, service.hitCount(id));
    }

    @Test
    void eventWithoutThrowable_hasNoChoice() {
        trim(WORKER, null, 5);
        assertNull(gate.trimChoice(event(WORKER, null, "x")));
    }

    @Test
    void mostRestrictiveWins_asAtTheGate() {
        trim(WORKER, null, 5);
        String fewer = trim("com.acme", null, 2);
        assertEquals(fewer, gate.trimChoice(event(WORKER, BOOM, "x")).matchedRuleId());
        assertEquals(gate.evaluate(new Object(), event(WORKER, BOOM, "x")).trim().matchedRuleId(),
                gate.trimChoice(event(WORKER, BOOM, "x")).matchedRuleId());
    }

    @Test
    void nonMatchingMessage_hasNoChoice() {
        trim(WORKER, "needle", 5);
        assertNull(gate.trimChoice(event(WORKER, BOOM, "nothing")));
    }

    @Test
    void dropRuleOnly_hasNoTrimChoice() {
        service.addRuleDrop(WORKER, new CompiledMatchers(Level.INFO, "noisy", false, null, null, false),
                RuleAttachOptions.defaults(), SampleFullPolicy.disabled());
        assertNull(gate.trimChoice(event(WORKER, BOOM, "noisy")));
    }

    @Test
    void ruleResetAfterGate_rendersUntrimmed_butWasCounted() {
        String id = trim(WORKER, null, 5);
        assertNotNull(gate.evaluate(new Object(), event(WORKER, BOOM, "x")).trim());
        service.resetRule(id, false);
        assertNull(gate.trimChoice(event(WORKER, BOOM, "x")), "the split the spec accepts");
    }

    @Test
    void decision_isEqualToTheGates_inEveryField() {
        service.addRuleTrim(WORKER, new CompiledMatchers(Level.INFO, null, false, null, null, false),
                RuleAttachOptions.defaults(), 3, true);
        TrimDecision fromGate = gate.evaluate(new Object(), event(WORKER, BOOM, "x")).trim();
        assertEquals(fromGate, gate.trimChoice(event(WORKER, BOOM, "x")));
        assertEquals(3, fromGate.frames());
        assertEquals(true, fromGate.collapseCauses());
    }

    @Test
    void ruleOnTheParent_isInherited_andACutOffChildHasNone() {
        String id = trim("com.acme", null, 4);
        assertEquals(id, gate.trimChoice(event(WORKER, BOOM, "x")).matchedRuleId());
        service.setUseParentRules(WORKER, false);
        assertNull(gate.trimChoice(event(WORKER, BOOM, "x")));
    }

    @Test
    void levelBoundedMatcher_isHonoured() {
        service.addRuleTrim(WORKER, new CompiledMatchers(Level.DEBUG, null, false, null, null, false),
                RuleAttachOptions.defaults(), 5, false);
        assertNull(gate.trimChoice(event(WORKER, BOOM, "x")), "an INFO event is above the rule's DEBUG ceiling");
    }

    @Test
    void sampledThroughDrop_leavesATrimSurvivor() {
        service.addRuleDrop(WORKER, new CompiledMatchers(Level.INFO, "noisy", false, null, null, false),
                RuleAttachOptions.defaults(), new SampleFullPolicy(true, Duration.ofHours(1)));
        String id = trim(WORKER, "noisy", 5);
        GateVerdict first = gate.evaluate(new Object(), event(WORKER, BOOM, "noisy"));
        assertEquals(false, first.deny(), "the first match is always sampled through");
        assertEquals(id, first.trim().matchedRuleId());
        assertEquals(first.trim(), gate.trimChoice(event(WORKER, BOOM, "noisy")));
        assertEquals(1, service.hitCount(id));
    }
}
