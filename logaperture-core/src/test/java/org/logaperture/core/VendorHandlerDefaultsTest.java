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
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "The baseline layer" -- "Handlers" and "Default handlers". */
class VendorHandlerDefaultsTest {

    private static final HandlerRef FILE = new HandlerRef("FILE");
    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef LATE = new HandlerRef("LATE");

    private FakeLoggingAdapter adapter;
    private HandlerBaselineRegistry baselines;
    private HandlerOverrideRegistry overrides;
    private DefaultHandlerGroupRegistry defaultHandlerGroup;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private final List<LevelOverride> activeLoggerOverrides = new ArrayList<>();
    private HandlerLevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(FILE, Level.ALL);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.attachToRoot(CONSOLE);
        adapter.attachToRoot(FILE);
        baselines = new HandlerBaselineRegistry(Map.of(
                FILE, new VendorDefaults.HandlerDefault(FILE, Level.WARN, HandlerLevelMode.FIXED, "quiet the file"),
                CONSOLE, new VendorDefaults.HandlerDefault(CONSOLE, null, HandlerLevelMode.AUTO, null),
                LATE, new VendorDefaults.HandlerDefault(LATE, Level.ERROR, HandlerLevelMode.FIXED, null)));
        overrides = new HandlerOverrideRegistry();
        defaultHandlerGroup = new DefaultHandlerGroupRegistry(List.of(FILE));
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        ActiveLoggerFloor floor = () -> List.copyOf(activeLoggerOverrides);
        service = new HandlerLevelControlService(adapter, baselines, overrides, defaultHandlerGroup,
                CapabilityPolicy.allowAll(), auditLog, stateStore, "alice", "jmx", floor);
    }

    @Test
    void applyVendorDefaults_fixedLevel_andAutoTrackingItsNativeLevel() {
        service.applyVendorDefaults(Instant.now());

        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
        assertEquals(Optional.of(Level.ALL), baselines.nativeLevel(FILE), "native captured before applying");
        assertEquals(Optional.of(Level.WARN), baselines.get(FILE));
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE),
                "AUTO with no logger overrides sits at its own native level");

        AuditRecord fileRecord = auditLog.records().stream()
                .filter(r -> r.loggerName().equals("FILE")).findFirst().orElseThrow();
        assertEquals("vendor-defaults", fileRecord.source());
        assertEquals("ALL", fileRecord.previousValue());
        assertEquals("quiet the file", fileRecord.reason());
    }

    @Test
    void vendorAuto_tracksTheLowestActiveLoggerOverride() {
        service.applyVendorDefaults(Instant.now());

        activeLoggerOverrides.add(debugOverride("com.acme"));
        service.recomputeAuto();
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE));

        activeLoggerOverrides.clear();
        service.recomputeAuto();
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void operatorOverrideOnAVendorAutoHandler_wins_andResetReturnsItToTracking() {
        service.applyVendorDefaults(Instant.now());
        activeLoggerOverrides.add(debugOverride("com.acme"));

        service.setHandlerLevel(CONSOLE, Level.ERROR, SetHandlerLevelOptions.defaults());
        service.recomputeAuto();
        assertEquals(Optional.of(Level.ERROR), adapter.handlerLevel(CONSOLE), "the operator's fixed level wins");

        service.resetHandler(CONSOLE, false);
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE), "back to AUTO tracking (M3)");
    }

    @Test
    void resetHandler_andExpiry_landOnTheFixedVendorLevel() {
        service.applyVendorDefaults(Instant.now());

        service.setHandlerLevel(FILE, Level.TRACE, SetHandlerLevelOptions.defaults());
        service.resetHandler(FILE, false);
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));

        service.setHandlerLevel(FILE, Level.TRACE,
                new SetHandlerLevelOptions(null, Duration.ofMinutes(1), PersistenceTier.FOR));
        service.sweepExpiredOverrides(Instant.now().plus(Duration.ofMinutes(2)));
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
    }

    @Test
    void allHandlersOverride_coversTheVendorLayer_untilReset() {
        service.applyVendorDefaults(Instant.now());

        service.setHandlerLevel(HandlerRef.ALL_HANDLERS, Level.DEBUG, SetHandlerLevelOptions.defaults());
        service.recomputeAuto();
        service.verifyAndReapply(Instant.now());
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(FILE));
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE), "AUTO doesn't fight the group override");

        service.resetHandler(HandlerRef.ALL_HANDLERS, false);
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void aHandlerThatDoesNotResolveYet_isAppliedByALaterSweepTick() {
        service.applyVendorDefaults(Instant.now());
        assertEquals(Optional.empty(), adapter.handlerLevel(LATE));

        adapter.addHandler(LATE, Level.INFO); // e.g. WildFly finished resolving handler names
        int reapplied = service.verifyAndReapply(Instant.now());

        assertEquals(1, reapplied);
        assertEquals(Optional.of(Level.ERROR), adapter.handlerLevel(LATE));
        assertEquals(Optional.of(Level.INFO), baselines.nativeLevel(LATE));
        assertEquals(0, service.verifyAndReapply(Instant.now()), "applied once, then quiet");
    }

    @Test
    void verificationSweep_reappliesADriftedFixedVendorLevel() {
        service.applyVendorDefaults(Instant.now());
        adapter.setHandlerLevel(FILE, Level.ALL); // a :reload re-read the server config

        assertEquals(1, service.verifyAndReapply(Instant.now()));
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
        AuditRecord last = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals("verification-sweep", last.source());
    }

    @Test
    void reapplyActiveOverrides_restoresTheVendorLayer() {
        service.applyVendorDefaults(Instant.now());
        adapter.setHandlerLevel(FILE, Level.ALL);

        service.reapplyActiveOverrides(adapter);

        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
    }

    @Test
    void persistedOverride_resumesOnTopOfTheVendorLevel() {
        stateStore.saveHandler(HandlerLevelOverride.fixed(FILE, Level.TRACE, "customer", Instant.now(), "jmx",
                PersistenceTier.STICKY, null));

        service.applyVendorDefaults(Instant.now());
        service.resumeFromStateStore(Instant.now());

        assertEquals(Optional.of(Level.TRACE), adapter.handlerLevel(FILE));
        service.resetHandler(FILE, true);
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(FILE));
    }

    @Test
    void defaultHandlers_explicitBeatsVendor_andResetLandsOnVendor() {
        service.applyVendorDefaults(Instant.now());
        assertEquals("(vendor: FILE)", defaultHandlersRow().membersSummary());

        service.setDefaultHandlerMembers(List.of(CONSOLE));
        assertEquals("CONSOLE", defaultHandlersRow().membersSummary());

        service.setDefaultHandlerMembers(List.of()); // logctl reset default-handler
        assertEquals("(vendor: FILE)", defaultHandlersRow().membersSummary());
    }

    @Test
    void defaultHandlers_fallBackToTheRule_whenNoVendorMemberResolves() {
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry(List.of(new HandlerRef("NOPE")));

        assertEquals(List.of(CONSOLE), registry.members(adapter), "the deterministic rule's pick");
        assertTrue(!registry.vendorMembersInEffect(adapter));
    }

    @Test
    void listHandlers_showsTheVendorColumn() {
        service.applyVendorDefaults(Instant.now());

        List<HandlerInfo> rows = service.listHandlers();
        assertEquals("WARN", row(rows, "FILE").vendorDefault());
        assertEquals("AUTO", row(rows, "CONSOLE").vendorDefault());
        assertNull(row(rows, "DEFAULT_HANDLERS").vendorDefault());
    }

    private HandlerInfo defaultHandlersRow() {
        return row(service.listHandlers(), "DEFAULT_HANDLERS");
    }

    private static HandlerInfo row(List<HandlerInfo> rows, String ref) {
        return rows.stream().filter(r -> r.ref().equals(ref)).findFirst().orElseThrow();
    }

    private static LevelOverride debugOverride(String logger) {
        return new LevelOverride(logger, Level.DEBUG, null, Instant.now(), "jmx", PersistenceTier.SESSION, null);
    }
}
