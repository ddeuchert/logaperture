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
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/drop-rule.md "Testing" -- {@code Drop}-specific coverage on top
 * of what rule-pipeline-foundation.md's own {@link RuleServiceTest} already
 * proves generically (attach/list/reset machinery, tree inheritance,
 * capability/floor checks against a test double).
 */
class DropRuleTest {

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

    // --- RuleMatching -------------------------------------------------------------------------

    @Test
    void matches_levelBound_sparesTheBoundLevelAndAbove() {
        CompiledMatchers atMostWarn = new CompiledMatchers(Level.WARN, null, false, null, null, false);
        assertTrue(RuleMatching.matches(atMostWarn, event("x", Level.WARN, "m", null)));
        assertTrue(RuleMatching.matches(atMostWarn, event("x", Level.INFO, "m", null)));
        assertFalse(RuleMatching.matches(atMostWarn, event("x", Level.ERROR, "m", null)), "ERROR is spared");
    }

    @Test
    void matches_messageContains_caseSensitiveByDefault() {
        CompiledMatchers m = new CompiledMatchers(null, "This happens a lot", false, null, null, false);
        assertTrue(RuleMatching.matches(m, event("x", Level.INFO, "This happens a lot, again", null)));
        assertFalse(RuleMatching.matches(m, event("x", Level.INFO, "THIS HAPPENS A LOT", null)));
    }

    @Test
    void matches_messageContainsIgnoreCase() {
        CompiledMatchers m = new CompiledMatchers(null, "This happens a lot", true, null, null, false);
        assertTrue(RuleMatching.matches(m, event("x", Level.INFO, "THIS HAPPENS A LOT today", null)));
    }

    @Test
    void matches_throwableType_subclassesIncluded() {
        CompiledMatchers m = new CompiledMatchers(null, null, false, "java.io.IOException", null, false);
        assertTrue(RuleMatching.matches(m, event("x", Level.INFO, "m", new java.net.ConnectException("refused"))));
        assertFalse(RuleMatching.matches(m, event("x", Level.INFO, "m", new IllegalStateException())));
        assertFalse(RuleMatching.matches(m, event("x", Level.INFO, "m", null)), "no throwable at all -- no match");
    }

    @Test
    void matches_throwableMessageContains() {
        CompiledMatchers m = new CompiledMatchers(null, null, false, null, "Failed to connect", false);
        assertTrue(RuleMatching.matches(m,
                event("x", Level.INFO, "m", new RuntimeException("Failed to connect to URL"))));
        assertFalse(RuleMatching.matches(m, event("x", Level.INFO, "m", new RuntimeException("unrelated"))));
    }

    @Test
    void matches_anyCause_walksTheCauseChain() {
        CompiledMatchers m = new CompiledMatchers(null, null, false, "java.net.ConnectException", null, true);
        Throwable cause = new java.net.ConnectException("refused");
        Throwable top = new RuntimeException("wrapper", cause);
        assertFalse(RuleMatching.matches(new CompiledMatchers(null, null, false, "java.net.ConnectException", null,
                false), event("x", Level.INFO, "m", top)), "top-level only -- the wrapper isn't a ConnectException");
        assertTrue(RuleMatching.matches(m, event("x", Level.INFO, "m", top)), "any-cause reaches the wrapped cause");
    }

    @Test
    void matches_allSetFieldsMustHold() {
        CompiledMatchers m = new CompiledMatchers(Level.WARN, "noisy", false, "java.lang.RuntimeException", null,
                false);
        assertTrue(RuleMatching.matches(m, event("x", Level.INFO, "very noisy indeed", new RuntimeException())));
        assertFalse(RuleMatching.matches(m, event("x", Level.INFO, "quiet", new RuntimeException())),
                "message doesn't match");
        assertFalse(RuleMatching.matches(m, event("x", Level.ERROR, "very noisy indeed", new RuntimeException())),
                "level doesn't match -- ERROR is spared");
    }

    // --- Gate evaluation (RuleService.gate()) --------------------------------------------------

    private Drop attachDrop(String loggerName, CompiledMatchers matchers, SampleFullPolicy sampleFull) {
        return (Drop) service.attach(loggerName, matchers, RuleAttachOptions.defaults(),
                DropFactories.attach(sampleFull), Capability.SUPPRESS);
    }

