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
import org.logaperture.api.PersistedRule;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.spi.ContextHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/rule-multi-context.md -- {@code add rule} reaches every context, under one id (M1, M3, M6). */
class AggregateRuleFanOutTest {

    private static final CompiledMatchers MATCHERS = new CompiledMatchers(Level.ERROR, "noise", false, null, null, false);
    private static final RuleAttachOptions FOR_AN_HOUR =
            new RuleAttachOptions(null, Duration.ofHours(1), PersistenceTier.FOR);

    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private AggregateLevelControl aggregate;
    private RuleService appA;
    private RuleService appB;
    private RuleService system;

    @BeforeEach
    void setUp() {
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        aggregate = new AggregateLevelControl();
        appA = register("log4j2:/appA", true);
        appB = register("log4j2:/appB", true);
        system = register("system", true);
    }

    /** One fake context; {@code withRuleTypes == false} makes adopting a rule there fail. */
    private RuleService register(String key, boolean withRuleTypes) {
        return register(key, withRuleTypes, false);
    }

    /** {@code late} registers through {@code addContext}, the way a redeployed web application arrives. */
    private RuleService register(String key, boolean withRuleTypes, boolean late) {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        CapabilityPolicy all = CapabilityPolicy.allowAll();
        RuleService rules = new RuleService(adapter, all, auditLog, stateStore, key, "alice", "jmx");
        if (withRuleTypes) {
            rules.registerDropSupport();
            rules.registerTrimSupport();
        }
        LevelControlService loggers = new LevelControlService(adapter, new BaselineRegistry(Map.of()),
                new OverrideRegistry(), all, auditLog, stateStore, "alice", "jmx");
        HandlerLevelControlService handlers = new HandlerLevelControlService(adapter,
                new HandlerBaselineRegistry(Map.of()), new HandlerOverrideRegistry(),
                new DefaultHandlerGroupRegistry(List.of()), all, auditLog, stateStore, "alice", "jmx", List::of);
        AggregateLevelControl.ContextControl control = new AggregateLevelControl.ContextControl(
                ContextHandle.of(key, key, adapter), loggers, handlers, new DoctorService(adapter, all),
                new TopService(adapter, all),
                new StormService(adapter, all, new StormDetector(3, Duration.ofSeconds(10), Duration.ofSeconds(60),
                        4_000, 100, 8 * 1024)),
                rules, new EnvironmentReportService(adapter, all));
        if (late) {
            aggregate.addContext(control);
        } else {
            aggregate.register(control);
        }
        return rules;
    }

    private static List<String> ids(RuleService service) {
        return service.listRules().stream().map(view -> view.rule().id()).toList();
    }

