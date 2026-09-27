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
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleChange;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.spi.ContextHandle;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/export-round-trip.md (issue #107): tune with sticky settings, export, restart the
 * same instance (same state store) with the exported file, and get each setting once, from the
 * file. "Restart" here is a second composition over the same {@link InMemoryStateStore}, with
 * {@link VendorStateTakeover} run before resume the way the containers run it.
 */
class ExportRoundTripTest {

    private static final HandlerRef FILE = new HandlerRef("FILE");
    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef AUDIT = new HandlerRef("AUDIT");

    private static final String VENDOR_FILE = """
            schemaVersion: 1
            loggers:
              - name: com.acme.a
                level: WARN
            handlers:
              - name: FILE
                level: WARN
            rules:
              - id: health-noise
                action: drop
                logger: com.acme.health
                messageContains: "ping ok"
            """;

    private final InMemoryAuditLog auditLog = new InMemoryAuditLog();
    private final InMemoryStateStore stateStore = new InMemoryStateStore().assigningStateIds();
    private Jvm jvm;

    /** One JVM's worth of services over {@link #stateStore}, as the containers compose them. */
    private final class Jvm {
        final LevelControlService loggers;
        final HandlerLevelControlService handlers;
        final RuleService rules;
        final AggregateLevelControl aggregate;
        final VendorStateTakeover.Result takeover;

        Jvm(VendorDefaults vendor) {
            takeover = VendorStateTakeover.run(vendor, stateStore, auditLog, "alice", Instant.now());
            CapabilityPolicy policy = CapabilityPolicy.allowAll();
            FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
            adapter.addHandler(FILE, Level.ALL);
            adapter.addHandler(CONSOLE, Level.INFO);
            adapter.addHandler(AUDIT, Level.INFO);
            OverrideRegistry overrides = new OverrideRegistry();
            loggers = new LevelControlService(adapter,
                    new BaselineRegistry(vendor.loggerLevels(), vendor.loggerReasons()), overrides, policy, auditLog,
                    stateStore, "alice", "jmx");
            ActiveLoggerFloor floor = () -> List.copyOf(overrides.all().values());
            handlers = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(vendor.handlers()),
                    new HandlerOverrideRegistry(),
                    new DefaultHandlerGroupRegistry(vendor.defaultHandlers().orElse(List.of())), policy, auditLog,
                    stateStore, "alice", "jmx", floor);
            rules = new RuleService(adapter, policy, auditLog, stateStore, "system", "alice", "jmx");
            rules.registerDropSupport();
            rules.registerTrimSupport();
            loggers.applyVendorDefaults(Instant.now());
            handlers.applyVendorDefaults(Instant.now());
            rules.attachVendorRules(vendor.rules(), vendor.status() == VendorDefaults.Status.LOADED, Instant.now());
            loggers.resumeFromStateStore(Instant.now());
            handlers.resumeFromStateStore(Instant.now());
            rules.resumeFromStateStore(Instant.now());
            aggregate = new AggregateLevelControl("none", Optional::empty, null, () -> true, vendor);
            aggregate.register(new AggregateLevelControl.ContextControl(ContextHandle.of("system", "system", adapter),
                    loggers, handlers, new DoctorService(adapter, policy), new TopService(adapter, policy),
                    new StormService(adapter, policy, new StormDetector(3, Duration.ofSeconds(10),
                            Duration.ofSeconds(60), 4_000, 100, 8 * 1024)),
                    rules, new EnvironmentReportService(adapter, policy)));
        }

        String export() {
            return aggregate.exportVendorDefaults();
        }

        List<String> ruleIds() {
            return rules.listRules().stream().map(view -> view.rule().id()).toList();
        }
    }

    private static VendorDefaults parse(String text) {
        VendorDefaults parsed = VendorDefaultsFile.parse(text, Path.of("/opt/app/vendor-defaults.yaml"), false);
        assertEquals(VendorDefaults.Status.LOADED, parsed.status(), parsed.errors().toString());
        return parsed;
    }

    private static SetLevelOptions sticky() {
        return new SetLevelOptions(null, null, PersistenceTier.STICKY, true);
    }

    @BeforeEach
    void startWithTheVendorFile() {
        jvm = new Jvm(parse(VENDOR_FILE));
    }

    /** Every kind of sticky setting the export folds into the file. */
    private void tuneEverything() {
        jvm.loggers.setLogger("com.acme.a", Level.ERROR, sticky());
        jvm.loggers.setLogger("com.acme.new", Level.DEBUG, sticky());
        jvm.handlers.setHandlerLevel(CONSOLE, Level.WARN, SetHandlerLevelOptions.sticky());
        jvm.handlers.setDefaultHandlerMembers(List.of(CONSOLE));
        jvm.rules.addRuleDrop("com.acme.HealthCheck",
                new CompiledMatchers(Level.INFO, "GET /health", false, null, null, false),
                RuleAttachOptions.sticky(), SampleFullPolicy.defaults());
        jvm.rules.alterRule("vendor:health-noise", new RuleChange(RuleChange.Field.set("pong"), false, null, null,
                null, null, null, null, null, null), PersistenceTier.STICKY, null);
    }

    // --- the export ------------------------------------------------------------------------------

    @Test
    void entriesFromStickySettings_carryTheirStateIds_andEntriesFromTheFileCarryNone() {
        tuneEverything();

        VendorDefaults exported = parse(jvm.export());

        assertEquals(stateStore.loadAll().stream().filter(o -> o.loggerName().equals("com.acme.a")).findFirst()
                .orElseThrow().stateId(), exported.loggers().get("com.acme.a").stateId());
        assertNotNull(exported.loggers().get("com.acme.new").stateId());
        assertNotNull(exported.handlers().get(CONSOLE).stateId());
        assertNull(exported.handlers().get(FILE).stateId(), "straight from the file, no sticky setting over it");
        assertEquals(stateStore.defaultHandlerMembersStateId(), exported.defaultHandlersStateId());
        assertEquals(2, exported.rules().stream().filter(r -> r.stateId() != null).count(),
                "the operator rule and the vendor rule's sticky alteration");
    }

    @Test
    void aGroupOverride_writesItsStateIdOnEveryMember_andAMembersOwnOverrideWritesItsOwn() {
        jvm.handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.ERROR, SetHandlerLevelOptions.sticky());
        jvm.handlers.setHandlerLevel(AUDIT, Level.DEBUG, SetHandlerLevelOptions.sticky());

        VendorDefaults exported = parse(jvm.export());

        String groupId = stateStore.loadAllHandlers().stream()
                .filter(o -> o.handlerRef().equals(HandlerRef.ALL_HANDLERS)).findFirst().orElseThrow().stateId();
        assertEquals(groupId, exported.handlers().get(FILE).stateId());
        assertEquals(groupId, exported.handlers().get(CONSOLE).stateId());
        assertNotNull(exported.handlers().get(AUDIT).stateId());
        assertTrue(!groupId.equals(exported.handlers().get(AUDIT).stateId()));
    }

    @Test
    void aGroupOverrideWhoseIdIsReplacedOnEveryMember_isStillTakenOver() {
        // Code review of PR #111: each member's own sticky override replaces ALL_HANDLERS' id on
        // its entry, so the group's id reaches the file only through handlerGroupStateIds.
        jvm.handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.WARN, SetHandlerLevelOptions.sticky());
        for (HandlerRef handler : List.of(FILE, CONSOLE, AUDIT)) {
            jvm.handlers.setHandlerLevel(handler, Level.DEBUG, SetHandlerLevelOptions.sticky());
        }
        String exported = jvm.export();

        Jvm restarted = new Jvm(parse(exported));

        assertTrue(restarted.takeover.takenOver().contains("handler ALL_HANDLERS"),
                restarted.takeover.takenOver() + "\n" + exported);
        assertEquals(List.of(), stateStore.loadAllHandlers());
        assertEquals(List.of(), restarted.handlers.listHandlerOverrides(), "nothing left to override the file");
    }

    @Test
    void allHandlersUnderADefaultHandlersOverrideCoveringEveryHandler_isStillTakenOver() {
        jvm.handlers.setDefaultHandlerMembers(List.of(FILE, CONSOLE, AUDIT));
        jvm.handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.WARN, SetHandlerLevelOptions.sticky());
        jvm.handlers.setHandlerLevel(HandlerRef.DEFAULT_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.sticky());
        String exported = jvm.export();

        Jvm restarted = new Jvm(parse(exported));

        assertEquals(List.of(), stateStore.loadAllHandlers(), exported);
        assertEquals(List.of(), restarted.handlers.listHandlerOverrides());
        assertEquals(List.of(), restarted.takeover.differed());
    }

    // --- the restart -----------------------------------------------------------------------------

    @Test
    void restartingWithTheExportedFile_givesEachSettingOnce_fromTheFile() {
        tuneEverything();
        String exported = jvm.export();

        Jvm restarted = new Jvm(parse(exported));

        assertEquals(6, restarted.takeover.takenOver().size(), restarted.takeover.takenOver().toString());
        assertEquals(List.of(), restarted.takeover.differed());
        assertEquals(List.of(), stateStore.loadAll());
        assertEquals(List.of(), stateStore.loadAllHandlers());
        assertEquals(List.of(), stateStore.loadDefaultHandlerMembers());
        assertEquals(List.of(), stateStore.loadAllRules());
        assertEquals(List.of("vendor:health-noise", "vendor:drop-healthcheck"), restarted.ruleIds(),
                "no r1 next to its exported copy");
        assertEquals(List.of(), restarted.loggers.activeOverrides(), "the loggers are vendor defaults now");
        assertEquals(List.of(), restarted.handlers.listHandlerOverrides());
        assertEquals(6, auditLog.records().stream()
                .filter(r -> r.source().equals(VendorDefaults.AUDIT_SOURCE)
                        && r.action() == AuditRecord.Action.REVERSION
                        && VendorStateTakeover.REASON.equals(r.reason()))
                .count());
    }

    @Test
    void anEditToTheFileAfterTheRestart_takesEffect() {
        jvm.loggers.setLogger("com.acme.a", Level.ERROR, sticky());
        String exported = jvm.export();
        new Jvm(parse(exported));

        String edited = exported.replace("level: ERROR", "level: TRACE");
        Jvm again = new Jvm(parse(edited));

        assertEquals(List.of(), again.takeover.takenOver(), "nothing left in the state file to take over");
        assertEquals(List.of(), again.loggers.activeOverrides());
        assertEquals(Level.TRACE, parse(again.export()).loggers().get("com.acme.a").level());
    }

    @Test
    void aFileEditedBeforeTheRestart_wins_andTheDifferenceIsReported() {
        jvm.loggers.setLogger("com.acme.a", Level.ERROR, sticky());
        String edited = jvm.export().replace("level: ERROR", "level: TRACE");

        Jvm restarted = new Jvm(parse(edited));

        assertEquals(List.of("logger com.acme.a"), restarted.takeover.differed());
        assertEquals(List.of(), restarted.loggers.activeOverrides());
    }

    @Test
    void aRuleChangedAfterTheExport_isStillTakenOver_andReportedAsDiffering() {
        RuleView added = jvm.rules.addRuleDrop("com.acme.HealthCheck",
                new CompiledMatchers(Level.INFO, "GET /health", false, null, null, false),
                RuleAttachOptions.sticky(), SampleFullPolicy.defaults());
        String exported = jvm.export();
        jvm.rules.alterRule(added.rule().id(), new RuleChange(null, false, null, null, null, Level.WARN, null, null, null,
                null), null, null);

        Jvm restarted = new Jvm(parse(exported));

        assertEquals(List.of("rule " + added.rule().id() + " (now vendor:drop-healthcheck)"), restarted.takeover.differed());
        assertEquals(List.of("vendor:health-noise", "vendor:drop-healthcheck"), restarted.ruleIds());
    }

    @Test
    void aCustomersOwnStickyOverride_equalToTheVendorValue_isNotTakenOver() {
        // The customer case: same name, same value as the file, but set in this JVM -- no id the
        // file could carry. It must keep winning over the vendor value.
        jvm.loggers.setLogger("com.acme.a", Level.WARN, sticky());

        Jvm restarted = new Jvm(parse(VENDOR_FILE));

        assertEquals(List.of(), restarted.takeover.takenOver());
        assertEquals(1, restarted.loggers.activeOverrides().size());
        assertEquals(1, stateStore.loadAll().size());
    }

    @Test
    void aRejectedFile_takesOverNothing() {
        tuneEverything();
        String broken = jvm.export() + "levle: typo\n";
        VendorDefaults rejected = VendorDefaultsFile.parse(broken, Path.of("/v.yaml"), false);
        assertEquals(VendorDefaults.Status.REJECTED, rejected.status());

        Jvm restarted = new Jvm(rejected);

        assertEquals(List.of(), restarted.takeover.takenOver());
        assertEquals(2, stateStore.loadAll().size());
        assertEquals(2, restarted.loggers.activeOverrides().size());
    }

    @Test
    void anAutoHandlerOverride_roundTrips() {
        jvm.handlers.setHandlerAuto(CONSOLE, SetHandlerLevelOptions.sticky());
        VendorDefaults exported = parse(jvm.export());
        assertEquals(HandlerLevelMode.AUTO, exported.handlers().get(CONSOLE).mode());

        Jvm restarted = new Jvm(exported);

        assertEquals(List.of("handler CONSOLE"), restarted.takeover.takenOver());
        assertEquals(List.of(), restarted.takeover.differed());
    }
}
