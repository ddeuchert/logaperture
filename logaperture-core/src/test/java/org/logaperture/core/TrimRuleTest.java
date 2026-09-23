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
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.Trim;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/trim-rule.md "Testing" -- {@code Trim}-specific coverage on top
 * of what {@link RuleServiceTest} and {@link DropRuleTest} already prove
 * generically. {@link RuleMatching} itself is exercised by {@link
 * DropRuleTest} against the shared matcher library and isn't re-tested here.
 */
class TrimRuleTest {

    private FakeLoggingAdapter adapter;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private RuleService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore, "system", "alice",
                "jmx");
    }

    private RuleCandidateEvent event(String loggerName, Level level, String message, Throwable thrown) {
        return new RuleCandidateEvent(loggerName, level, thrown, () -> message, Instant.now());
    }

    private Trim attachTrim(String loggerName, CompiledMatchers matchers, int frames, boolean collapseCauses) {
        return (Trim) service.attach(loggerName, matchers, RuleAttachOptions.defaults(),
                TrimFactories.attach(frames, collapseCauses), Capability.SUPPRESS);
    }

    // --- Gate evaluation (RuleService.gate()) --------------------------------------------------

    @Test
    void gate_matchingTrim_carriesTheDecision_neverDenies() {
        Trim trim = attachTrim("com.acme.Worker", new CompiledMatchers(Level.WARN, null, false, null, null, false),
                3, false);

        GateVerdict verdict = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.INFO, "m", new RuntimeException()));

        assertFalse(verdict.deny());
        assertEquals(trim.id(), verdict.trim().matchedRuleId());
        assertEquals(3, verdict.trim().frames());
        assertFalse(verdict.trim().collapseCauses());
    }

    @Test
    void gate_noMatchingTrim_carriesNoDecision() {
        attachTrim("com.acme.Worker", new CompiledMatchers(Level.WARN, null, false, null, null, false), 0, false);

        GateVerdict verdict = service.gate().evaluate(new Object(),
                event("other.Logger", Level.INFO, "m", new RuntimeException()));

        assertFalse(verdict.deny());
        assertNull(verdict.trim());
    }

    @Test
    void gate_severeEnoughEvent_isSparedTheTrim() {
        attachTrim("com.acme.Worker", new CompiledMatchers(Level.WARN, null, false, null, null, false), 0, false);

        GateVerdict verdict = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.ERROR, "m", new RuntimeException()));

        assertNull(verdict.trim(), "ERROR is spared by a below-WARN bound");
    }

    @Test
    void gate_severalMatchingTrims_mostRestrictiveWins() {
        attachTrim("com.acme.Worker", CompiledMatchers.matchAll(), 5, false);
        Trim mostRestrictive = attachTrim("com.acme.Worker", CompiledMatchers.matchAll(), 1, false);
        attachTrim("com.acme.Worker", CompiledMatchers.matchAll(), 3, false);

        GateVerdict verdict = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.INFO, "m", new RuntimeException()));

        assertEquals(mostRestrictive.id(), verdict.trim().matchedRuleId());
        assertEquals(1, verdict.trim().frames());
    }

    @Test
    void gate_dropDeniedEvent_carriesNoTrimDecision() {
        service.attach("com.acme.Worker", new CompiledMatchers(Level.WARN, "noisy", false, null, null, false),
                RuleAttachOptions.defaults(), DropFactories.attach(SampleFullPolicy.disabled()),
                Capability.SUPPRESS);
        attachTrim("com.acme.Worker", CompiledMatchers.matchAll(), 0, false);

        GateVerdict verdict = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.INFO, "noisy", null));

        assertTrue(verdict.deny(), "a dropped event is never trimmed -- doc/specs/trim-rule.md \"What trim means\"");
        assertNull(verdict.trim());
    }

    @Test
    void gate_matchingTrim_incrementsItsHitCount() {
        Trim trim = attachTrim("com.acme.Worker", CompiledMatchers.matchAll(), 0, false);

        service.gate().evaluate(new Object(), event("com.acme.Worker", Level.INFO, "m", new RuntimeException()));
        service.gate().evaluate(new Object(), event("com.acme.Worker", Level.INFO, "m", new RuntimeException()));

        assertEquals(2L, service.hitCount(trim.id()));
    }

    // --- Capability -----------------------------------------------------------------------------

    @Test
    void addRuleTrim_requiresSuppressInAdditionToRulesAuthor() {
        CapabilityPolicy rulesAuthorOnly = capability -> capability == Capability.RULES_AUTHOR;
        RuleService noSuppress = new RuleService(adapter, rulesAuthorOnly, auditLog, stateStore, "system", "alice",
                "jmx");
        CapabilityDeniedException ex = assertThrows(CapabilityDeniedException.class,
                () -> noSuppress.addRuleTrim("com.acme.Worker", CompiledMatchers.matchAll(),
                        RuleAttachOptions.defaults(), 0, false));
        assertEquals(Capability.SUPPRESS, ex.capability());
    }

    @Test
    void addRuleTrim_attachesAndReturnsAContextTaggedView() {
        RuleView created = service.addRuleTrim("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.defaults(), 2, true);
        assertEquals("system", created.context());
        assertEquals("trim", created.rule().actionName());
        assertEquals(1, service.listRules().size());
    }

    @Test
    void addRuleTrim_noContentMatcherRequired_unlikeDrop() {
        // doc/specs/trim-rule.md "Matchers in use": a bare level-bounded trim is a first-class
        // case -- CompiledMatchers.matchAll() (no logger/message/throwable constraint beyond
        // attachment) is a perfectly legal Trim, unlike Drop.
        RuleView created = service.addRuleTrim("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.defaults(), 0, false);
        assertEquals("trim", created.rule().actionName());
    }

    // --- Data model -------------------------------------------------------------------------------

    @Test
    void trim_rejectsNegativeFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> new Trim("r1", "x", CompiledMatchers.matchAll(), null, PersistenceTier.SESSION, null,
                        Instant.now(), -1, false));
    }

    // --- Persistence (payload round trip) --------------------------------------------------------

    @Test
    void persistedPayload_roundTripsFramesAndCollapseCausesThroughResume() {
        Trim original = attachTrim("com.acme.Worker", CompiledMatchers.matchAll(), 4, true);
        service.resetRule(original.id(), false); // clear the in-memory registry, not the (never-persisted-yet) store

        RuleService resumed = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore, "system",
                "alice", "jmx");
        resumed.registerTrimSupport();
        // Simulate what a STICKY attach would have persisted -- this rule was SESSION, so build the
        // persisted row directly rather than re-attaching with a STICKY tier just to exercise resume.
        stateStore.saveRule(new org.logaperture.api.PersistedRule(original.id(), original.loggerName(), "trim",
                original.matchers(), null, PersistenceTier.STICKY, null, original.createdAt(), "system",
                original.persistedPayload()));

        resumed.resumeFromStateStore(Instant.now());

        Trim resumedRule = (Trim) resumed.find(original.id()).orElseThrow();
        assertEquals(4, resumedRule.frames());
        assertTrue(resumedRule.collapseCauses());
    }
}
