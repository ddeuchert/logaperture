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
import org.logaperture.api.LogRule;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleChange;
import org.logaperture.api.RuleChange.Field;
import org.logaperture.api.SampleFullPolicy;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/rule-pipeline-foundation.md "Evaluation cost" (issue #130): the
 * fast paths must never change a verdict, and the per-logger resolution cache
 * must follow every kind of registry change.
 */
class RuleGateFastPathTest {

    private static final String WORKER = "com.acme.Worker";

    private RuleService service;
    private RuleGate gate;

    @BeforeEach
    void setUp() {
        service = new RuleService(new FakeLoggingAdapter(Level.INFO), CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), new InMemoryStateStore(), "system", "alice", "jmx");
        gate = service.gate();
    }

    private LogRule drop(String loggerName, String message) {
        return service.attach(loggerName, new CompiledMatchers(Level.WARN, message, false, null, null, false),
                RuleAttachOptions.defaults(), DropFactories.attach(SampleFullPolicy.disabled()), Capability.SUPPRESS);
    }

    private GateVerdict evaluate(String loggerName, String message) {
        return gate.evaluate(new Object(),
                new RuleCandidateEvent(loggerName, Level.INFO, null, () -> message, Instant.now()));
    }

    @Test
    void noRules_appliesToNothing_andAllows() {
        assertFalse(gate.appliesTo(WORKER));
        assertFalse(gate.appliesTo(""));
        assertSame(GateVerdict.allow(), evaluate(WORKER, "noisy"));
    }

    @Test
    void ruleElsewhere_appliesOnlyToItsSubtree() {
        drop("com.acme", "noisy");
        assertTrue(gate.appliesTo("com.acme"));
        assertTrue(gate.appliesTo(WORKER), "inherited from the parent");
        assertFalse(gate.appliesTo("org.other.Thing"));
        assertSame(GateVerdict.allow(), evaluate("org.other.Thing", "noisy"));
        assertTrue(evaluate(WORKER, "noisy").deny());
    }

    @Test
    void resettingTheLastRule_turnsTheFastPathBackOn() {
        LogRule rule = drop(WORKER, "noisy");
        assertTrue(gate.appliesTo(WORKER)); // resolves and caches the one-rule list
        service.resetRule(rule.id(), false);
        assertFalse(gate.appliesTo(WORKER), "a cached list must not outlive the rule");
        assertFalse(evaluate(WORKER, "noisy").deny());
    }

    @Test
    void attachingAfterACachedEmptyAnswer_isSeen() {
        drop("org.other", "x"); // so the context has a rule, and WORKER's empty list gets cached
        assertFalse(gate.appliesTo(WORKER));
        drop(WORKER, "noisy");
        assertTrue(gate.appliesTo(WORKER));
        assertTrue(evaluate(WORKER, "noisy").deny());
    }

    @Test
    void useParentRulesChange_invalidatesTheCache() {
        drop("com.acme", "noisy");
        assertTrue(gate.appliesTo(WORKER));
        service.setUseParentRules(WORKER, false);
        assertFalse(gate.appliesTo(WORKER), "cut off from the parent's rule");
        service.setUseParentRules(WORKER, true);
        assertTrue(gate.appliesTo(WORKER));
    }

    @Test
    void alteringARule_invalidatesTheCache() {
        LogRule rule = drop(WORKER, "noisy");
        assertTrue(evaluate(WORKER, "noisy").deny());
        RuleChange toOther = new RuleChange(Field.set("other"), false, null, null, null, null, null, null, null, null);
        service.alterRule(rule.id(), toOther, null, null).orElseThrow();
        assertFalse(evaluate(WORKER, "noisy").deny(), "the old matcher must not survive in the cache");
        assertTrue(evaluate(WORKER, "other").deny());
    }

    @Test
    void manyLoggerNames_pastTheCacheBound_stillResolveCorrectly() {
        drop("com.acme", "noisy");
        for (int i = 0; i < RuleService.MAX_RESOLVED_LOGGERS + 100; i++) {
            assertFalse(gate.appliesTo("org.other.L" + i));
        }
        assertTrue(gate.appliesTo(WORKER));
        assertTrue(evaluate(WORKER, "noisy").deny());
    }

    @Test
    void siblingHandlers_stillCountOneHitPerEvent() {
        LogRule rule = drop(WORKER, "noisy");
        Object record = new Object();
        RuleCandidateEvent event = new RuleCandidateEvent(WORKER, Level.INFO, null, () -> "noisy", Instant.now());
        for (int handler = 0; handler < 4; handler++) { // two filters, two trim formatters
            assertTrue(gate.evaluate(record, event).deny());
        }
        assertEquals(1L, service.hitCount(rule.id()));
    }
}
