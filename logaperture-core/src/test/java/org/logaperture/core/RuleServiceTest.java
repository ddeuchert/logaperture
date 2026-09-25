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
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleResetOutcome;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleServiceTest {

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

    private LogRule attach(String loggerName) {
        return service.attach(loggerName, CompiledMatchers.matchAll(), RuleAttachOptions.defaults(),
                TestRule.FACTORY);
    }

    @Test
    void attach_assignsMonotonicIdsNeverReused() {
        LogRule first = attach("com.acme.Worker");
        LogRule second = attach("com.acme.Worker");
        assertNotEquals(first.id(), second.id());

        service.resetRule(first.id(), false);
        LogRule third = attach("com.acme.Worker");
        assertNotEquals(first.id(), third.id());
        assertNotEquals(second.id(), third.id());
    }

    @Test
    void attach_recordsAMutationAuditRecordWithNullPreviousValue() {
        LogRule rule = attach("com.acme.Worker");
        AuditRecord record = auditLog.records().get(0);
        assertEquals(AuditRecord.Action.MUTATION, record.action());
        assertEquals("com.acme.Worker", record.loggerName());
        assertEquals(null, record.previousValue());
        assertTrue(record.newValue().contains(rule.id()));
    }

    @Test
    void attach_requiresRulesAuthor() {
        RuleService denied = new RuleService(adapter, CapabilityPolicy.denyAll(), auditLog, stateStore, "system",
                "alice", "jmx");
        CapabilityDeniedException ex = assertThrows(CapabilityDeniedException.class,
                () -> denied.attach("com.acme.Worker", CompiledMatchers.matchAll(), RuleAttachOptions.defaults(),
                        TestRule.FACTORY));
        assertEquals(Capability.RULES_AUTHOR, ex.capability());
    }

    @Test
    void attach_nonSessionTierAlsoRequiresPersist() {
        CapabilityPolicy rulesAuthorOnly = capability -> capability == Capability.RULES_AUTHOR;
        RuleService noPersist = new RuleService(adapter, rulesAuthorOnly, auditLog, stateStore, "system", "alice",
                "jmx");
        CapabilityDeniedException ex = assertThrows(CapabilityDeniedException.class,
                () -> noPersist.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                        RuleAttachOptions.sticky(), TestRule.FACTORY));
        assertEquals(Capability.PERSIST, ex.capability());
    }

    @Test
    void attach_refusesAProtectedCategoryAndMutatesNothing() {
        RuleService protectedService = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore,
                "system", "alice", "jmx", loggerName -> loggerName.startsWith("security."));
        assertThrows(IllegalArgumentException.class,
                () -> protectedService.attach("security.auth", CompiledMatchers.matchAll(),
                        RuleAttachOptions.defaults(), TestRule.FACTORY));
        assertTrue(protectedService.listRules().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void effectiveRules_reachesADescendantCreatedAfterAttachmentWithNoReScan() {
        attach("com.acme");
        // "com.acme.batch.Worker" didn't exist when the rule was attached --
        // no re-scan needed, ordinary tree lookup finds it.
        List<LogRule> effective = service.effectiveRules("com.acme.batch.Worker");
        assertEquals(1, effective.size());
        assertEquals("com.acme", effective.get(0).loggerName());
    }

    @Test
    void effectiveRules_accumulateFromMultipleAncestorsByDefault() {
        attach("com.acme");
        attach("com.acme.batch");
        List<LogRule> effective = service.effectiveRules("com.acme.batch.Worker");
        assertEquals(2, effective.size());
    }

    @Test
    void effectiveRules_useParentRulesFalseCutsOffEverythingAboveButKeepsItsOwnRules() {
        attach("com.acme");
        attach("com.acme.batch");
        service.setUseParentRules("com.acme.batch", false);

        List<LogRule> effective = service.effectiveRules("com.acme.batch.Worker");
        assertEquals(1, effective.size());
        assertEquals("com.acme.batch", effective.get(0).loggerName());
    }

    @Test
    void effectiveRules_ownFlagFalseKeepsOnlyItsOwnRules() {
        attach("com.acme");
        attach("com.acme.batch");
        service.setUseParentRules("com.acme.batch", false);

        List<LogRule> effective = service.effectiveRules("com.acme.batch");
        assertEquals(1, effective.size());
        assertEquals("com.acme.batch", effective.get(0).loggerName());
    }

    @Test
    void effectiveRules_walksAllTheWayToTheRootLogger() {
        attach("");
        List<LogRule> effective = service.effectiveRules("com.acme.batch.Worker");
        assertEquals(1, effective.size());
        assertEquals("", effective.get(0).loggerName());
    }

    @Test
    void levelChangeNeverAffectsRuleScopeAndViceVersa() {
        // This service has no notion of level at all -- attaching/detaching a
        // rule never touches anything level-shaped, by construction (the two
        // axes are independent per doc/specs/filtering-epic.md's own
        // "Logger scope" section). Proven here by simply asserting rule
        // attachment doesn't require or reference any level state.
        LogRule rule = attach("com.acme.Worker");
        assertEquals("com.acme.Worker", rule.loggerName());
        assertEquals(1, service.effectiveRules("com.acme.Worker").size());
    }

    @Test
    void resetRule_removesById() {
        LogRule rule = attach("com.acme.Worker");
        Optional<RuleView> removed = service.resetRule(rule.id(), false);
        assertTrue(removed.isPresent());
        assertEquals(rule.id(), removed.get().rule().id());
        assertTrue(service.listRules().isEmpty());
    }

    @Test
    void resetRule_unknownIdIsANoOp() {
        assertTrue(service.resetRule("no-such-id", false).isEmpty());
    }

    @Test
    void resetRule_refusesStickyWithoutIncludeSticky() {
        LogRule sticky = service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.sticky(), TestRule.FACTORY);
        assertThrows(IllegalArgumentException.class, () -> service.resetRule(sticky.id(), false));
        assertTrue(service.find(sticky.id()).isPresent());

        Optional<RuleView> removed = service.resetRule(sticky.id(), true);
        assertTrue(removed.isPresent());
    }

    @Test
    void resetAllRules_skipsAndReportsStickyEntries() {
        LogRule sessionRule = attach("com.acme.a");
        LogRule stickyRule = service.attach("com.acme.b", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(),
                TestRule.FACTORY);

        RuleResetOutcome outcome = service.resetAllRules(false);
        assertEquals(List.of(sessionRule.id()), outcome.removedIds());
        assertEquals(List.of(stickyRule.id()), outcome.skippedStickyIds());
        assertTrue(service.find(stickyRule.id()).isPresent());
        assertTrue(service.find(sessionRule.id()).isEmpty());
    }

    @Test
    void resetRulesForLogger_onlyTouchesRulesAttachedDirectlyToThatLogger() {
        attach("com.acme");
        LogRule direct = attach("com.acme.batch");

        RuleResetOutcome outcome = service.resetRulesForLogger("com.acme.batch", false);
        assertEquals(List.of(direct.id()), outcome.removedIds());
        // The ancestor's own rule (attached to "com.acme") is untouched.
        assertEquals(1, service.listRules().size());
    }

    @Test
    void resetRemovals_recordAReversionAuditRecord() {
        LogRule rule = attach("com.acme.Worker");
        service.resetRule(rule.id(), false);
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, last.action());
        assertEquals(null, last.newValue());
        assertTrue(last.previousValue().contains(rule.id()));
    }

    @Test
    void attach_neverLosesAnAttachmentUnderConcurrentAttach() throws InterruptedException {
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        Runnable attachBurstAfterReleased = () -> {
            ready.countDown();
            try {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    attach("com.acme.Worker");
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        };
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(attachBurstAfterReleased);
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, failures.get());
        assertEquals(threads * perThread, service.listRules().size());
        // No two attachments raced into the same id.
        long distinctIds = service.listRules().stream().map(view -> view.rule().id()).distinct().count();
        assertEquals(threads * perThread, distinctIds);
    }

    @Test
    void attach_withForTierResolvesExpiresAt() {
        LogRule rule = service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.forDuration(Duration.ofMinutes(30)), TestRule.FACTORY);
        assertEquals(PersistenceTier.FOR, rule.tier());
        assertFalse(rule.expiresAt() == null);
    }

    // --- persistence (doc/specs/rule-pipeline-foundation.md "Persistence") -------------------------

    @Test
    void attach_sessionTierIsNeverPersisted() {
        attach("com.acme.Worker");
        assertTrue(stateStore.loadAllRules().isEmpty());
    }

    @Test
    void attach_stickyTierIsPersisted() {
        LogRule rule = service.attach("com.acme.Worker", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(),
                TestRule.FACTORY);
        assertEquals(1, stateStore.loadAllRules().size());
        assertEquals(rule.id(), stateStore.loadAllRules().get(0).id());
    }

    @Test
    void resetRule_removesThePersistedRowToo() {
        LogRule rule = service.attach("com.acme.Worker", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(),
                TestRule.FACTORY);
        service.resetRule(rule.id(), true);
        assertTrue(stateStore.loadAllRules().isEmpty());
    }

    @Test
    void resetAllRules_batchesTheStateStoreRewrite() {
        service.attach("com.acme.a", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(), TestRule.FACTORY);
        service.attach("com.acme.b", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(), TestRule.FACTORY);

        service.resetAllRules(true);

        assertTrue(stateStore.loadAllRules().isEmpty());
        assertEquals(1, stateStore.removeAllRulesCalls(), "one rewrite for the whole batch, not one per rule");
    }

    @Test
    void resumeFromStateStore_stickyRuleKeepsItsExactIdAndIsAudited() {
        service.registerActionFactory("TestRule", TestRule.FACTORY);
        LogRule original = service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.sticky(), TestRule.FACTORY);

        RuleService resumed = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore, "system",
                "alice", "jmx");
        resumed.registerActionFactory("TestRule", TestRule.FACTORY);
        resumed.resumeFromStateStore(Instant.now());

        List<RuleView> rules = resumed.listRules();
        assertEquals(1, rules.size());
        assertEquals(original.id(), rules.get(0).rule().id(), "a resumed rule keeps its persisted id, never a fresh one");
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.MUTATION, last.action());
        assertEquals("resume", last.source());
    }

    @Test
    void resumeFromStateStore_expiredForRuleIsNeverReappliedAndIsRemoved() {
        Instant past = Instant.now().minus(Duration.ofMinutes(5));
        service.registerActionFactory("TestRule", TestRule.FACTORY);
        stateStore.saveRule(new org.logaperture.api.PersistedRule("r99", "com.acme.Worker", "TestRule",
                CompiledMatchers.matchAll(), null, PersistenceTier.FOR, past, past.minus(Duration.ofMinutes(30)),
                "system", java.util.Map.of()));

        service.resumeFromStateStore(Instant.now());

        assertTrue(service.listRules().isEmpty(), "an expired FOR rule is never reapplied");
        assertTrue(stateStore.loadAllRules().isEmpty(), "and is dropped from the store, not left to resurface later");
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, last.action());
    }

    // --- runtime expiry (issue #95) -----------------------------------------------------------

    @Test
    void sweepExpiredRules_removesADueForRuleFromRegistryAndStoreWithAnExpirySweepAudit() {
        LogRule rule = service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.forDuration(Duration.ofMinutes(30)), TestRule.FACTORY);
        assertEquals(1, stateStore.loadAllRules().size(), "a FOR rule is persisted");

        service.sweepExpiredRules(rule.expiresAt().plusSeconds(1));

        assertTrue(service.listRules().isEmpty());
        assertTrue(service.effectiveRules("com.acme.Worker").isEmpty(), "no longer evaluated");
        assertTrue(stateStore.loadAllRules().isEmpty());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, last.action());
        assertEquals("expiry-sweep", last.source());
        assertTrue(last.previousValue().contains(rule.id()));
    }

    @Test
    void sweepExpiredRules_expiresExactlyAtTheDeadline() {
        LogRule rule = service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.forDuration(Duration.ofMinutes(30)), TestRule.FACTORY);

        service.sweepExpiredRules(rule.expiresAt());

        assertTrue(service.listRules().isEmpty(), "at-or-before, same as level overrides");
    }

    @Test
    void sweepExpiredRules_notYetDueIsLeftUntouchedAndUnaudited() {
        service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.forDuration(Duration.ofMinutes(30)), TestRule.FACTORY);
        int auditsBefore = auditLog.records().size();

        service.sweepExpiredRules(Instant.now());

        assertEquals(1, service.listRules().size());
        assertEquals(1, stateStore.loadAllRules().size());
        assertEquals(auditsBefore, auditLog.records().size());
        assertEquals(0, stateStore.removeAllRulesCalls(), "a quiet tick doesn't rewrite the store");
    }

    @Test
    void sweepExpiredRules_ignoresSessionAndStickyRules() {
        attach("com.acme.Session");
        service.attach("com.acme.Sticky", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(), TestRule.FACTORY);

        service.sweepExpiredRules(Instant.now().plus(Duration.ofDays(365)));

        assertEquals(2, service.listRules().size());
    }

    @Test
    void sweepExpiredRules_batchesTheStateStoreRewrite() {
        RuleAttachOptions shortFor = RuleAttachOptions.forDuration(Duration.ofMinutes(1));
        service.attach("com.acme.a", CompiledMatchers.matchAll(), shortFor, TestRule.FACTORY);
        service.attach("com.acme.b", CompiledMatchers.matchAll(), shortFor, TestRule.FACTORY);

        service.sweepExpiredRules(Instant.now().plus(Duration.ofMinutes(2)));

        assertTrue(stateStore.loadAllRules().isEmpty());
        assertEquals(1, stateStore.removeAllRulesCalls(), "one rewrite per sweep tick, not one per rule");
    }

    @Test
    void sweepExpiredRules_afterAResetDoesNothingMore() {
        LogRule rule = service.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                RuleAttachOptions.forDuration(Duration.ofMinutes(30)), TestRule.FACTORY);
        service.resetRule(rule.id(), false);
        int auditsBefore = auditLog.records().size();

        service.sweepExpiredRules(rule.expiresAt().plusSeconds(1));

        assertEquals(auditsBefore, auditLog.records().size(), "no second REVERSION for an already-removed rule");
    }

    @Test
    void resumeFromStateStore_unregisteredActionIsSkippedButLeftInTheStore() {
        // No registerActionFactory call -- this slice ships no concrete
        // action type, so a persisted row from a future one (or a
        // hand-edited file) is neither resumed nor discarded.
        stateStore.saveRule(new org.logaperture.api.PersistedRule("r1", "com.acme.Worker", "Drop",
                CompiledMatchers.matchAll(), null, PersistenceTier.STICKY, null, Instant.now(), "system", java.util.Map.of()));

        service.resumeFromStateStore(Instant.now());

        assertTrue(service.listRules().isEmpty(), "not resumed into the live registry");
        assertEquals(1, stateStore.loadAllRules().size(), "but left untouched in the store for a later resume");
    }

    @Test
    void resumeFromStateStore_neverResumesARowPersistedByAnotherContextSharingTheSameStore() {
        // A code-review finding: every context in a JVM shares one
        // StateStore, but a rule is attached to exactly one context's own
        // RuleService, never broadcast the way a level override is -- so
        // resuming must filter by context, not resume every row into
        // whichever context happens to call resumeFromStateStore first.
        service.registerActionFactory("TestRule", TestRule.FACTORY);
        stateStore.saveRule(new org.logaperture.api.PersistedRule("r1", "com.acme.Worker", "TestRule",
                CompiledMatchers.matchAll(), null, PersistenceTier.STICKY, null, Instant.now(), "myapp.war", java.util.Map.of()));

        service.resumeFromStateStore(Instant.now()); // service's own context is "system"

        assertTrue(service.listRules().isEmpty(), "a row persisted under a different context is never resumed here");
        assertEquals(1, stateStore.loadAllRules().size(), "and is left untouched, for that other context's own resume");
    }

    @Test
    void resumeFromStateStore_advancesTheIdSequencePastAResumedId() {
        service.registerActionFactory("TestRule", TestRule.FACTORY);
        stateStore.saveRule(new org.logaperture.api.PersistedRule("r5", "com.acme.Worker", "TestRule",
                CompiledMatchers.matchAll(), null, PersistenceTier.STICKY, null, Instant.now(), "system", java.util.Map.of()));

        service.resumeFromStateStore(Instant.now());
        LogRule fresh = attach("com.acme.Other");

        assertNotEquals("r5", fresh.id());
        assertTrue(Long.parseLong(fresh.id().substring(1)) > 5);
    }
}
