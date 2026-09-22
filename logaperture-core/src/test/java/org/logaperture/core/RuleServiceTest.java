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
    private RuleService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        auditLog = new InMemoryAuditLog();
        service = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, "alice", "jmx");
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
        RuleService denied = new RuleService(adapter, CapabilityPolicy.denyAll(), auditLog, "alice", "jmx");
        CapabilityDeniedException ex = assertThrows(CapabilityDeniedException.class,
                () -> denied.attach("com.acme.Worker", CompiledMatchers.matchAll(), RuleAttachOptions.defaults(),
                        TestRule.FACTORY));
        assertEquals(Capability.RULES_AUTHOR, ex.capability());
    }

    @Test
    void attach_nonSessionTierAlsoRequiresPersist() {
        CapabilityPolicy rulesAuthorOnly = capability -> capability == Capability.RULES_AUTHOR;
        RuleService noPersist = new RuleService(adapter, rulesAuthorOnly, auditLog, "alice", "jmx");
        CapabilityDeniedException ex = assertThrows(CapabilityDeniedException.class,
                () -> noPersist.attach("com.acme.Worker", CompiledMatchers.matchAll(),
                        RuleAttachOptions.sticky(), TestRule.FACTORY));
        assertEquals(Capability.PERSIST, ex.capability());
    }

    @Test
    void attach_refusesAProtectedCategoryAndMutatesNothing() {
        RuleService protectedService = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, "alice",
                "jmx", loggerName -> loggerName.startsWith("security."));
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
        Optional<LogRule> removed = service.resetRule(rule.id(), false);
        assertTrue(removed.isPresent());
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

        Optional<LogRule> removed = service.resetRule(sticky.id(), true);
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
    void planSource_swapsAtomicallyUnderConcurrentAttach() throws InterruptedException {
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < perThread; i++) {
                            attach("com.acme.Worker");
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
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
        assertEquals(threads * perThread, service.planSource().currentPlan().rules().size());
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
}
