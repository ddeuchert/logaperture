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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults-export.md "Testing" -- the writer, and what goes into the export. */
class VendorDefaultsExportTest {

    private static final HandlerRef FILE = new HandlerRef("FILE");
    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef AUDIT = new HandlerRef("AUDIT");

    private static final String VENDOR_FILE = """
            schemaVersion: 1
            loggers:
              - name: com.acme.a
                level: WARN
                reason: "noisy at INFO"
              - name: com.acme.b
                level: DEBUG
            handlers:
              - name: FILE
                level: WARN
              - name: CONSOLE
                level: AUTO
            defaultHandlers:
              - FILE
            rules:
              - id: health-noise
                action: drop
                logger: com.acme.health
                messageContains: "ping ok"
                sampleFull: false
              - id: retry-trace
                action: trim
                logger: org.apache.http
                below: WARN
                frames: 2
            """;

    private final InMemoryAuditLog auditLog = new InMemoryAuditLog();
    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private FakeLoggingAdapter adapter;
    private LevelControlService loggers;
    private HandlerLevelControlService handlers;
    private RuleService rules;
    private AggregateLevelControl aggregate;

    @BeforeEach
    void setUp() {
        start(VendorDefaultsFile.parse(VENDOR_FILE, Path.of("/opt/app/vendor-defaults.yaml"), false),
                CapabilityPolicy.allowAll());
    }

