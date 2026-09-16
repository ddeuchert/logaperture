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
import org.logaperture.api.BackendInfo;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.EnvironmentReport;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerByteCount;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl.ContextControl;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.StateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-context fan-out logic — see doc/specs/wildfly-support.md, Slice
 * 1's "Testing". Exercised with fake contexts even though {@code none}
 * never produces more than one.
 */
class AggregateLevelControlTest {

    private StateStore sharedStore;
    private InMemoryAuditLog auditLog;
    private AggregateLevelControl aggregate;

    @BeforeEach
    void setUp() {
        sharedStore = new InMemoryStateStore();
        auditLog = new InMemoryAuditLog();
        aggregate = new AggregateLevelControl();
    }

    private final class Ctx {
        final FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        final LevelControlService service;
        final HandlerLevelControlService handlerService;
        final DoctorService doctorService;
        final TopService topService;
        final EnvironmentReportService environmentReportService;
        final ContextControl control;

        Ctx(String key) {
            this(key, CapabilityPolicy.allowAll());
        }

        Ctx(String key, CapabilityPolicy policy) {
            this(key, policy, "jmx");
        }

        Ctx(String key, CapabilityPolicy policy, String source) {
            OverrideRegistry overrides = new OverrideRegistry();
            service = new LevelControlService(adapter, new BaselineRegistry(), overrides,
                    policy, auditLog, sharedStore, "alice", source);
            ActiveLoggerFloor activeLoggerFloor = () -> List.copyOf(overrides.all().values());
            handlerService = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(),
                    new HandlerOverrideRegistry(), policy, auditLog, sharedStore, "alice", "jmx", activeLoggerFloor);
            doctorService = new DoctorService(adapter, policy);
            topService = new TopService(adapter, policy);
            environmentReportService = new EnvironmentReportService(adapter, policy);
            control = new ContextControl(ContextHandle.of(key, key, adapter), service, handlerService,
                    doctorService, topService, environmentReportService);
        }
    }

    // --- listLoggers ------------------------------------------------------------------------------

    @Test
    void listLoggers_concatenatesEveryContext_taggedWithItsContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        system.adapter.addKnownLogger("com.shared.Util");
        system.adapter.addKnownLogger("org.jboss.as.server");
        app.adapter.addKnownLogger("com.shared.Util");
        app.adapter.addKnownLogger("com.myapp.Worker");
        aggregate.register(system.control);
        aggregate.register(app.control);

        List<LoggerInfo> rows = aggregate.listLoggers(null);

        assertEquals(2, rows.stream().filter(r -> r.name().equals("com.shared.Util")).count(),
                "a name present in both contexts produces two rows");
        assertTrue(rows.stream().anyMatch(r -> r.name().equals("org.jboss.as.server") && r.context().equals("system")));
        assertTrue(rows.stream().anyMatch(r -> r.name().equals("com.myapp.Worker") && r.context().equals("myapp.war")));
        assertTrue(rows.stream().allMatch(r -> r.context() != null), "every row is tagged with its context");
    }

    // --- setLevel / resetLevel / resetAll broadcast --------------------------------------------

    @Test
    void setLevel_broadcastsToEveryContext_creatingTheLoggerWhereAbsent() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        system.adapter.addKnownLogger("com.shared.Util"); // known only in system
        aggregate.register(system.control);
        aggregate.register(app.control);

        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.defaults());

        assertEquals(Level.DEBUG, system.adapter.effectiveLevel("com.shared.Util"));
        assertEquals(Level.DEBUG, app.adapter.effectiveLevel("com.shared.Util"),
                "broadcast created and set the logger in the context that did not know it");
    }

    @Test
    void setLevel_blockingHandlers_keepsTheStricterFloorAcrossContextsSharingARef() {
        // Code-review finding: two contexts collapsing to the same
        // ALL_HANDLERS ref (doc/specs/handler-floor-control.md Decision #7)
        // can legitimately report different currentLevels -- the old
        // putIfAbsent-based union silently dropped whichever context wasn't
        // first, understating the real block.
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        system.adapter.addKnownLogger("com.shared.Util");
        app.adapter.addKnownLogger("com.shared.Util");
        system.adapter.addHandlerWithPerTargetLevel(HandlerRef.ALL_HANDLERS, Level.INFO, "com.shared.Util");
        app.adapter.addHandlerWithPerTargetLevel(HandlerRef.ALL_HANDLERS, Level.WARN, "com.shared.Util"); // stricter
        aggregate.register(system.control);
        aggregate.register(app.control);

        var result = aggregate.setLevel("com.shared.Util", Level.TRACE, SetLevelOptions.defaults());

        assertEquals(1, result.blockingHandlers().size());
        assertEquals(Level.WARN, result.blockingHandlers().get(0).currentLevel(),
                "the stricter of the two contexts' readings, not whichever was registered first");
    }

    @Test
    void resetLevel_revertsInEveryContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.defaults());

        aggregate.resetLevel("com.shared.Util");

        assertEquals(Level.INFO, system.adapter.effectiveLevel("com.shared.Util"));
        assertEquals(Level.INFO, app.adapter.effectiveLevel("com.shared.Util"));
    }

    @Test
    void resetAll_revertsEveryOverrideInEveryContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setLevel("com.a.One", Level.DEBUG, SetLevelOptions.defaults());
        aggregate.setLevel("com.b.Two", Level.TRACE, SetLevelOptions.defaults());

        aggregate.resetAll();

        for (Ctx c : List.of(system, app)) {
            assertEquals(Level.INFO, c.adapter.effectiveLevel("com.a.One"));
            assertEquals(Level.INFO, c.adapter.effectiveLevel("com.b.Two"));
        }
    }

    @Test
    void resetAll_revertsHandlerOverridesToo() {
        Ctx system = new Ctx("system");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.setLevel("com.a.One", Level.DEBUG, SetLevelOptions.defaults());
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());

        aggregate.resetAll();

        assertEquals(Level.INFO, system.adapter.effectiveLevel("com.a.One"));
        assertEquals(Level.INFO, system.adapter.handlerLevel(console).orElseThrow());
    }

    // --- diagnose (doc/specs/doctor.md) --------------------------------------------------------

    @Test
    void diagnose_concatenatesEveryContext_taggedWithItsContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        aggregate.register(system.control);
        aggregate.register(app.control);

        List<DoctorFinding> findings = aggregate.diagnose();

        assertTrue(findings.stream().anyMatch(f -> "system".equals(f.context())));
        assertTrue(findings.stream().anyMatch(f -> "myapp.war".equals(f.context())));
        assertTrue(findings.stream().allMatch(f -> f.context() != null), "every row is tagged with its context");
    }

    // --- environmentReport (doc/specs/environment-report.md) -----------------------------------

    @Test
    void environmentReport_noRegisteredContext_stillCarriesProcessWideFacts() {
        EnvironmentReport report = aggregate.environmentReport();

        assertNotNull(report.agentVersion());
        assertEquals(System.getProperty("java.version"), report.javaVersion());
        assertEquals(System.getProperty("java.vendor"), report.javaVendor());
        assertEquals(System.getProperty("os.name"), report.osName());
        assertNull(report.backendName(), "no context registered -- nothing to ask for a backend");
        assertNull(report.containerName(), "the no-arg constructor -- no container to report");
    }

    @Test
    void environmentReport_containerNameVersionAndStateFilePath_fromConstructor() {
        AggregateLevelControl wildfly = new AggregateLevelControl(
                "WildFly", () -> Optional.of("34.0.1.Final"), "/opt/jboss/.logaperture/instances/abc.state.yaml");

        EnvironmentReport report = wildfly.environmentReport();

        assertEquals("WildFly", report.containerName());
        assertEquals("34.0.1.Final", report.containerVersion());
        assertEquals("/opt/jboss/.logaperture/instances/abc.state.yaml", report.stateFilePath());
    }

    @Test
    void environmentReport_noStateFilePath_isAbsentNotAFailure() {
        // The no-arg constructor's own case -- a StateStore with no single
        // filesystem location to name (doc/specs/environment-report.md
        // "State file"), same "absent, never a failure" contract as every
        // other unresolved fact.
        assertNull(aggregate.environmentReport().stateFilePath());
    }

    @Test
    void environmentReport_containerVersionSupplier_reinvokedOnEveryCall_notCachedAtConstruction() {
        // doc/specs/environment-report.md: the real-WildFly finding that
        // motivated the supplier in the first place -- a fact genuinely
        // unresolvable at construction time (jboss.home.dir not yet visible
        // to System.getProperty) can still resolve later, once the server
        // finishes its own bootstrap. A one-shot resolve-at-construction
        // would bake in "no version" forever; the supplier must not be
        // memoised.
        java.util.concurrent.atomic.AtomicReference<Optional<String>> version =
                new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
        AggregateLevelControl wildfly = new AggregateLevelControl("WildFly", version::get, null);

        assertNull(wildfly.environmentReport().containerVersion(), "not yet resolvable, same as real premain timing");

        version.set(Optional.of("26.1.3.Final"));

        assertEquals("26.1.3.Final", wildfly.environmentReport().containerVersion(),
                "re-invoked fresh, not cached from the first call");
    }

    @Test
    void environmentReport_containerVersionSupplierThrows_containerVersionIsAbsentNotAFailure() {
        AggregateLevelControl wildfly = new AggregateLevelControl("WildFly", () -> {
            throw new RuntimeException("simulated jboss.home.dir resolution failure");
        }, null);

        assertNull(wildfly.environmentReport().containerVersion());
    }

    @Test
    void environmentReport_backendInfo_fromFirstContextThatResolvesOne() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        app.adapter.setBackendInfo(new BackendInfo("JBoss LogManager", "3.1.1.Final"));
        aggregate.register(system.control); // default EMPTY -- nothing to resolve
        aggregate.register(app.control);

        EnvironmentReport report = aggregate.environmentReport();

        assertEquals("JBoss LogManager", report.backendName());
        assertEquals("3.1.1.Final", report.backendVersion());
    }

    @Test
    void environmentReport_oneContextThrowsOnBackendInfo_stillReturnsUsingTheOtherContext() {
        // doc/specs/environment-report.md "Failure handling": a misbehaving
        // adapter must degrade that one fact, never fail the whole command.
        // Keys chosen so the throwing context sorts (and so is tried) first --
        // sortedByKey() is alphabetical -- genuinely exercising the catch,
        // rather than the healthy context's break short-circuiting first.
        Ctx broken = new Ctx("a-broken");
        Ctx healthy = new Ctx("z-healthy");
        broken.adapter.throwOnBackendInfo();
        healthy.adapter.setBackendInfo(new BackendInfo("JBoss LogManager", "3.1.1.Final"));
        aggregate.register(broken.control);
        aggregate.register(healthy.control);

        EnvironmentReport report = aggregate.environmentReport();

        assertEquals("JBoss LogManager", report.backendName());
        assertEquals("3.1.1.Final", report.backendVersion());
    }

    @Test
    void environmentReport_everyContextThrowsOnBackendInfo_backendIsAbsentNotAFailure() {
        Ctx broken = new Ctx("system");
        broken.adapter.throwOnBackendInfo();
        aggregate.register(broken.control);

        EnvironmentReport report = aggregate.environmentReport();

        assertNull(report.backendName());
        assertNull(report.backendVersion());
    }

    @Test
    void environmentReport_diagnosticsLevelProperty_surfacedWhenSet() {
        System.setProperty("logaperture.diagnostics.level", "DEBUG");
        try {
            assertEquals("DEBUG", aggregate.environmentReport().diagnosticsLevel());
        } finally {
            System.clearProperty("logaperture.diagnostics.level");
        }
    }

    // --- topLoggers (doc/specs/top.md) -----------------------------------------------------------

    @Test
    void topLoggers_mergesEveryContext_taggedWithItsContext_sortedWorstFirst() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        system.adapter.setByteCounts(List.of(new LoggerByteCount("org.apache.http", 1_000L, 0L)));
        app.adapter.setByteCounts(List.of(new LoggerByteCount("com.myapp.Worker", 5_000L, 0L)));
        aggregate.register(system.control);
        aggregate.register(app.control);

        List<LoggerByteCount> rows = aggregate.topLoggers(0).loggers();

        assertEquals(2, rows.size());
        assertEquals("com.myapp.Worker", rows.get(0).loggerName(), "worst offender across every context comes first");
        assertEquals("myapp.war", rows.get(0).context());
        assertEquals("system", rows.get(1).context());
    }

    @Test
    void topLoggers_limitAppliesToTheMergedSet_notPerContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        system.adapter.setByteCounts(List.of(new LoggerByteCount("a", 300L, 0L), new LoggerByteCount("b", 100L, 0L)));
        app.adapter.setByteCounts(List.of(new LoggerByteCount("c", 200L, 0L)));
        aggregate.register(system.control);
        aggregate.register(app.control);

        TopReport report = aggregate.topLoggers(2);

        assertEquals(List.of("a", "c"), report.loggers().stream().map(LoggerByteCount::loggerName).toList());
        assertEquals(3, report.trackedCount(), "the true count across both contexts, unaffected by --limit");
    }

    @Test
    void topLoggers_measurementStartedAt_isTheEarliestAcrossContexts() throws InterruptedException {
        Ctx early = new Ctx("system");
        early.topService.startMeasuring();
        Thread.sleep(5);
        Ctx late = new Ctx("myapp.war");
        late.topService.startMeasuring();
        aggregate.register(early.control);
        aggregate.register(late.control);

        assertEquals(early.topService.topLoggers(0).measurementStartedAt(),
                aggregate.topLoggers(0).measurementStartedAt());
    }

    @Test
    void setLevel_withNoContextRegistered_throws() {
        assertThrows(IllegalStateException.class,
                () -> aggregate.setLevel("com.x.Y", Level.DEBUG, SetLevelOptions.defaults()));
    }

    @Test
    void setLevel_deniedInOneContext_mutatesNoContext() {
        // Raise-vs-lower is judged per context: "com.shared.Util" -> DEBUG is
        // a RAISE in `system` (currently INFO) but a no-op/LOWER in `app`
        // (already DEBUG). Policy denies RAISE, grants LOWER -- so `app`'s
        // pre-check passes and `system`'s fails. The broadcast must mutate
        // neither.
        CapabilityPolicy noRaise = capability -> capability != Capability.LEVEL_RAISE;
        Ctx system = new Ctx("system", noRaise);
        Ctx app = new Ctx("myapp.war", noRaise);
        app.adapter.setConfiguredLevel("com.shared.Util", Level.DEBUG);
        aggregate.register(system.control);
        aggregate.register(app.control);

        assertThrows(CapabilityDeniedException.class,
                () -> aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.defaults()));

        assertTrue(system.service.activeOverrides().isEmpty());
        assertTrue(app.service.activeOverrides().isEmpty(),
                "the context whose pre-check passed must not have been mutated");
    }

    @Test
    void setLevel_exactName_reportsOneOverride_notOneParContext() {
        // Code-review finding: an exact-name target broadcast across every
        // registered context used to report every context's own override in
        // the result, so a 2-node WildFly deployment's setLevel appeared to
        // create two overrides for one logger. Every other broadcast
        // operation here reports one representative (preferring SYSTEM).
        Ctx system = new Ctx("system", CapabilityPolicy.allowAll(), "system-jmx");
        Ctx app = new Ctx("myapp.war", CapabilityPolicy.allowAll(), "app-jmx");
        aggregate.register(system.control);
        aggregate.register(app.control);

        var result = aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.defaults());

        assertEquals(1, result.overrides().size(),
                "one representative override, not one per context");
        assertEquals("system-jmx", result.overrides().get(0).source(),
                "the SYSTEM context's override is preferred as the representative");
    }

    @Test
    void setLevel_pattern_unconfirmed_mergesMatchesFromEveryContext() {
        // Code-review finding: an unconfirmed pattern used to throw from the
        // first context whose pre-check ran, so ConfirmationRequiredException
        // carried only that one context's matches -- a two-node deployment's
        // preview silently omitted every match that only existed on the
        // second node.
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        system.adapter.addKnownLogger("system.Shared");
        app.adapter.addKnownLogger("app.Shared");
        aggregate.register(system.control);
        aggregate.register(app.control);

        ConfirmationRequiredException ex = assertThrows(ConfirmationRequiredException.class,
                () -> aggregate.setLevel("*.Shared", Level.DEBUG, SetLevelOptions.defaults()));

        assertTrue(ex.matches().contains("system.Shared"));
        assertTrue(ex.matches().contains("app.Shared"),
                "matches from every context are merged into the one exception, not just the first context checked");
        assertTrue(system.service.activeOverrides().isEmpty(), "unconfirmed preview must not mutate anything");
        assertTrue(app.service.activeOverrides().isEmpty(), "unconfirmed preview must not mutate anything");
    }

    // --- lifecycle: addContext / removeContext ------------------------------------------------

    @Test
    void addContext_reBroadcastsActiveOverridesOntoTheNewcomer() {
        Ctx system = new Ctx("system");
        aggregate.register(system.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.sticky());

        Ctx redeployed = new Ctx("myapp.war");
        aggregate.addContext(redeployed.control);

        assertEquals(Level.DEBUG, redeployed.adapter.effectiveLevel("com.shared.Util"),
                "a level set before this context existed is present immediately after it registers");
        assertTrue(redeployed.service.activeOverrides().stream()
                .anyMatch(o -> o.loggerName().equals("com.shared.Util")));
    }

    @Test
    void addContext_doesNotReBroadcastAnAlreadyExpiredForOverride() throws InterruptedException {
        Ctx system = new Ctx("system");
        aggregate.register(system.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMillis(1)));
        Thread.sleep(10); // the FOR override elapses, but no sweep has run to revert it

        Ctx redeployed = new Ctx("myapp.war");
        aggregate.addContext(redeployed.control);

        assertEquals(Level.INFO, redeployed.adapter.effectiveLevel("com.shared.Util"),
                "an already-elapsed --for override is not re-applied to a fresh context");
        assertTrue(redeployed.service.activeOverrides().isEmpty());
    }

    @Test
    void removeContext_dropsItButLeavesOverridesInTheStore() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.sticky());
        assertFalse(sharedStore.loadAll().isEmpty());

        aggregate.removeContext("myapp.war");

        assertEquals(1, aggregate.contextCount());
        assertFalse(aggregate.hasContext("myapp.war"));
        assertFalse(sharedStore.loadAll().isEmpty(), "an undeploy is not a reset");
    }

    // --- expiry sweep fan-out ---------------------------------------------------------------------

    @Test
    void verificationSweep_fansOutToEveryContext_andReturnsTheReAppliedCount() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.sticky());

        // drift in both contexts
        system.adapter.applyLevel("com.shared.Util", Level.INFO);
        app.adapter.applyLevel("com.shared.Util", Level.INFO);

        int reapplied = aggregate.verificationSweep(Instant.now());

        assertEquals(2, reapplied);
        assertEquals(Level.DEBUG, system.adapter.effectiveLevel("com.shared.Util"));
        assertEquals(Level.DEBUG, app.adapter.effectiveLevel("com.shared.Util"));
    }

    @Test
    void verificationSweep_coversHandlerOverridesToo() {
        Ctx system = new Ctx("system");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.sticky());

        system.adapter.setHandlerLevel(console, Level.INFO); // drifted

        int reapplied = aggregate.verificationSweep(Instant.now());

        assertEquals(1, reapplied);
        assertEquals(Level.TRACE, system.adapter.handlerLevel(console).orElseThrow());
    }

    @Test
    void sweepExpiredOverrides_fansOutToEveryContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMillis(1)));

        aggregate.sweepExpiredOverrides(Instant.now().plusSeconds(60));

        assertEquals(Level.INFO, system.adapter.effectiveLevel("com.shared.Util"));
        assertEquals(Level.INFO, app.adapter.effectiveLevel("com.shared.Util"));
    }

    // --- setHandlerLevel / resetHandler broadcast (doc/specs/handler-floor-control.md) -----------

    @Test
    void setHandlerLevel_broadcastsToEveryContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(app.control);

        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertEquals(Level.TRACE, system.adapter.handlerLevel(console).orElseThrow());
        assertEquals(Level.TRACE, app.adapter.handlerLevel(console).orElseThrow());
    }

    @Test
    void squelchedByRaise_prefersTheSystemContextsAnswerOverAnotherContexts() {
        // doc/specs/handler-floor-control.md "Squelch warning" (issue #16) --
        // same representative-answer preference setHandlerLevel/setHandlerAuto
        // already use, since a handler's pre-raise level can genuinely differ
        // per context.
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.TRACE);
        app.adapter.addHandler(console, Level.TRACE);
        system.service.setLevel("com.acme.SystemWorker", Level.DEBUG, SetLevelOptions.defaults());
        app.service.setLevel("com.acme.AppWorker", Level.DEBUG, SetLevelOptions.defaults());
        aggregate.register(app.control);
        aggregate.register(system.control);

        List<org.logaperture.api.SquelchedLogger> squelched = aggregate.squelchedByRaise(console, Level.INFO);

        assertEquals(1, squelched.size());
        assertEquals("com.acme.SystemWorker", squelched.get(0).loggerName(),
                "the system context's own answer, not myapp.war's");
    }

    @Test
    void squelchedByRaise_readOnly_neverMutatesTheHandler() {
        Ctx system = new Ctx("system");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.TRACE);
        system.service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());
        aggregate.register(system.control);

        aggregate.squelchedByRaise(console, Level.INFO);

        assertEquals(Level.TRACE, system.adapter.handlerLevel(console).orElseThrow());
    }

    @Test
    void resetHandler_revertsInEveryContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());

        aggregate.resetHandler(console);

        assertEquals(Level.INFO, system.adapter.handlerLevel(console).orElseThrow());
        assertEquals(Level.INFO, app.adapter.handlerLevel(console).orElseThrow());
    }

    // --- setHandlerAuto (doc/specs/handler-floor-control.md "AUTO handler level", issue #20) -------

    @Test
    void setHandlerAuto_broadcastsToEveryContext() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(app.control);

        HandlerLevelOverride result = aggregate.setHandlerAuto(console, SetHandlerLevelOptions.defaults())
                .orElseThrow();

        assertEquals(org.logaperture.api.HandlerLevelMode.AUTO, result.mode());
        assertTrue(system.handlerService.listHandlerOverrides().stream()
                .anyMatch(o -> o.handlerRef().equals(console) && o.mode() == org.logaperture.api.HandlerLevelMode.AUTO));
        assertTrue(app.handlerService.listHandlerOverrides().stream()
                .anyMatch(o -> o.handlerRef().equals(console) && o.mode() == org.logaperture.api.HandlerLevelMode.AUTO));
    }

    @Test
    void setHandlerAuto_capabilityWithheld_deniesInEveryContextBeforeMutatingAny() {
        Ctx system = new Ctx("system", CapabilityPolicy.denyAll());
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);

        assertThrows(CapabilityDeniedException.class,
                () -> aggregate.setHandlerAuto(console, SetHandlerLevelOptions.defaults()));
        assertEquals(Level.INFO, system.adapter.handlerLevel(console).orElseThrow());
    }

    @Test
    void listHandlerOverrides_unionsAcrossContexts_dedupingASharedRef() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        HandlerRef appOnly = new HandlerRef("APP-FILE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(appOnly, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(app.control);
        assertTrue(aggregate.listHandlerOverrides().isEmpty());

        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());
        aggregate.setHandlerLevel(appOnly, Level.DEBUG, SetHandlerLevelOptions.defaults());

        List<HandlerLevelOverride> overrides = aggregate.listHandlerOverrides();
        assertEquals(2, overrides.size(), "CONSOLE is active in both contexts but counted once");
        assertTrue(overrides.stream().anyMatch(o -> o.handlerRef().equals(console) && o.level() == Level.TRACE));
        assertTrue(overrides.stream().anyMatch(o -> o.handlerRef().equals(appOnly) && o.level() == Level.DEBUG));
    }

    @Test
    void setHandlerLevel_oneContextThrows_theOtherStillSucceeds() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        app.adapter.throwOnSetHandlerLevel(console);
        aggregate.register(system.control);
        aggregate.register(app.control);

        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertEquals(Level.TRACE, system.adapter.handlerLevel(console).orElseThrow(), "unaffected context still applied");
        assertEquals(Level.INFO, app.adapter.handlerLevel(console).orElseThrow(), "the failing context is left alone");
    }

    @Test
    void addContext_reBroadcastsActiveHandlerOverridesOntoTheNewcomer() {
        Ctx system = new Ctx("system");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.sticky());

        Ctx redeployed = new Ctx("myapp.war");
        redeployed.adapter.addHandler(console, Level.INFO);
        aggregate.addContext(redeployed.control);

        assertEquals(Level.TRACE, redeployed.adapter.handlerLevel(console).orElseThrow(),
                "a handler level set before this context existed is present immediately after it registers");
    }

    @Test
    void sweepExpiredOverrides_fansOutToEveryContextForHandlersToo() {
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.forDuration(Duration.ofMillis(1)));

        aggregate.sweepExpiredOverrides(Instant.now().plusSeconds(60));

        assertEquals(Level.INFO, system.adapter.handlerLevel(console).orElseThrow());
        assertEquals(Level.INFO, app.adapter.handlerLevel(console).orElseThrow());
    }

    // --- code-review findings: a context that simply lacks the target handler --------------------

    @Test
    void setHandlerLevel_oneContextLacksTheHandlerEntirely_doesNotWronglyDenyTheOthers() {
        // A code-review finding: the capability precheck used to default a
        // context lacking the handler to requiring HANDLER_RAISE regardless
        // of the actual requested direction, so a lower could be wrongly
        // denied even though every context that does have the handler only
        // ever needed HANDLER_LOWER.
        Ctx system = new Ctx("system", c -> c == Capability.HANDLER_LOWER);
        Ctx perApp = new Ctx("myapp.war", c -> c == Capability.HANDLER_LOWER); // never gets CONSOLE added
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(perApp.control);

        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());

        assertEquals(Level.TRACE, system.adapter.handlerLevel(console).orElseThrow());
    }

    @Test
    void addContext_newContextLacksTheHandler_doesNotAbortRebroadcast() {
        // A code-review finding: adoptOverride's handler-rebroadcast used to
        // propagate UnknownHandlerException uncaught, which would have
        // aborted addContext entirely for a context that simply doesn't have
        // the overridden handler at all.
        Ctx system = new Ctx("system");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.setLevel("com.shared.Util", Level.DEBUG, SetLevelOptions.sticky());
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.sticky());

        Ctx redeployed = new Ctx("myapp.war"); // never gets CONSOLE added
        aggregate.addContext(redeployed.control); // must not throw

        assertEquals(Level.DEBUG, redeployed.adapter.effectiveLevel("com.shared.Util"),
                "the logger override still rebroadcasts even though the handler one can't apply here");
    }

    @Test
    void resetHandler_oneContextsHandlerHasVanished_stillRevertsTheOthers() {
        // A code-review finding: resetHandler had no per-context guard,
        // unlike setHandlerLevel right above it in the same class -- a
        // vanished handler in one context used to abort every context
        // ordered after it.
        Ctx system = new Ctx("system");
        Ctx app = new Ctx("myapp.war");
        HandlerRef console = new HandlerRef("CONSOLE");
        system.adapter.addHandler(console, Level.INFO);
        app.adapter.addHandler(console, Level.INFO);
        aggregate.register(system.control);
        aggregate.register(app.control);
        aggregate.setHandlerLevel(console, Level.TRACE, SetHandlerLevelOptions.defaults());
        system.adapter.vanishHandler(console);

        aggregate.resetHandler(console); // must not throw

        assertEquals(Level.INFO, app.adapter.handlerLevel(console).orElseThrow(),
                "the other context is still reverted despite the first one's handler having vanished");
    }
}
