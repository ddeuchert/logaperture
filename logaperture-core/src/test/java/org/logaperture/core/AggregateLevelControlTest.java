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
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl.ContextControl;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.StateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        final ContextControl control;

        Ctx(String key) {
            this(key, CapabilityPolicy.allowAll());
        }

        Ctx(String key, CapabilityPolicy policy) {
            service = new LevelControlService(adapter, new BaselineRegistry(), new OverrideRegistry(),
                    policy, auditLog, sharedStore, "alice", "jmx");
            handlerService = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(),
                    new HandlerOverrideRegistry(), policy, auditLog, sharedStore, "alice", "jmx");
            control = new ContextControl(ContextHandle.of(key, key, adapter), service, handlerService);
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