    @Test
    void gate_deniesAMatchingEvent_allowsAnNonMatchingOne() {
        attachDrop("com.acme.Worker", new CompiledMatchers(Level.WARN, "noisy", false, null, null, false),
                SampleFullPolicy.disabled());

        GateVerdict denied = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.INFO, "noisy again", null));
        assertTrue(denied.deny());

        GateVerdict allowed = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.INFO, "unrelated", null));
        assertFalse(allowed.deny());
    }

    @Test
    void gate_reusesTheCachedVerdictForTheSameRecordIdentity_notRecomputed() {
        attachDrop("com.acme.Worker", new CompiledMatchers(Level.WARN, "noisy", false, null, null, false),
                SampleFullPolicy.disabled());
        Object recordIdentity = new Object();
        RuleCandidateEvent event = event("com.acme.Worker", Level.INFO, "noisy", null);

        GateVerdict first = service.gate().evaluate(recordIdentity, event);
        GateVerdict second = service.gate().evaluate(recordIdentity, event);

        assertEquals(first, second);
        // Only one hit recorded -- the second call was served from the identity cache, not
        // re-evaluated (doc/specs/rule-pipeline-foundation.md "Evaluation": per event, not per
        // handler -- the same mechanism this cache generalizes to any number of sibling handlers).
        assertEquals(1L, service.hitCount(service.listRules().get(0).rule().id()));
    }

    @Test
    void gate_neverDeniesFatal_evenWithBelowFatalRequested() {
        // "--below FATAL" resolves client-side to levelAtMost == ERROR (doc/specs/drop-rule.md
        // "Divergence from prior specs" -- this codebase's Level has no FATAL member); this proves
        // the practical effect: an ERROR event is now droppable, matching the epic's own "a rule
        // may set --below FATAL to include ERROR".
        attachDrop("com.acme.Worker", new CompiledMatchers(Level.ERROR, "noisy", false, null, null, false),
                SampleFullPolicy.disabled());

        GateVerdict verdict = service.gate().evaluate(new Object(),
                event("com.acme.Worker", Level.ERROR, "noisy", null));
        assertTrue(verdict.deny());
    }

    @Test
    void sampleFull_firstMatchIsAlwaysKept_thenDeniedUntilTheIntervalElapses() {
        Drop drop = attachDrop("com.acme.Worker", new CompiledMatchers(Level.WARN, "noisy", false, null, null,
                false), new SampleFullPolicy(true, Duration.ofHours(1)));
        RuleCandidateEvent noisy = event("com.acme.Worker", Level.INFO, "noisy", null);

        assertFalse(service.gate().evaluate(new Object(), noisy).deny(), "the very first match is always sampled through");
        assertTrue(service.gate().evaluate(new Object(), noisy).deny(), "the next one, well inside the interval, is denied");
        assertTrue(service.gate().evaluate(new Object(), noisy).deny());
        assertEquals(3L, service.hitCount(drop.id()), "a sampled-through match still counts as a hit");
    }

    @Test
    void sampleFull_disabled_neverSamples() {
        attachDrop("com.acme.Worker", new CompiledMatchers(Level.WARN, "noisy", false, null, null, false),
                SampleFullPolicy.disabled());
        RuleCandidateEvent noisy = event("com.acme.Worker", Level.INFO, "noisy", null);

        assertTrue(service.gate().evaluate(new Object(), noisy).deny(), "no exception even for the first match");
    }

    // --- Capability -----------------------------------------------------------------------------

    @Test
    void addRuleDrop_requiresSuppressInAdditionToRulesAuthor() {
        CapabilityPolicy rulesAuthorOnly = capability -> capability == Capability.RULES_AUTHOR;
        RuleService noSuppress = new RuleService(adapter, rulesAuthorOnly, auditLog, stateStore, "system", "alice",
                "jmx");
        CapabilityDeniedException ex = assertThrows(CapabilityDeniedException.class,
                () -> noSuppress.addRuleDrop("com.acme.Worker",
                        new CompiledMatchers(Level.WARN, "noisy", false, null, null, false),
                        RuleAttachOptions.defaults(), SampleFullPolicy.defaults()));
        assertEquals(Capability.SUPPRESS, ex.capability());
    }

    @Test
    void addRuleDrop_attachesAndReturnsAContextTaggedView() {
        RuleView created = service.addRuleDrop("com.acme.Worker",
                new CompiledMatchers(Level.WARN, "noisy", false, null, null, false), RuleAttachOptions.defaults(),
                SampleFullPolicy.defaults());
        assertEquals("system", created.context());
        assertEquals("drop", created.rule().actionName());
        assertEquals(1, service.listRules().size());
    }

    // --- Persistence (payload round trip) --------------------------------------------------------

    @Test
    void persistedPayload_roundTripsSampleFullThroughResume() {
        SampleFullPolicy sampleFull = new SampleFullPolicy(false, Duration.ofMinutes(17));
        Drop original = attachDrop("com.acme.Worker", CompiledMatchers.matchAll(), sampleFull);
        service.resetRule(original.id(), false); // clear the in-memory registry, not the (never-persisted-yet) store

        RuleService resumed = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore, "system",
                "alice", "jmx");
        resumed.registerDropSupport();
        // Simulate what a STICKY attach would have persisted -- this rule was SESSION, so build the
        // row directly rather than re-attaching as STICKY (payload encoding is what's under test).
        org.logaperture.api.PersistedRule persisted = new org.logaperture.api.PersistedRule(
                "r99", "com.acme.Worker", "drop", CompiledMatchers.matchAll(), null, PersistenceTier.STICKY, null,
                Instant.now(), "system", original.persistedPayload());
        stateStore.saveRule(persisted);

        resumed.resumeFromStateStore(Instant.now());

        Drop resumedDrop = (Drop) resumed.listRules().get(0).rule();
        assertEquals(sampleFull, resumedDrop.sampleFull());
    }
}
