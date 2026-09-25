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
import org.logaperture.api.PersistedRule;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleChange;
import org.logaperture.api.RuleChange.Field;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.Trim;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/alter-rule.md "Testing" -- {@code alter rule} on operator and vendor rules. */
class AlterRuleTest {

    private static final String WORKER = "com.acme.Worker";
    private static final String HEALTH = "com.acme.health";
    private static final String VENDOR_DROP = "vendor:healthcheck-noise";

    private FakeLoggingAdapter adapter;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private RuleService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = newService(CapabilityPolicy.allowAll());
    }

    private RuleService newService(CapabilityPolicy policy) {
        RuleService created = new RuleService(adapter, policy, auditLog, stateStore, "system", "alice", "jmx");
        created.registerDropSupport();
        created.registerTrimSupport();
        return created;
    }

    private LogRule drop(String message, RuleAttachOptions options) {
        return service.addRuleDrop(WORKER, new CompiledMatchers(Level.INFO, message, false, null, null, false),
                options, SampleFullPolicy.disabled()).rule();
    }

    private LogRule trim(RuleAttachOptions options) {
        return service.addRuleTrim(WORKER, new CompiledMatchers(Level.INFO, null, false,
                "java.net.ConnectException", null, false), options, 0, false).rule();
    }

    private static RuleChange message(String text) {
        return new RuleChange(Field.set(text), false, null, null, null, null, null, null, null, null);
    }

    private static RuleChange below(Level levelAtMost) {
        return new RuleChange(null, false, null, null, null, levelAtMost, null, null, null, null);
    }

    private RuleAlteration alter(String id, RuleChange change) {
        return service.alterRule(id, change, null, null).orElseThrow();
    }

    private LogRule current(String id) {
        return service.listRules().stream().map(RuleView::rule).filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow();
    }

    private boolean denies(String loggerName, String message) {
        return service.gate().evaluate(new Object(),
                new RuleCandidateEvent(loggerName, Level.INFO, null, () -> message, Instant.now())).deny();
    }

    // --- operator rules: what changes (A2, A3) ---------------------------------------------------

    @Test
    void alterMessage_replacesIt_andKeepsTheIdAndEverythingElse() {
        LogRule original = drop("blue", RuleAttachOptions.forDuration(Duration.ofHours(2)));

        RuleAlteration result = alter(original.id(), message("green"));

        assertTrue(result.changed());
        LogRule altered = current(original.id());
        assertEquals("green", altered.matchers().messageContains());
        assertEquals(Level.INFO, altered.matchers().levelAtMost(), "untouched parts kept");
        assertEquals(original.expiresAt(), altered.expiresAt(), "lifetime kept without a tier (A6)");
        assertEquals(original.createdAt(), altered.createdAt());
        assertEquals("blue", result.before().rule().matchers().messageContains());
        assertTrue(denies(WORKER, "all green"));
        assertFalse(denies(WORKER, "all blue"), "the old matcher no longer applies");
    }

    @Test
    void alterBelow_onATrim_keepsItsOtherParts() {
        LogRule original = trim(RuleAttachOptions.defaults());

        alter(original.id(), below(Level.WARN));

        Trim altered = (Trim) current(original.id());
        assertEquals(Level.WARN, altered.matchers().levelAtMost());
        assertEquals("java.net.ConnectException", altered.matchers().throwableType());
    }

    @Test
    void messageForms_replaceEachOther() {
        LogRule original = drop("blue", RuleAttachOptions.defaults());

        alter(original.id(), new RuleChange(Field.set("GREEN"), true, null, null, null, null, null, null, null, null));

        CompiledMatchers m = current(original.id()).matchers();
        assertEquals("GREEN", m.messageContains());
        assertTrue(m.messageIgnoreCase());
    }

    @Test
    void clearingAnOptionalMatcher_removesIt() {
        LogRule original = service.addRuleDrop(WORKER, new CompiledMatchers(Level.INFO, "blue", false,
                "java.io.IOException", null, true), RuleAttachOptions.defaults(), SampleFullPolicy.disabled()).rule();

        alter(original.id(), new RuleChange(null, false, Field.cleared(), null, false, null, null, null, null, null));

        CompiledMatchers m = current(original.id()).matchers();
        assertNull(m.throwableType());
        assertFalse(m.anyCause());
        assertEquals("blue", m.messageContains());
    }

    @Test
    void noSampleFull_keepsTheInterval() {
        LogRule original = service.addRuleDrop(WORKER, new CompiledMatchers(Level.INFO, "blue", false, null, null,
                false), RuleAttachOptions.defaults(), SampleFullPolicy.every(Duration.ofMinutes(10))).rule();

        alter(original.id(), new RuleChange(null, false, null, null, null, null, SampleFullPolicy.disabled(), null,
                null, null));

        SampleFullPolicy sampleFull = ((Drop) current(original.id())).sampleFull();
        assertFalse(sampleFull.enabled());
        assertEquals(Duration.ofMinutes(10), sampleFull.every());
    }

    @Test
    void anOptionForTheOtherAction_isRefused() {
        LogRule dropRule = drop("blue", RuleAttachOptions.defaults());
        LogRule trimRule = trim(RuleAttachOptions.defaults());

        assertThrows(IllegalArgumentException.class, () -> alter(dropRule.id(),
                new RuleChange(null, false, null, null, null, null, null, 3, null, null)));
        assertThrows(IllegalArgumentException.class, () -> alter(trimRule.id(),
                new RuleChange(null, false, null, null, null, null, SampleFullPolicy.disabled(), null, null, null)));
    }

    // --- validation and no-op (A4) ----------------------------------------------------------------

    @Test
    void clearingADropsLastContentMatcher_isRefused_andChangesNothing() {
        LogRule original = drop("blue", RuleAttachOptions.defaults());
        int auditsBefore = auditLog.records().size();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> alter(original.id(),
                new RuleChange(Field.cleared(), false, null, null, null, null, null, null, null, null)));

        assertTrue(refused.getMessage().contains("content matcher"), refused.getMessage());
        assertEquals("blue", current(original.id()).matchers().messageContains());
        assertEquals(auditsBefore, auditLog.records().size());
    }

    @Test
    void anEmptyAlter_isRefused() {
        LogRule original = drop("blue", RuleAttachOptions.defaults());

        assertThrows(IllegalArgumentException.class, () -> alter(original.id(), RuleChange.none()));
    }

    @Test
    void aNoChangeAlter_reportsUnchanged_withoutAuditOrWrite() {
        LogRule original = drop("blue", RuleAttachOptions.sticky());
        int auditsBefore = auditLog.records().size();
        PersistedRule savedBefore = stateStore.loadAllRules().get(0);

        RuleAlteration result = alter(original.id(), message("blue"));

        assertFalse(result.changed());
        assertEquals(auditsBefore, auditLog.records().size());
        assertEquals(savedBefore, stateStore.loadAllRules().get(0));
        assertTrue(current(original.id()) == original, "the very same rule object is still attached");
    }

    @Test
    void anUnknownId_isEmpty() {
        assertEquals(Optional.empty(), service.alterRule("r99", message("x"), null, null));
    }

    @Test
    void capabilities_suppressAlways_persistOnlyForAPersistedResult() {
        LogRule sessionRule = drop("blue", RuleAttachOptions.defaults());
        CapabilityPolicy noPersist = capability -> capability != Capability.PERSIST;
        RuleService restricted = newService(noPersist);
        restricted.attach(WORKER, new CompiledMatchers(Level.INFO, "x", false, null, null, false),
                RuleAttachOptions.defaults(), DropFactories.attach(SampleFullPolicy.disabled()), Capability.SUPPRESS);
        String restrictedId = restricted.listRules().get(0).rule().id();

        assertTrue(restricted.alterRule(restrictedId, message("y"), null, null).orElseThrow().changed(),
                "a session result needs no PERSIST");
        CapabilityDeniedException denied = assertThrows(CapabilityDeniedException.class,
                () -> restricted.alterRule(restrictedId, message("z"), PersistenceTier.STICKY, null));
        assertEquals(Capability.PERSIST, denied.capability());

        RuleService noSuppress = newService(capability -> capability != Capability.SUPPRESS);
        assertThrows(CapabilityDeniedException.class,
                () -> noSuppress.alterRule(sessionRule.id(), message("y"), null, null));
    }

    // --- hit count (A5) ---------------------------------------------------------------------------

    @Test
    void hitCount_startsOverWhenTheDefinitionChanges_butNotForATierOrReasonChange() {
        LogRule original = drop("blue", RuleAttachOptions.defaults());
        denies(WORKER, "blue");
        denies(WORKER, "blue");
        assertEquals(2, service.hitCount(original.id()));

        alter(original.id(), new RuleChange(null, false, null, null, null, null, null, null, null, "why not"));
        service.alterRule(original.id(), RuleChange.none(), PersistenceTier.STICKY, null);
        assertEquals(2, service.hitCount(original.id()), "reason and lifetime changes keep the count");

        alter(original.id(), message("green"));
        assertEquals(0, service.hitCount(original.id()));
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertTrue(last.previousValue().contains("2 hits"), last.previousValue());
        assertTrue(last.previousValue().contains("--message-contains blue"), last.previousValue());
        assertTrue(last.newValue().contains("--message-contains green"), last.newValue());
        assertEquals(AuditRecord.Action.MUTATION, last.action());
    }

    // --- lifetime (A6) ----------------------------------------------------------------------------

    @Test
    void aGivenTier_replacesTheLifetime_andRestartsAForClock() {
        LogRule original = drop("blue", RuleAttachOptions.forDuration(Duration.ofMinutes(1)));

        service.alterRule(original.id(), RuleChange.none(), PersistenceTier.FOR, Duration.ofHours(3));

        Instant expiresAt = current(original.id()).expiresAt();
        assertTrue(expiresAt.isAfter(Instant.now().plus(Duration.ofHours(2))), "clock restarted from now");
    }

    @Test
    void stickyAndSession_moveTheStateFileEntry() {
        LogRule original = drop("blue", RuleAttachOptions.defaults());
        assertTrue(stateStore.loadAllRules().isEmpty());

        service.alterRule(original.id(), RuleChange.none(), PersistenceTier.STICKY, null);
        assertEquals(1, stateStore.loadAllRules().size());
        assertEquals(original.id(), stateStore.loadAllRules().get(0).id());

        alter(original.id(), message("green"));
        assertEquals("green", stateStore.loadAllRules().get(0).matchers().messageContains(),
                "a sticky rule's entry is rewritten under the same id");

        service.alterRule(original.id(), RuleChange.none(), PersistenceTier.SESSION, null);
        assertTrue(stateStore.loadAllRules().isEmpty());
    }

    @Test
    void anAlteredStickyRule_resumesAltered() {
        LogRule original = drop("blue", RuleAttachOptions.sticky());
        alter(original.id(), message("green"));

        RuleService restarted = newService(CapabilityPolicy.allowAll());
        restarted.resumeFromStateStore(Instant.now());

        LogRule resumed = restarted.listRules().get(0).rule();
        assertEquals(original.id(), resumed.id());
        assertEquals("green", resumed.matchers().messageContains());
    }

    // --- atomic swap ------------------------------------------------------------------------------

    @Test
    void aConcurrentEvaluator_neverSeesTheRuleMissing() throws Exception {
        LogRule original = drop("noisy", RuleAttachOptions.defaults());
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> misses = pool.submit(() -> {
                int missing = 0;
                while (!stop.get()) {
                    if (service.effectiveRules(WORKER).size() != 1) {
                        missing++;
                    }
                }
                return missing;
            });
            for (int i = 0; i < 2_000; i++) {
                alter(original.id(), message("noisy " + i));
            }
            stop.set(true);
            assertEquals(0, misses.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    // --- vendor rules (A7, A8, A9) ----------------------------------------------------------------

    private List<VendorDefaults.RuleDefault> vendorRules() {
        return VendorDefaultsFile.parse("""
                schemaVersion: 1
                rules:
                  - id: healthcheck-noise
                    action: drop
                    logger: com.acme.health
                    messageContains: "ping ok"
                    sampleFull: false
                """, Path.of("/v.yaml"), false).rules();
    }

    @Test
    void alteringAVendorRule_isAnOverride_defaultingToFor4h() {
        service.attachVendorRules(vendorRules(), Instant.now());

        RuleAlteration result = alter(VENDOR_DROP, message("pong"));

        assertTrue(result.after().altered());
        assertFalse(result.before().altered());
        LogRule override = current(VENDOR_DROP);
        assertEquals(PersistenceTier.FOR, override.tier());
        assertTrue(override.expiresAt().isAfter(Instant.now().plus(Duration.ofHours(3))));
        assertTrue(denies(HEALTH, "pong"));
        assertFalse(denies(HEALTH, "ping ok"));

        Instant firstExpiry = override.expiresAt();
        alter(VENDOR_DROP, below(Level.WARN));
        assertEquals(firstExpiry, current(VENDOR_DROP).expiresAt(), "a later alteration keeps the override's lifetime");
    }

    @Test
    void aVendorRuleAlterThatChangesNothing_isANoOp_evenWithoutATier() {
        service.attachVendorRules(vendorRules(), Instant.now());

        assertFalse(alter(VENDOR_DROP, message("ping ok")).changed());
        assertFalse(service.listRules().get(0).altered());
    }

    @Test
    void aPlainReset_restoresTheVendorDefinition() {
        service.attachVendorRules(vendorRules(), Instant.now());
        alter(VENDOR_DROP, message("pong"));

        Optional<RuleView> reset = service.resetRule(VENDOR_DROP, false, false);

        assertTrue(reset.isPresent());
        assertFalse(reset.get().altered());
        assertTrue(denies(HEALTH, "ping ok"));
        assertEquals(Optional.empty(), service.resetRule(VENDOR_DROP, false, false), "already at the baseline");
    }

    @Test
    void aStickyVendorAlteration_needsIncludeStickyToReset_andIsSavedUnderTheVendorId() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.STICKY, null);
        assertEquals(VENDOR_DROP, stateStore.loadAllRules().get(0).id());

        assertThrows(IllegalArgumentException.class, () -> service.resetRule(VENDOR_DROP, false, false));
        assertTrue(service.resetRule(VENDOR_DROP, true, false).isPresent());
        assertTrue(stateStore.loadAllRules().isEmpty());
    }

    @Test
    void toNative_removesTheAlteration_andSwitchesTheRuleOff_andAlterIsRefusedWhileOff() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.SESSION, null);

        RuleView off = service.resetRule(VENDOR_DROP, false, true).orElseThrow();

        assertTrue(off.toNative());
        assertFalse(off.altered());
        assertFalse(denies(HEALTH, "pong"));
        assertFalse(denies(HEALTH, "ping ok"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> alter(VENDOR_DROP, message("x")));
        assertTrue(refused.getMessage().contains("reset rule " + VENDOR_DROP), refused.getMessage());
    }

    @Test
    void anExpiredVendorAlteration_returnsToTheVendorDefinition() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.FOR, Duration.ofMinutes(5));

        service.sweepExpiredRules(Instant.now().plus(Duration.ofMinutes(6)));

        RuleView row = service.listRules().get(0);
        assertFalse(row.altered());
        assertEquals("ping ok", row.rule().matchers().messageContains());
        assertTrue(stateStore.loadAllRules().isEmpty());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("expiry-sweep", last.source());
    }

    @Test
    void restart_reappliesAStickyVendorAlteration() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.STICKY, null);

        RuleService restarted = newService(CapabilityPolicy.allowAll());
        restarted.attachVendorRules(vendorRules(), true, Instant.now());
        restarted.resumeFromStateStore(Instant.now());

        RuleView row = restarted.listRules().get(0);
        assertTrue(row.altered());
        assertEquals("pong", row.rule().matchers().messageContains());
        assertEquals(PersistenceTier.STICKY, row.rule().tier());
        assertEquals(Optional.empty(), restarted.listRules().stream()
                .filter(r -> !r.rule().id().equals(VENDOR_DROP)).findAny(), "not attached a second time");
    }

    @Test
    void restart_dropsAnAlterationWhoseVendorRuleIsGone() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.STICKY, null);

        RuleService restarted = newService(CapabilityPolicy.allowAll());
        restarted.attachVendorRules(List.of(), true, Instant.now()); // the new file has no rules
        restarted.resumeFromStateStore(Instant.now());

        assertTrue(restarted.listRules().isEmpty());
        assertTrue(stateStore.loadAllRules().isEmpty());
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, last.action());
        assertEquals("vendor rule no longer in the vendor defaults file", last.reason());
    }

    @Test
    void restart_keepsAnAlterationWhenTheVendorFileDidNotLoad() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.STICKY, null);

        RuleService restarted = newService(CapabilityPolicy.allowAll());
        restarted.attachVendorRules(List.of(), false, Instant.now()); // rejected or not configured
        restarted.resumeFromStateStore(Instant.now());

        assertTrue(restarted.listRules().isEmpty());
        assertEquals(1, stateStore.loadAllRules().size(), "kept for a start where the file loads again");
    }

    @Test
    void restart_forgetsASessionAlterationAndAToNativeReset() {
        service.attachVendorRules(vendorRules(), Instant.now());
        service.alterRule(VENDOR_DROP, message("pong"), PersistenceTier.SESSION, null);
        service.resetRule(VENDOR_DROP, false, true);

        RuleService restarted = newService(CapabilityPolicy.allowAll());
        restarted.attachVendorRules(vendorRules(), true, Instant.now());
        restarted.resumeFromStateStore(Instant.now());

        RuleView row = restarted.listRules().get(0);
        assertFalse(row.altered());
        assertFalse(row.toNative());
    }
}
