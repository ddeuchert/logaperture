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
import org.logaperture.api.HandlerInfo;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.HandlerResetOutcome;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.spi.ContextHandle;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/reset-to-native.md -- {@code reset --to-native} and the plain reset that undoes it. */
class ResetToNativeTest {

    /** Native DEBUG, vendor WARN -- the spec's worked example, with levels to tell them apart. */
    private static final String PERFMON = "org.perfmon4j";
    /** Named by the vendor file, never configured natively (inherits). */
    private static final String SUPPORT = "com.acme.support";
    /** Not named by the vendor file. */
    private static final String PLAIN = "com.acme.plain";

    private static final HandlerRef FILE = new HandlerRef("FILE");
    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef AUDIT = new HandlerRef("AUDIT");

    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private LevelControlService loggers;
    private HandlerBaselineRegistry handlerBaselines;
    private DefaultHandlerGroupRegistry defaultHandlerGroup;
    private HandlerLevelControlService handlers;
    private final List<LevelOverride> activeLoggerOverrides = new ArrayList<>();

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.setConfiguredLevel(PERFMON, Level.DEBUG);
        adapter.addKnownLogger(PLAIN);
        adapter.addHandler(FILE, Level.ALL);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.addHandler(AUDIT, Level.INFO);
        adapter.attachToRoot(CONSOLE);
        adapter.attachToRoot(FILE);
        adapter.attachToRoot(AUDIT);
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();

        baselines = new BaselineRegistry(Map.of(PERFMON, Level.WARN, SUPPORT, Level.TRACE));
        loggers = new LevelControlService(adapter, baselines, new OverrideRegistry(), CapabilityPolicy.allowAll(),
                auditLog, stateStore, "alice", "jmx");

        handlerBaselines = new HandlerBaselineRegistry(Map.of(
                FILE, new VendorDefaults.HandlerDefault(FILE, Level.WARN, HandlerLevelMode.FIXED, null),
                CONSOLE, new VendorDefaults.HandlerDefault(CONSOLE, null, HandlerLevelMode.AUTO, null)));
        defaultHandlerGroup = new DefaultHandlerGroupRegistry(List.of(FILE));
        ActiveLoggerFloor floor = () -> List.copyOf(activeLoggerOverrides);
        handlers = new HandlerLevelControlService(adapter, handlerBaselines, new HandlerOverrideRegistry(),
                defaultHandlerGroup, CapabilityPolicy.allowAll(), auditLog, stateStore, "alice", "jmx", floor);

