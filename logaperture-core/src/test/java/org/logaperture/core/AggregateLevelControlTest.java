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
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
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
        final ContextControl control;

        Ctx(String key) {
            service = new LevelControlService(adapter, new BaselineRegistry(), new OverrideRegistry(),
                    CapabilityPolicy.allowAll(), auditLog, sharedStore, "alice", "jmx");
            control = new ContextControl(ContextHandle.of(key, key, adapter), service);
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
    void setLevel_withNoContextRegistered_throws() {
        assertThrows(IllegalStateException.class,
                () -> aggregate.setLevel("com.x.Y", Level.DEBUG, SetLevelOptions.defaults()));
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
}
