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
import org.logaperture.api.RuleResetOutcome;
import org.logaperture.api.SampleFullPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "The baseline layer" -- "Rules". */
class VendorRuleDefaultsTest {

    private static final String HEALTH = "com.acme.health";

    private FakeLoggingAdapter adapter;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private RuleService service;
    private List<VendorDefaults.RuleDefault> vendorRules;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore, "system", "alice",
                "jmx");
        service.registerDropSupport();
        service.registerTrimSupport();
        vendorRules = VendorDefaultsFile.parse("""
                schemaVersion: 1
                rules:
                  - id: healthcheck-noise
                    action: drop
                    logger: com.acme.health
                    messageContains: "ping ok"
                    sampleFull: false
                    reason: "load balancer probes"
                  - id: autoupdate-trace
                    action: trim
                    logger: com.destiny.AutoUpdateHelper
                    below: FATAL
                """, Path.of("/v.yaml"), false).rules();
    }

    @Test
    void attachVendorRules_attachesWithVendorIds_andNeverPersists() {
        service.attachVendorRules(vendorRules, Instant.now());

        List<RuleView> rows = service.listRules();
        assertEquals(List.of("vendor:healthcheck-noise", "vendor:autoupdate-trace"),
                rows.stream().map(row -> row.rule().id()).toList());
        assertTrue(rows.stream().allMatch(row -> "vendor-defaults".equals(row.origin()) && !row.suspended()));
        assertTrue(stateStore.loadAllRules().isEmpty(), "the vendor file is their persistence");
        assertEquals(2, auditLog.records().stream().filter(r -> r.source().equals("vendor-defaults")).count());
    }

    @Test
    void aVendorDropRule_actuallyDrops() {
        service.attachVendorRules(vendorRules, Instant.now());

        assertTrue(service.gate().evaluate(new Object(), event(HEALTH, "ping ok from 10.0.0.1")).deny());
        assertFalse(service.gate().evaluate(new Object(), event(HEALTH, "db pool exhausted")).deny());
    }

    @Test
    void operatorRuleIds_stillStartAtR1() {
        service.attachVendorRules(vendorRules, Instant.now());

        RuleView added = service.addRuleDrop("com.acme", new CompiledMatchers(Level.WARN, "x", false, null, null, false),
                RuleAttachOptions.defaults(), SampleFullPolicy.defaults());

        assertEquals("r1", added.rule().id());
        assertEquals(null, added.origin());
    }

    @Test
    void resetRules_skipsAndReportsVendorRules() {
        service.attachVendorRules(vendorRules, Instant.now());
        service.addRuleDrop(HEALTH, new CompiledMatchers(Level.WARN, "x", false, null, null, false),
                RuleAttachOptions.defaults(), SampleFullPolicy.defaults());

        RuleResetOutcome outcome = service.resetAllRules(false, false);

        assertEquals(List.of("r1"), outcome.removedIds());
        assertEquals(List.of("vendor:healthcheck-noise", "vendor:autoupdate-trace"), outcome.skippedVendorIds());
        assertEquals(2, service.listRules().size());

        RuleResetOutcome forLogger = service.resetRulesForLogger(HEALTH, true, false);
        assertEquals(List.of("vendor:healthcheck-noise"), forLogger.skippedVendorIds(),
                "--include-sticky alone doesn't reach vendor rules");
    }

    @Test
    void resetRule_onAVendorRule_refusesWithoutTheFlag() {
        service.attachVendorRules(vendorRules, Instant.now());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.resetRule("vendor:healthcheck-noise", true, false));
        assertTrue(refused.getMessage().contains("--include-vendor-defaults"), refused.getMessage());
        assertTrue(service.gate().evaluate(new Object(), event(HEALTH, "ping ok")).deny(), "still in force");
    }

    @Test
    void resetRule_withTheFlag_suspendsUntilRestart_andStaysListed() {
        service.attachVendorRules(vendorRules, Instant.now());
        service.gate().evaluate(new Object(), event(HEALTH, "ping ok")); // one hit before suspending

        Optional<RuleView> suspended = service.resetRule("vendor:healthcheck-noise", false, true);

        assertTrue(suspended.isPresent());
        assertFalse(service.gate().evaluate(new Object(), event(HEALTH, "ping ok")).deny(), "no longer drops");
        RuleView row = service.listRules().stream()
                .filter(r -> r.rule().id().equals("vendor:healthcheck-noise")).findFirst().orElseThrow();
        assertTrue(row.suspended());
        assertEquals(1, row.hitCount(), "hits before suspension are still shown");
        assertEquals(Optional.empty(), service.resetRule("vendor:healthcheck-noise", false, true),
                "already suspended -- nothing left to do");
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, last.action());
        assertEquals("vendor default suspended until restart", last.reason());
    }

    @Test
    void resetRules_withTheFlag_suspendsVendorRulesToo() {
        service.attachVendorRules(vendorRules, Instant.now());

        RuleResetOutcome outcome = service.resetAllRules(false, true);

        assertEquals(List.of("vendor:healthcheck-noise", "vendor:autoupdate-trace"), outcome.removedIds());
        assertTrue(service.listRules().stream().allMatch(RuleView::suspended));
        assertTrue(stateStore.loadAllRules().isEmpty());
    }

    private static RuleCandidateEvent event(String loggerName, String message) {
        return new RuleCandidateEvent(loggerName, Level.INFO, null, () -> message, Instant.now());
    }
}