    /** The same composition the containers use, minus the adapter's framework. */
    private void start(VendorDefaults vendor, CapabilityPolicy policy) {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(FILE, Level.ALL);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.addHandler(AUDIT, Level.INFO);
        OverrideRegistry overrides = new OverrideRegistry();
        loggers = new LevelControlService(adapter, new BaselineRegistry(vendor.loggerLevels(), vendor.loggerReasons()),
                overrides, policy, auditLog, stateStore, "alice", "jmx");
        ActiveLoggerFloor floor = () -> List.copyOf(overrides.all().values());
        handlers = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(vendor.handlers()),
                new HandlerOverrideRegistry(), new DefaultHandlerGroupRegistry(vendor.defaultHandlers().orElse(List.of())),
                policy, auditLog, stateStore, "alice", "jmx", floor);
        rules = new RuleService(adapter, policy, auditLog, stateStore, "system", "alice", "jmx");
        rules.registerDropSupport();
        rules.registerTrimSupport();
        loggers.applyVendorDefaults(Instant.now());
        handlers.applyVendorDefaults(Instant.now());
        rules.attachVendorRules(vendor.rules(), vendor.status() == VendorDefaults.Status.LOADED, Instant.now());
        aggregate = new AggregateLevelControl("none", Optional::empty, null, () -> true, vendor);
        aggregate.register(new AggregateLevelControl.ContextControl(ContextHandle.of("system", "system", adapter),
                loggers, handlers, new DoctorService(adapter, policy), new TopService(adapter, policy),
                new StormService(adapter, policy, new StormDetector(3, Duration.ofSeconds(10),
                        Duration.ofSeconds(60), 4_000, 100, 8 * 1024)),
                rules, new EnvironmentReportService(adapter, policy)));
    }

    private String exportText() {
        return aggregate.exportVendorDefaults();
    }

    private VendorDefaults exported() {
        VendorDefaults parsed = VendorDefaultsFile.parse(exportText(), Path.of("/exported.yaml"), false);
        assertEquals(VendorDefaults.Status.LOADED, parsed.status(), parsed.errors().toString());
        return parsed;
    }

    private static SetLevelOptions tier(PersistenceTier tier, String reason) {
        return new SetLevelOptions(reason, tier == PersistenceTier.FOR ? Duration.ofHours(1) : null, tier, true);
    }

    // --- the writer ------------------------------------------------------------------------------

    @Test
    void anUntouchedJvm_exportsTheFileItStartedWith() {
        VendorDefaults original = VendorDefaultsFile.parse(VENDOR_FILE, Path.of("/v.yaml"), false);
        VendorDefaults exported = exported();

        assertEquals(List.copyOf(original.loggers().values()), List.copyOf(exported.loggers().values()));
        assertEquals(original.handlers().keySet().stream().sorted(java.util.Comparator.comparing(HandlerRef::value))
                .toList(), List.copyOf(exported.handlers().keySet()));
        assertEquals(List.copyOf(original.handlers().values()).stream()
                .sorted(java.util.Comparator.comparing(h -> h.ref().value())).toList(),
                List.copyOf(exported.handlers().values()));
        assertEquals(original.defaultHandlers(), exported.defaultHandlers());
        assertEquals(original.rules(), exported.rules());
    }

    @Test
    void theHeader_namesTheFileItStartedFrom_andTheOutputIsDeterministic() {
        String text = exportText();

        assertTrue(text.startsWith("# Exported by logctl export vendor-defaults, "), text);
        assertTrue(text.contains("\n# Started from: /opt/app/vendor-defaults.yaml\nschemaVersion: 1\n"), text);
        String withoutTime = text.lines().skip(1).reduce("", (a, b) -> a + b + "\n");
        assertEquals(withoutTime, exportText().lines().skip(1).reduce("", (a, b) -> a + b + "\n"));
    }

    @Test
    void awkwardText_survivesTheRoundTrip() {
        List<String> awkward = List.of("a # not a comment", "key: value", "\"leading quote", "it's", "back\\slash",
                "two\nlines", "tab\there", "true", "-dash", "'single'", "plain.Value_1");
        for (String text : awkward) {
            VendorDefaultsExport export = new VendorDefaultsExport(List.of(),
                    List.of(new VendorDefaults.LoggerDefault("com.acme.x", Level.INFO, text)), List.of(), null,
                    List.of(new VendorDefaults.RuleDefault("vendor:r", "drop", "com.acme.x",
                            new CompiledMatchers(Level.INFO, text, false, null, text, false), text,
                            SampleFullPolicy.every(Duration.ofSeconds(90)), 0, false)),
                    Map.of(), List.of());
            VendorDefaults parsed = VendorDefaultsFile.parse(VendorDefaultsFile.write(export), Path.of("/x"), false);

            assertEquals(VendorDefaults.Status.LOADED, parsed.status(), text + ": " + parsed.errors());
            assertEquals(text, parsed.loggers().get("com.acme.x").reason(), text);
            VendorDefaults.RuleDefault rule = parsed.rules().get(0);
            assertEquals(export.rules().get(0), rule, text);
        }
    }

    @Test
    void everyRuleField_roundTrips() {
        List<VendorDefaults.RuleDefault> all = List.of(
                new VendorDefaults.RuleDefault("vendor:a", "drop", "com.acme.a",
                        new CompiledMatchers(Level.ERROR, "Boom", true, "java.io.IOException", "reset", true),
                        "why", SampleFullPolicy.disabled(), 0, false),
                new VendorDefaults.RuleDefault("vendor:b", "trim", "com.acme.b",
                        new CompiledMatchers(Level.DEBUG, null, false, null, null, false), null,
                        SampleFullPolicy.defaults(), 5, true));
        VendorDefaultsExport export = new VendorDefaultsExport(List.of(), List.of(), List.of(), null, all, Map.of(),
                List.of());

        assertEquals(all, VendorDefaultsFile.parse(VendorDefaultsFile.write(export), Path.of("/x"), false).rules());
    }

    // --- loggers ---------------------------------------------------------------------------------

    @Test
    void aStickyLoggerOverride_isExported_andReplacesTheVendorLevel_butSessionAndForAreIgnored() {
        loggers.setLogger("com.acme.a", Level.ERROR, tier(PersistenceTier.STICKY, "INC-7"));
        loggers.setLogger("com.acme.new", Level.TRACE, tier(PersistenceTier.STICKY, null));
        loggers.setLogger("com.acme.b", Level.TRACE, tier(PersistenceTier.SESSION, null));
        loggers.setLogger("com.acme.temp", Level.TRACE, tier(PersistenceTier.FOR, null));

        Map<String, VendorDefaults.LoggerDefault> exported = exported().loggers();

        assertEquals(new VendorDefaults.LoggerDefault("com.acme.a", Level.ERROR, "INC-7"), exported.get("com.acme.a"));
        assertEquals(Level.TRACE, exported.get("com.acme.new").level());
        assertEquals(Level.DEBUG, exported.get("com.acme.b").level(), "X2: the file's value, not the session one");
        assertFalse(exported.containsKey("com.acme.temp"));
        assertEquals(List.of("com.acme.a", "com.acme.b", "com.acme.new"), List.copyOf(exported.keySet()),
                "sorted by name");
    }

    @Test
    void aLoggerResetToNative_isLeftOut() {
        loggers.resetLogger("com.acme.b", false, true);

        assertFalse(exported().loggers().containsKey("com.acme.b"));
    }

    // --- handlers --------------------------------------------------------------------------------

    @Test
    void handlers_stickyOverrideWins_groupsExpand_memberBeatsGroup_andAutoExportsAsAuto() {
        handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.ERROR,
                new SetHandlerLevelOptions("quiet", null, PersistenceTier.STICKY));
        handlers.setHandlerLevel(AUDIT, Level.DEBUG, SetHandlerLevelOptions.sticky());
        handlers.setHandlerAuto(CONSOLE, SetHandlerLevelOptions.sticky());

        Map<HandlerRef, VendorDefaults.HandlerDefault> exported = exported().handlers();

        assertEquals(new VendorDefaults.HandlerDefault(FILE, Level.ERROR, HandlerLevelMode.FIXED, "quiet"),
                exported.get(FILE), "ALL_HANDLERS expanded to each member");
        assertEquals(Level.DEBUG, exported.get(AUDIT).level(), "a member's own sticky override wins");
        assertEquals(HandlerLevelMode.AUTO, exported.get(CONSOLE).mode());
        assertFalse(exported.containsKey(HandlerRef.ALL_HANDLERS));
    }

    @Test
    void aSessionHandlerOverride_isIgnored_andAHandlerResetToNative_isLeftOut() {
        handlers.setHandlerLevel(FILE, Level.TRACE, SetHandlerLevelOptions.defaults());
        handlers.resetHandler(CONSOLE, false, true);

        Map<HandlerRef, VendorDefaults.HandlerDefault> exported = exported().handlers();

        assertEquals(Level.WARN, exported.get(FILE).level());
        assertFalse(exported.containsKey(CONSOLE));
    }

    // --- default handlers ------------------------------------------------------------------------

    @Test
    void defaultHandlers_explicitMembership_beatsTheFile_andResetToNativeLeavesItOut() {
        assertEquals(Optional.of(List.of(FILE)), exported().defaultHandlers());

        handlers.setDefaultHandlerMembers(List.of(CONSOLE, AUDIT));
        assertEquals(Optional.of(List.of(AUDIT, CONSOLE)), exported().defaultHandlers(), "sorted by name");

        handlers.resetDefaultHandlerMembers(true);
        assertEquals(Optional.empty(), exported().defaultHandlers());
    }

    // --- rules -----------------------------------------------------------------------------------

    @Test
    void aStickyVendorRuleAlteration_isExportedUnderTheFilesId_aSessionOneIsNot() {
        rules.alterRule("vendor:health-noise", new RuleChange(RuleChange.Field.set("pong"), false, null, null,
                null, null, null, null, null, null), PersistenceTier.STICKY, null);
        rules.alterRule("vendor:retry-trace", new RuleChange(null, false, null, null, null, null, null, 9, null,
                null), PersistenceTier.SESSION, null);

        List<VendorDefaults.RuleDefault> exported = exported().rules();

        assertEquals("vendor:health-noise", exported.get(0).id());
        assertEquals("pong", exported.get(0).matchers().messageContains());
        assertEquals(2, exported.get(1).frames(), "X2: the file's definition, not the session alteration");
    }

    @Test
    void aVendorRuleSwitchedOff_isLeftOut() {
        rules.resetRule("vendor:health-noise", false, true);

        assertEquals(List.of("vendor:retry-trace"), exported().rules().stream()
                .map(VendorDefaults.RuleDefault::id).toList());
    }

    @Test
    void stickyOperatorRules_getDerivedNames_withAWasComment_andSessionOnesAreLeftOut() {
        rules.addRuleDrop("com.acme.HealthCheck", new CompiledMatchers(Level.INFO, "x", false, null, null, false),
                RuleAttachOptions.sticky(), SampleFullPolicy.defaults());
        rules.addRuleDrop("org.other.HealthCheck", new CompiledMatchers(Level.INFO, "y", false, null, null, false),
                RuleAttachOptions.sticky(), SampleFullPolicy.defaults());
        rules.addRuleTrim("com.acme.Session", CompiledMatchers.matchAll(), RuleAttachOptions.defaults(), 0, false);

        String text = exportText();
        List<VendorDefaults.RuleDefault> exported = exported().rules();

        assertEquals(List.of("vendor:health-noise", "vendor:retry-trace", "vendor:drop-healthcheck",
                "vendor:drop-healthcheck-2"), exported.stream().map(VendorDefaults.RuleDefault::id).toList());
        assertTrue(text.contains("  # was r1\n  - id: drop-healthcheck\n"), text);
        assertTrue(text.contains("  # was r2\n  - id: drop-healthcheck-2\n"), text);
    }

    @Test
    void derivedNames_areSlugged_andFitTheIdLimit() {
        var rule = new org.logaperture.api.Trim("r1", "com.acme.Very$Long_Inner.ClassNameThatGoesOnAndOnAndOnForever",
                CompiledMatchers.matchAll(), null, PersistenceTier.STICKY, null, Instant.now(), 0, false);

        String name = RuleService.derivedName(rule, java.util.Set.of());

        assertTrue(name.matches("[a-z0-9-]{1,40}"), name);
        assertTrue(name.startsWith("trim-classnamethatgoeson"), name);
        assertEquals("trim-root", RuleService.derivedName(new org.logaperture.api.Trim("r2", "",
                CompiledMatchers.matchAll(), null, PersistenceTier.STICKY, null, Instant.now(), 0, false),
                java.util.Set.of()));
    }

    // --- settings the file can't hold (code review of PR #102) --------------------------------------

    @Test
    void anEmptyReason_isLeftOut_andACarriageReturnBecomesANewline() {
        loggers.setLogger("com.acme.empty", Level.DEBUG, tier(PersistenceTier.STICKY, ""));
        loggers.setLogger("com.acme.cr", Level.DEBUG, tier(PersistenceTier.STICKY, "line one\r\nline two\rthree"));

        Map<String, VendorDefaults.LoggerDefault> exported = exported().loggers();

        assertNull(exported.get("com.acme.empty").reason());
        assertEquals("line one\nline two\nthree", exported.get("com.acme.cr").reason());
    }

    @Test
    void aRuleOnTheRootLogger_isLeftOutWithAComment_andTheRestIsStillExported() {
        rules.addRuleTrim("", CompiledMatchers.matchAll(), RuleAttachOptions.sticky(), 0, false);
        rules.addRuleDrop("com.acme.Empty", new CompiledMatchers(Level.INFO, "", false, null, null, false),
                RuleAttachOptions.sticky(), SampleFullPolicy.defaults());
        loggers.setLogger("com.acme.kept", Level.DEBUG, tier(PersistenceTier.STICKY, null));

        String text = exportText();
        VendorDefaults exported = exported();

        assertTrue(text.contains("# Not exported: trim rule r1 on '' -- the file can't hold it"), text);
        assertTrue(text.contains("# Not exported: drop rule r2 on 'com.acme.Empty' -- the file can't hold it"), text);
        assertEquals(List.of("vendor:health-noise", "vendor:retry-trace"), exported.rules().stream()
                .map(VendorDefaults.RuleDefault::id).toList());
        assertTrue(exported.loggers().containsKey("com.acme.kept"));
    }

    // --- nothing to export, capability -----------------------------------------------------------

    @Test
    void nothingToExport_isStillAValidFile() {
        start(VendorDefaults.none(), CapabilityPolicy.allowAll());

        String text = exportText();

        assertTrue(text.contains("# Started from: no vendor defaults file\nschemaVersion: 1\n"), text);
        VendorDefaults parsed = exported();
        assertTrue(parsed.isEmpty());
        assertNull(parsed.defaultHandlers().orElse(null));
    }

    @Test
    void theExport_needsView() {
        start(VendorDefaults.none(), capability -> capability != Capability.VIEW);

        assertThrows(CapabilityDeniedException.class, this::exportText);
    }
}