    @Test
    void aDropRule_isInEveryContext_underOneId() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        assertEquals(List.of(added.rule().id()), ids(appA));
        assertEquals(List.of(added.rule().id()), ids(appB));
        assertEquals(List.of(added.rule().id()), ids(system));
        LogRule inB = appB.listRules().get(0).rule();
        assertEquals("com.acme", inB.loggerName());
        assertEquals("drop", inB.actionName());
        assertEquals(PersistenceTier.FOR, inB.tier());
        assertEquals(added.rule().expiresAt(), inB.expiresAt(), "the same absolute deadline in every context");
    }

    @Test
    void aTrimRule_isInEveryContext_underOneId() {
        RuleView added = aggregate.addRuleTrim("com.acme", MATCHERS, FOR_AN_HOUR, 2, false);

        assertEquals(List.of(added.rule().id()), ids(appA));
        assertEquals(List.of(added.rule().id()), ids(appB));
        assertEquals(List.of(added.rule().id()), ids(system));
        assertEquals("trim", appB.listRules().get(0).rule().actionName());
    }

    @Test
    void eachContext_keepsItsOwnInstance_soHitsAreCountedPerContext() {
        aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        LogRule inA = appA.listRules().get(0).rule();
        LogRule inB = appB.listRules().get(0).rule();
        assertFalse(inA == inB, "a shared instance would share its counters and sample timer");
    }

    @Test
    void successiveRules_getSuccessiveIds_oneSequenceForTheJvm() {
        RuleView first = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        RuleView second = aggregate.addRuleTrim("com.acme.other", MATCHERS, FOR_AN_HOUR, 1, false);

        assertEquals("r1", first.rule().id());
        assertEquals("r2", second.rule().id());
        assertEquals(List.of("r1", "r2"), ids(system).stream().sorted().toList());
    }

    @Test
    void theSequence_startsPastAnIdAnyContextResumed() {
        // A context that resumed r7 from the state file (advanceIdSequencePast) must never see r7 minted again.
        appB.attach("com.acme.old", MATCHERS, FOR_AN_HOUR, DropFactories.attach(SampleFullPolicy.disabled()),
                Capability.SUPPRESS, "r7");

        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        assertEquals("r8", added.rule().id());
    }

    @Test
    void oneStateRowAndOneAuditRecord_perRule_notPerContext() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        assertEquals(List.of(added.rule().id()), stateStore.loadAllRules().stream().map(r -> r.id()).toList());
        long mutations = auditLog.records().stream()
                .filter(r -> r.loggerName().equals("com.acme") && r.action() == AuditRecord.Action.MUTATION)
                .count();
        assertEquals(1, mutations);
    }

    @Test
    void aFailureInTheLeader_leavesNoContextTouched() {
        // LogAperture's own category is refused by the first context, before any other is touched.
        assertThrows(IllegalArgumentException.class,
                () -> aggregate.addRuleDrop("org.logaperture.drop", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled()));

        assertTrue(ids(appA).isEmpty());
        assertTrue(ids(appB).isEmpty());
        assertTrue(ids(system).isEmpty());
        assertTrue(stateStore.loadAllRules().isEmpty());
    }

    @Test
    void aFailureAdoptingInALaterContext_takesTheRuleBackOutOfEveryContext() {
        RuleService noRuleTypes = register("zz-broken", false);

        assertThrows(IllegalStateException.class,
                () -> aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled()));

        assertTrue(ids(appA).isEmpty(), "the first context had it, and gave it back");
        assertTrue(ids(appB).isEmpty());
        assertTrue(ids(system).isEmpty());
        assertTrue(ids(noRuleTypes).isEmpty());
        assertTrue(stateStore.loadAllRules().isEmpty(), "nothing persisted");
        assertTrue(auditLog.records().stream().anyMatch(r -> r.action() == AuditRecord.Action.REVERSION
                && r.reason() != null && r.reason().startsWith("rolled back")));
    }

    // --- A context that registers later (M4) -------------------------------------------------------

    @Test
    void aContextThatRegistersLater_receivesTheLiveRule_underTheSameIdAndDeadline() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        RuleService redeployed = register("log4j2:/appC", true, true);

        assertEquals(List.of(added.rule().id()), ids(redeployed));
        LogRule inNew = redeployed.listRules().get(0).rule();
        assertEquals(added.rule().expiresAt(), inNew.expiresAt(), "the same absolute deadline, not a fresh hour");
        assertEquals(PersistenceTier.FOR, inNew.tier());
        assertEquals(0L, redeployed.listRules().get(0).hitCount(), "this context counts its own hits");
        assertFalse(inNew == appA.listRules().get(0).rule());
    }

    @Test
    void aSessionRule_alsoReachesALaterContext() {
        RuleView added = aggregate.addRuleTrim("com.acme", MATCHERS, RuleAttachOptions.defaults(), 1, false);

        RuleService redeployed = register("log4j2:/appC", true, true);

        assertEquals(List.of(added.rule().id()), ids(redeployed));
    }

    @Test
    void aForRuleThatExpiredBeforeTheContextArrived_isNotSent() throws InterruptedException {
        aggregate.addRuleDrop("com.acme", MATCHERS,
                new RuleAttachOptions(null, Duration.ofMillis(1), PersistenceTier.FOR), SampleFullPolicy.disabled());
        Thread.sleep(20);

        RuleService redeployed = register("log4j2:/appC", true, true);

        assertTrue(ids(redeployed).isEmpty());
    }

    @Test
    void theArrivalIsAudited_andWritesNoSecondStateRow() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        int rowsBefore = stateStore.loadAllRules().size();

        register("log4j2:/appC", true, true);

        assertEquals(rowsBefore, stateStore.loadAllRules().size());
        assertTrue(auditLog.records().stream().anyMatch(r -> "resume".equals(r.source())
                && r.loggerName().equals("com.acme") && r.action() == AuditRecord.Action.MUTATION));
        assertEquals("r" + (Long.parseLong(added.rule().id().substring(1)) + 1), mintedNext());
    }

    @Test
    void aRuleThatCannotBeAppliedInTheNewContext_doesNotStopItRegistering() {
        aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        RuleService broken = register("log4j2:/appC", false, true);

        assertTrue(aggregate.hasContext("log4j2:/appC"), "registered all the same");
        assertTrue(ids(broken).isEmpty());
    }

    @Test
    void rebroadcastingARuleAContextAlreadyHolds_changesNothing() {
        aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        LogRule held = appA.listRules().get(0).rule();
        long auditBefore = auditLog.records().size();

        appA.rebroadcast(held, null);

        assertEquals(1, appA.listRules().size());
        assertEquals(auditBefore, auditLog.records().size());
    }

    private String mintedNext() {
        RuleView next = aggregate.addRuleDrop("com.acme.next", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        return next.rule().id();
    }

    // --- Persistence and resume (M5) --------------------------------------------------------------

    /** A 1.0.x row: the rule, tagged with the key of the one context that held it. */
    private PersistedRule legacyRow(String id, String contextTag, PersistenceTier tier, Instant expiresAt) {
        return new PersistedRule(id, "com.acme", "drop", MATCHERS, null, tier, expiresAt, Instant.now().minusSeconds(60),
                contextTag, Map.of("sampleFull", "false"), null, null);
    }

    @Test
    void aRuleIsPersistedWithoutAContext_becauseItAppliesInEveryOne() {
        aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());

        assertEquals(1, stateStore.loadAllRules().size());
        assertEquals(null, stateStore.loadAllRules().get(0).context());
    }

    @Test
    void aRowFromRelease1_resumesInEveryContext_whateverItsContextTagSays() {
        stateStore.saveRule(legacyRow("r4", "log4j2:/appA", PersistenceTier.STICKY, null));

        // Each context's composition root resumes for itself, in turn.
        for (RuleService service : List.of(appA, appB, system)) {
            assertEquals(1, service.resumeFromStateStore(Instant.now()));
        }

        assertEquals(List.of("r4"), ids(appA));
        assertEquals(List.of("r4"), ids(appB), "the tag named appA; the rule is system-wide");
        assertEquals(List.of("r4"), ids(system));
        assertEquals("r5", aggregate.addRuleDrop("com.acme.next", MATCHERS, FOR_AN_HOUR,
                SampleFullPolicy.disabled()).rule().id());
    }

    @Test
    void aResumedRule_isNotDuplicatedWhenTheContextIsAlsoHandedIt() {
        stateStore.saveRule(legacyRow("r4", "log4j2:/appA", PersistenceTier.STICKY, null));
        appA.resumeFromStateStore(Instant.now());
        RuleService late = register("log4j2:/appC", true); // resumes for itself below, then is registered
        late.resumeFromStateStore(Instant.now());
        aggregate.addContext(aggregate.contexts().stream()
                .filter(c -> c.stableKey().equals("log4j2:/appC")).findFirst().orElseThrow());

        assertEquals(List.of("r4"), ids(late));
    }

    @Test
    void anExpiredRow_isAuditedAndRemovedOnce_notOncePerContext() {
        stateStore.saveRule(legacyRow("r4", "log4j2:/appA", PersistenceTier.FOR, Instant.now().minusSeconds(5)));

        for (RuleService service : List.of(appA, appB, system)) {
            service.resumeFromStateStore(Instant.now());
        }

        assertTrue(stateStore.loadAllRules().isEmpty());
        assertTrue(ids(appA).isEmpty() && ids(appB).isEmpty() && ids(system).isEmpty());
        long expiredRecords = auditLog.records().stream()
                .filter(r -> "expired while stopped".equals(r.reason())).count();
        assertEquals(1, expiredRecords);
    }

    @Test
    void theStateFile_keepsOneRowPerId_soAStaleDuplicateCannotExist() {
        // 1.0 minted ids per context, so a second context's r1 once replaced the first's row on save. The store is
        // keyed by id, so there is never a pair to merge when resuming -- only the surviving row.
        stateStore.saveRule(legacyRow("r1", "log4j2:/appA", PersistenceTier.STICKY, null));
        stateStore.saveRule(legacyRow("r1", "system", PersistenceTier.STICKY, null));

        assertEquals(1, stateStore.loadAllRules().size());
        assertEquals("system", stateStore.loadAllRules().get(0).context());
    }

    // --- One rule to the user (M7) -----------------------------------------------------------------

    private static RuleCandidateEvent event(String loggerName, String message) {
        return new RuleCandidateEvent(loggerName, Level.INFO, null, () -> message, Instant.now());
    }

    @Test
    void listRules_showsARuleOnce_withTheHitsOfEveryContextAddedUp_andContextSystem() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        for (int i = 0; i < 3; i++) {
            appA.gate().evaluate(new Object(), event("com.acme.Worker", "some noise here"));
        }
        for (int i = 0; i < 2; i++) {
            appB.gate().evaluate(new Object(), event("com.acme.Worker", "some noise here"));
        }

        List<RuleView> rows = aggregate.listRules();

        assertEquals(1, rows.size(), "one row, not one per context");
        assertEquals(added.rule().id(), rows.get(0).rule().id());
        assertEquals("system", rows.get(0).context());
        assertEquals(5L, rows.get(0).hitCount(), "3 in appA + 2 in appB");
    }

    @Test
    void addRule_answersWithTheRolledUpRule() {
        RuleView drop = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        RuleView trim = aggregate.addRuleTrim("com.acme.x", MATCHERS, FOR_AN_HOUR, 1, false);

        assertEquals("system", drop.context());
        assertEquals("system", trim.context());
        assertEquals(0L, drop.hitCount());
    }

    @Test
    void alterRule_changesEveryContext_andAnswersWithOneRule() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        org.logaperture.api.RuleChange change = new org.logaperture.api.RuleChange(null, false, null, null, null, null, null,
                null, null, "tuned");

        java.util.Optional<RuleAlteration> altered =
                aggregate.alterRule(added.rule().id(), change, null, null);

        assertTrue(altered.isPresent());
        assertEquals("system", altered.get().after().context());
        for (RuleService service : List.of(appA, appB, system)) {
            assertEquals("tuned", service.listRules().get(0).rule().reason());
        }
    }

    // --- reset rule and the drop summary (M8, M9) ----------------------------------------------------

    @Test
    void resetRule_removesTheRuleFromEveryContext_andTheStateFile_andAnswersOnce() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        for (int i = 0; i < 2; i++) {
            appA.gate().evaluate(new Object(), event("com.acme.Worker", "some noise here"));
        }
        appB.gate().evaluate(new Object(), event("com.acme.Worker", "some noise here"));

        java.util.Optional<RuleView> removed = aggregate.resetRule(added.rule().id(), false, false);

        assertTrue(removed.isPresent());
        assertEquals("system", removed.get().context());
        assertEquals(3L, removed.get().hitCount(), "what it did in every context");
        assertTrue(ids(appA).isEmpty() && ids(appB).isEmpty() && ids(system).isEmpty());
        assertTrue(stateStore.loadAllRules().isEmpty());
        assertEquals(java.util.Optional.empty(), aggregate.resetRule(added.rule().id(), false, false), "already gone");
    }

    @Test
    void aStickyRule_refusedWithoutIncludeSticky_staysInEveryContext() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, RuleAttachOptions.sticky(),
                SampleFullPolicy.disabled());

        assertThrows(IllegalArgumentException.class, () -> aggregate.resetRule(added.rule().id(), false, false));

        assertEquals(List.of(added.rule().id()), ids(appA));
        assertEquals(List.of(added.rule().id()), ids(appB));
        assertEquals(List.of(added.rule().id()), ids(system));
        assertEquals(1, stateStore.loadAllRules().size());

        assertTrue(aggregate.resetRule(added.rule().id(), true, false).isPresent());
        assertTrue(ids(appA).isEmpty() && ids(appB).isEmpty() && ids(system).isEmpty());
    }

    @Test
    void resetRules_countsARuleOnce_howeverManyContextsHeldIt() {
        aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        aggregate.addRuleTrim("com.acme.x", MATCHERS, FOR_AN_HOUR, 1, false);

        RuleResetOutcome outcome = aggregate.resetAllRules(false, false);

        assertEquals(List.of("r1", "r2"), outcome.removedIds().stream().sorted().toList());
        assertTrue(aggregate.listRules().isEmpty());
    }

    @Test
    void theDropSummary_namesARuleOnce_withEveryContextsSuppressedEventsAddedUp() {
        RuleView added = aggregate.addRuleDrop("com.acme", MATCHERS, FOR_AN_HOUR, SampleFullPolicy.disabled());
        for (int i = 0; i < 3; i++) {
            appA.gate().evaluate(new Object(), event("com.acme.Worker", "some noise here"));
        }
        for (int i = 0; i < 2; i++) {
            appB.gate().evaluate(new Object(), event("com.acme.Worker", "some noise here"));
        }
        List<String> lines = new java.util.ArrayList<>();
        aggregate.setDropSummarySink(lines::add);

        aggregate.reportDueDropSummaries(Instant.now().plus(Duration.ofDays(1)));

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("5 events suppressed by 1 rule"), lines.get(0));
        assertTrue(lines.get(0).contains(added.rule().id() + " 5"), lines.get(0));
    }
}