        loggers.applyVendorDefaults(Instant.now());
        handlers.applyVendorDefaults(Instant.now());
    }

    // --- loggers ------------------------------------------------------------------------------------

    @Test
    void toNative_landsOnTheNativeLevel_andAPlainResetRestoresTheVendorLevel() {
        assertEquals(Level.WARN, adapter.effectiveLevel(PERFMON));

        ResetOutcome toNative = loggers.resetLogger(PERFMON, false, true);

        assertEquals(List.of(PERFMON), toNative.revertedLoggerNames());
        assertEquals(Level.DEBUG, adapter.effectiveLevel(PERFMON));
        assertTrue(row(PERFMON).resetToNative());
        assertEquals(Level.WARN, row(PERFMON).vendorDefaultLevel(), "the VENDOR column still shows the file's value");
        assertEquals(VendorDefaults.RESET_TO_NATIVE_REASON, lastAudit().reason());
        assertEquals(AuditRecord.Action.REVERSION, lastAudit().action());

        ResetOutcome plain = loggers.resetLogger(PERFMON, false);

        assertEquals(List.of(PERFMON), plain.revertedLoggerNames());
        assertEquals(Level.WARN, adapter.effectiveLevel(PERFMON));
        assertFalse(row(PERFMON).resetToNative());
        assertEquals(VendorDefaults.VENDOR_RESTORED_REASON, lastAudit().reason());
    }

    @Test
    void toNative_onALoggerWhoseNativeLevelIsInherited_clearsItsOwnLevel() {
        loggers.resetLogger(SUPPORT, false, true);

        assertEquals(Optional.empty(), adapter.configuredLevel(SUPPORT));
        assertEquals(Level.INFO, adapter.effectiveLevel(SUPPORT), "inherits from the root again");
    }

    @Test
    void toNative_removesAnOverride_andTheStateFileEntry() {
        loggers.setLogger(PERFMON, Level.TRACE, SetLevelOptions.sticky());
        assertEquals(1, stateStore.loadAll().size());

        loggers.resetLogger(PERFMON, true, true);

        assertEquals(Level.DEBUG, adapter.effectiveLevel(PERFMON));
        assertFalse(row(PERFMON).overrideActive());
        assertTrue(stateStore.loadAll().isEmpty());
    }

    @Test
    void aStickyOverride_stillNeedsIncludeSticky_andARefusalLeavesTheVendorLayerAlone() {
        loggers.setLogger(PERFMON, Level.TRACE, SetLevelOptions.sticky());

        assertThrows(IllegalArgumentException.class, () -> loggers.resetLogger(PERFMON, false, true));

        assertFalse(baselines.isResetToNative(PERFMON));
        assertEquals(Level.TRACE, adapter.effectiveLevel(PERFMON));
    }

    @Test
    void whileResetToNative_theVendorLayerNeverReassertsItself_soNativeChangesStick() {
        loggers.resetLogger(PERFMON, false, true);
        adapter.setConfiguredLevel(PERFMON, Level.ERROR); // e.g. a WildFly management change

        assertEquals(0, loggers.verifyAndReapply(Instant.now()));
        loggers.reapplyActiveOverrides(adapter); // a framework reconfiguration
        assertEquals(Level.ERROR, adapter.effectiveLevel(PERFMON), "follows the native configuration (T3)");

        loggers.resetLogger(PERFMON, false);
        assertEquals(Level.WARN, adapter.effectiveLevel(PERFMON));
        adapter.setConfiguredLevel(PERFMON, Level.ERROR);
        assertEquals(1, loggers.verifyAndReapply(Instant.now()), "back under the vendor layer");
        assertEquals(Level.WARN, adapter.effectiveLevel(PERFMON));
    }

    @Test
    void setOnALoggerResetToNative_thenEachReset() {
        loggers.resetLogger(PERFMON, false, true);
        loggers.setLogger(PERFMON, Level.TRACE, SetLevelOptions.defaults());
        assertEquals(Level.TRACE, adapter.effectiveLevel(PERFMON));

        loggers.resetLogger(PERFMON, false, true);
        assertEquals(Level.DEBUG, adapter.effectiveLevel(PERFMON), "--to-native clears the override, stays native");

        loggers.setLogger(PERFMON, Level.TRACE, SetLevelOptions.defaults());
        loggers.resetLogger(PERFMON, false);
        assertEquals(Level.WARN, adapter.effectiveLevel(PERFMON), "a plain reset clears both");
        assertFalse(baselines.isResetToNative(PERFMON));
    }

    @Test
    void onALoggerTheVendorFileDoesNotName_toNativeIsAPlainReset() {
        assertTrue(loggers.resetLogger(PLAIN, false, true).revertedLoggerNames().isEmpty(), "nothing to do (T7)");

        loggers.setLogger(PLAIN, Level.TRACE, SetLevelOptions.defaults());
        loggers.resetLogger(PLAIN, false, true);
        assertEquals(Level.INFO, adapter.effectiveLevel(PLAIN));
        assertFalse(baselines.isResetToNative(PLAIN));
    }

    @Test
    void aSecondToNative_andAPlainResetOfAVendorLogger_areNoOps() {
        loggers.resetLogger(PERFMON, false, true);

        assertTrue(loggers.resetLogger(PERFMON, false, true).revertedLoggerNames().isEmpty());
        loggers.resetLogger(PERFMON, false);
        assertTrue(loggers.resetLogger(PERFMON, false).revertedLoggerNames().isEmpty());
    }

    @Test
    void resetAllLoggers_toNative_coversEveryVendorLogger_andAPlainOneRestoresThem() {
        loggers.setLogger(PLAIN, Level.TRACE, SetLevelOptions.defaults());

        ResetOutcome toNative = loggers.resetAllLoggers(false, true);

        assertEquals(List.of(PLAIN, SUPPORT, PERFMON).stream().sorted().toList(),
                toNative.revertedLoggerNames().stream().sorted().toList());
        assertEquals(Level.DEBUG, adapter.effectiveLevel(PERFMON));
        assertEquals(2, loggers.resetToNativeCount());

        ResetOutcome plain = loggers.resetAllLoggers(false);

        assertEquals(List.of(PERFMON, SUPPORT).stream().sorted().toList(),
                plain.revertedLoggerNames().stream().sorted().toList());
        assertEquals(Level.WARN, adapter.effectiveLevel(PERFMON));
        assertEquals(Level.TRACE, adapter.effectiveLevel(SUPPORT));
        assertEquals(0, loggers.resetToNativeCount());
    }

    @Test
    void aPatternReset_reachesVendorLoggers_withoutAnOverride() {
        ResetOutcome outcome = loggers.resetLogger("org.*", false, true);

        assertEquals(List.of(PERFMON), outcome.revertedLoggerNames());
        assertEquals(Level.DEBUG, adapter.effectiveLevel(PERFMON));
    }

    // --- handlers -----------------------------------------------------------------------------------

    @Test
    void handler_toNative_landsOnItsNativeLevel_andAPlainResetRestoresTheVendorLevel() {
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));

        HandlerResetOutcome toNative = handlers.resetHandler(FILE, false, true);

        assertEquals(List.of(FILE), toNative.revertedHandlerRefs());
        assertEquals(Optional.of(Level.ALL), adapter.handlerLevel(FILE));
        assertTrue(handlerRow("FILE").resetToNative());
        assertEquals("WARN", handlerRow("FILE").vendorDefault());

        adapter.setHandlerLevel(FILE, Level.ERROR); // a native change while reset to native
        assertEquals(0, handlers.verifyAndReapply(Instant.now()), "the sweep leaves it alone");

        handlers.resetHandler(FILE, false);
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
        assertFalse(handlerRow("FILE").resetToNative());
    }

    @Test
    void aVendorAutoHandler_resetToNative_stopsTracking_untilAPlainReset() {
        activeLoggerOverrides.add(debugOverride());
        handlers.recomputeAuto();
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE));

        handlers.resetHandler(CONSOLE, false, true);
        handlers.recomputeAuto();
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE), "its native level, not tracking");

        handlers.resetHandler(CONSOLE, false);
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE), "tracking again (M3)");
    }

    @Test
    void aGroupReset_toNative_appliesToEachVendorMember() {
        handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.defaults());

        handlers.resetHandler(HandlerRef.ALL_HANDLERS, false, true);

        assertEquals(Optional.of(Level.ALL), adapter.handlerLevel(FILE));
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(AUDIT), "not named by the file: its baseline");
        assertTrue(handlerBaselines.isResetToNative(FILE));
        assertTrue(handlerBaselines.isResetToNative(CONSOLE));
    }

    @Test
    void aVendorHandlerCoveredByAGroupOverride_isResetToNativeUnderneath_withoutDisturbingTheGroup() {
        handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.DEBUG,
                SetHandlerLevelOptions.forDuration(Duration.ofMinutes(1)));

        handlers.resetHandler(FILE, false, true);
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(FILE), "the group override still decides");

        handlers.sweepExpiredOverrides(Instant.now().plus(Duration.ofMinutes(2)));
        assertEquals(Optional.of(Level.ALL), adapter.handlerLevel(FILE), "then it lands on native, not vendor");
    }

    @Test
    void aPlainGroupReset_putsEveryMemberBackOnItsVendorDefault() {
        handlers.resetHandler(FILE, false, true);
        handlers.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.defaults());

        handlers.resetHandler(HandlerRef.ALL_HANDLERS, false);

        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
        assertFalse(handlerBaselines.isResetToNative(FILE));
    }

    @Test
    void resetAllHandlers_toNative_thenPlain() {
        HandlerResetOutcome toNative = handlers.resetAllHandlers(false, true);

        assertEquals(List.of(FILE, CONSOLE).stream().map(HandlerRef::value).sorted().toList(),
                toNative.revertedHandlerRefs().stream().map(HandlerRef::value).sorted().toList());
        assertEquals(Optional.of(Level.ALL), adapter.handlerLevel(FILE));
        assertEquals(2, handlers.resetToNativeCount());

        handlers.resetAllHandlers(false);
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
        assertEquals(0, handlers.resetToNativeCount());
    }

    @Test
    void aPendingVendorHandler_resetToNative_isNeverApplied() {
        HandlerRef late = new HandlerRef("LATE");
        HandlerBaselineRegistry withLate = new HandlerBaselineRegistry(Map.of(
                late, new VendorDefaults.HandlerDefault(late, Level.ERROR, HandlerLevelMode.FIXED, null)));
        HandlerLevelControlService service = new HandlerLevelControlService(adapter, withLate,
                new HandlerOverrideRegistry(), new DefaultHandlerGroupRegistry(), CapabilityPolicy.allowAll(),
                auditLog, stateStore, "alice", "jmx", List::of);
        service.applyVendorDefaults(Instant.now());

        service.resetHandler(late, false, true);
        adapter.addHandler(late, Level.INFO);
        service.verifyAndReapply(Instant.now());

        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(late));
        assertTrue(service.pendingVendorHandlers().isEmpty(), "not reported as pending while reset to native");
    }

    // --- default handlers -----------------------------------------------------------------------------

    @Test
    void defaultHandlers_toNative_ignoreTheVendorList_andAPlainResetBringsItBack() {
        assertEquals("(vendor: FILE)", handlerRow("DEFAULT_HANDLERS").membersSummary());
        handlers.setDefaultHandlerMembers(List.of(AUDIT));

        List<HandlerRef> toNative = handlers.resetDefaultHandlerMembers(true);

        assertEquals(List.of(CONSOLE), toNative, "the deterministic rule's pick");
        assertTrue(handlerRow("DEFAULT_HANDLERS").membersSummary().startsWith("(auto: "));
        assertTrue(defaultHandlerGroup.explicit().isEmpty(), "the explicit membership is cleared too");
        assertEquals(1, handlers.resetToNativeCount());

        List<HandlerRef> plain = handlers.resetDefaultHandlerMembers(false);

        assertEquals(List.of(FILE), plain);
        assertEquals("(vendor: FILE)", handlerRow("DEFAULT_HANDLERS").membersSummary());
        assertEquals(VendorDefaults.VENDOR_RESTORED_REASON, lastAudit().reason());
    }

    // --- status ---------------------------------------------------------------------------------------

    @Test
    void theEnvStatusLine_countsWhatIsResetToNative() {
        VendorDefaults file = VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: org.perfmon4j
                    level: WARN
                """, Path.of("/v.yaml"), false);
        AggregateLevelControl aggregate = new AggregateLevelControl(null, Optional::empty, null, () -> true, file);
        aggregate.addContext(new AggregateLevelControl.ContextControl(ContextHandle.of("system", "system", adapter),
                loggers, handlers, new DoctorService(adapter, CapabilityPolicy.allowAll()),
                new TopService(adapter, CapabilityPolicy.allowAll()),
                new StormService(adapter, CapabilityPolicy.allowAll(), new StormDetector(3, Duration.ofSeconds(10),
                        Duration.ofSeconds(60), 4_000, 100, 8 * 1024)),
                new RuleService(adapter, CapabilityPolicy.allowAll(), auditLog, stateStore, "system", "alice", "jmx"),
                new EnvironmentReportService(adapter, CapabilityPolicy.allowAll())));
        assertEquals("loaded (1 logger)", aggregate.environmentReport().vendorDefaultsStatus());

        aggregate.resetLogger(PERFMON, false, true);

        assertEquals("loaded (1 logger), 1 reset to native", aggregate.environmentReport().vendorDefaultsStatus());
    }

    private LoggerInfo row(String name) {
        return loggers.listLoggers(null).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private HandlerInfo handlerRow(String ref) {
        return handlers.listHandlers().stream().filter(r -> r.ref().equals(ref)).findFirst().orElseThrow();
    }

    private AuditRecord lastAudit() {
        return auditLog.records().get(auditLog.records().size() - 1);
    }

    private static LevelOverride debugOverride() {
        return new LevelOverride("com.acme", Level.DEBUG, null, Instant.now(), "jmx", PersistenceTier.SESSION, null);
    }
}
