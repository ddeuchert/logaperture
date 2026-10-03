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
import org.logaperture.api.DescendantLevel;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code set logger --force} -- doc/specs/set-logger-force.md. The fixture: {@code com.acme} inherits INFO
 * from ROOT; {@code com.acme.other} has its own INFO and {@code com.acme.db.Pool} its own WARN (as a server's
 * logging config would give them); {@code com.acme.plain} has no level of its own; {@code com.acme.same} has
 * its own TRACE, equal to the level the tests request.
 */
class SetLoggerForceTest {

    private static final String PARENT = "com.acme";
    private static final String OTHER = "com.acme.other";
    private static final String POOL = "com.acme.db.Pool";
    private static final String PLAIN = "com.acme.plain";
    private static final String SAME = "com.acme.same";

    private FakeLoggingAdapter adapter;
    private OverrideRegistry overrides;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private LevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addKnownLogger(PARENT);
        adapter.setConfiguredLevel(OTHER, Level.INFO);
        adapter.setConfiguredLevel(POOL, Level.WARN);
        adapter.addKnownLogger(PLAIN);
        adapter.setConfiguredLevel(SAME, Level.TRACE);
        adapter.setConfiguredLevel("com.acmeish", Level.ERROR); // a name prefix, not a descendant
        overrides = new OverrideRegistry();
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = newService(CapabilityPolicy.allowAll());
    }

    private LevelControlService newService(CapabilityPolicy policy) {
        return new LevelControlService(adapter, new BaselineRegistry(), overrides, policy, auditLog, stateStore,
                "alice", "jmx");
    }

    private SetLevelResult force(Level level, SetLevelOptions options) {
        return service.setLogger(PARENT, level, options.withForce(true));
    }

    private static List<String> names(List<DescendantLevel> descendants) {
        return descendants.stream().map(DescendantLevel::loggerName).toList();
    }

    @Test
    void plainSet_reportsTheDescendantsThatKeepTheirOwnLevel_andChangesNothingUnderIt() {
        SetLevelResult result = service.setLogger(PARENT, Level.TRACE, SetLevelOptions.defaults());

        assertEquals(1, result.overrides().size());
        assertEquals(List.of(POOL, OTHER), names(result.descendants()), "sorted by name; not plain, same or acmeish");
        assertTrue(result.descendants().stream().allMatch(d -> d.kind() == DescendantLevel.Kind.OWN_LEVEL));
        assertEquals(Level.INFO, adapter.effectiveLevel(OTHER));
        assertEquals(Level.TRACE, adapter.effectiveLevel(PLAIN), "inherits, as it always did");
    }

    @Test
    void force_setsEachDescendantWithItsOwnDifferingLevel_taggedWithTheParent() {
        SetLevelResult result = force(Level.TRACE, SetLevelOptions.forDuration(Duration.ofMinutes(30)));

        assertEquals(List.of(PARENT, POOL, OTHER), result.overrides().stream().map(LevelOverride::loggerName).toList());
        LevelOverride forced = overrides.get(OTHER).orElseThrow();
        assertEquals(PARENT, forced.forcedBy());
        assertEquals(overrides.get(PARENT).orElseThrow().tier(), forced.tier(), "the parent's tier");
        assertNull(overrides.get(PARENT).orElseThrow().forcedBy());
        assertEquals(Level.TRACE, adapter.effectiveLevel(OTHER));
        assertEquals(Level.TRACE, adapter.effectiveLevel(POOL));
        assertTrue(overrides.get(PLAIN).isEmpty(), "no level of its own -- already follows");
        assertTrue(overrides.get(SAME).isEmpty(), "already at TRACE -- nothing to change");
        assertTrue(overrides.get("com.acmeish").isEmpty(), "a prefix, not a descendant");
        assertTrue(result.descendants().isEmpty());
        assertTrue(auditLog.records().stream().anyMatch(r -> r.loggerName().equals(OTHER)
                && "forced by com.acme".equals(r.origin())), auditLog.records().toString());
    }

    @Test
    void force_keepsAndReportsADescendantTheOperatorSet() {
        service.setLogger(OTHER, Level.DEBUG, SetLevelOptions.defaults());

        SetLevelResult result = force(Level.TRACE, SetLevelOptions.defaults());

        assertEquals(Level.DEBUG, adapter.effectiveLevel(OTHER), "most specific wins (F3)");
        assertNull(overrides.get(OTHER).orElseThrow().forcedBy());
        assertEquals(List.of(OTHER), names(result.descendants()));
        assertEquals(DescendantLevel.Kind.OPERATOR_OVERRIDE, result.descendants().get(0).kind());
    }

    @Test
    void resetParent_putsEveryForcedDescendantBackWhereItWasFound() {
        force(Level.TRACE, SetLevelOptions.defaults());

        ResetOutcome outcome = service.resetLogger(PARENT, false);

        assertEquals(List.of(PARENT, POOL, OTHER), outcome.revertedLoggerNames());
        assertEquals(Level.INFO, adapter.effectiveLevel(OTHER));
        assertEquals(Level.WARN, adapter.effectiveLevel(POOL));
        assertTrue(overrides.all().isEmpty());
    }

    @Test
    void resetParent_leavesADescendantChangedSince() {
        force(Level.TRACE, SetLevelOptions.defaults());
        service.setLogger(OTHER, Level.DEBUG, SetLevelOptions.defaults()); // replaces the tagged override

        service.resetLogger(PARENT, false);

        assertEquals(Level.DEBUG, adapter.effectiveLevel(OTHER), "a change made since is never undone (F8)");
        assertEquals(Level.WARN, adapter.effectiveLevel(POOL));
    }

    @Test
    void resetOneForcedDescendant_resetsJustThatOne() {
        force(Level.TRACE, SetLevelOptions.defaults());

        service.resetLogger(OTHER, false);

        assertEquals(Level.INFO, adapter.effectiveLevel(OTHER));
        assertEquals(Level.TRACE, adapter.effectiveLevel(POOL));
        assertTrue(overrides.get(PARENT).isPresent());
    }

    @Test
    void aForcedForOverride_expiresTogetherWithTheParent() {
        force(Level.TRACE, SetLevelOptions.forDuration(Duration.ofMillis(1)));

        service.sweepExpiredOverrides(Instant.now().plusSeconds(1));

        assertTrue(overrides.all().isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel(OTHER));
    }

    @Test
    void reForcing_movesTheTaggedOnes_whileAPlainReSetLeavesThemTiedAndSaysSo() {
        force(Level.TRACE, SetLevelOptions.defaults());

        SetLevelResult plain = service.setLogger(PARENT, Level.DEBUG, SetLevelOptions.defaults());
        assertEquals(Level.TRACE, adapter.effectiveLevel(OTHER), "a plain re-set moves nothing it forced (F5)");
        assertTrue(plain.descendants().stream().anyMatch(d -> d.loggerName().equals(OTHER)
                && d.kind() == DescendantLevel.Kind.FORCED_EARLIER));

        force(Level.DEBUG, SetLevelOptions.defaults());
        assertEquals(Level.DEBUG, adapter.effectiveLevel(OTHER));
        assertEquals(PARENT, overrides.get(OTHER).orElseThrow().forcedBy());

        service.resetLogger(PARENT, false);
        assertEquals(Level.INFO, adapter.effectiveLevel(OTHER), "still tied, so still reset with the parent");
    }

    @Test
    void force_isAllOrNothingOnCapability() {
        // DEBUG raises com.acme (from INFO) but lowers com.acme.same (its own TRACE): needs both.
        LevelControlService raiseOnly = newService(capability -> capability == Capability.LEVEL_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> raiseOnly.setLogger(PARENT, Level.DEBUG, SetLevelOptions.defaults().withForce(true)));
        assertTrue(overrides.all().isEmpty(), "nothing changed");
        assertThrows(CapabilityDeniedException.class,
                () -> raiseOnly.checkSetLevelPermitted(PARENT, Level.DEBUG, SetLevelOptions.defaults().withForce(true)));
        raiseOnly.checkSetLevelPermitted(PARENT, Level.DEBUG, SetLevelOptions.defaults()); // without force: fine
    }

    @Test
    void aStickyForce_isRefusedWithoutIncludeSticky_andResetWhole() {
        force(Level.TRACE, SetLevelOptions.sticky());

        assertThrows(IllegalArgumentException.class, () -> service.resetLogger(PARENT, false));
        ResetOutcome outcome = service.resetLogger(PARENT, true);

        assertEquals(3, outcome.revertedLoggerNames().size());
        assertTrue(stateStore.loadAll().isEmpty(), "removed from the state file too");
    }

    @Test
    void aPatternReset_takesTheForcedDescendantsWithIt() {
        force(Level.TRACE, SetLevelOptions.defaults());

        service.resetLogger("*.acme", false);

        assertTrue(overrides.all().isEmpty());
        assertEquals(Level.WARN, adapter.effectiveLevel(POOL));
    }

    @Test
    void forcedBy_survivesTheStateFile_andAnOlderFileReadsUntagged() {
        force(Level.TRACE, SetLevelOptions.sticky());

        String written = StateFileFormat.write(stateStore.loadAll(), List.of(), List.of(), List.of());
        List<LevelOverride> read = StateFileFormat.parse(written).overrides();

        assertTrue(written.contains("forcedBy: \"com.acme\""), written);
        assertEquals(PARENT, read.stream().filter(o -> o.loggerName().equals(OTHER)).findFirst().orElseThrow()
                .forcedBy());
        String older = written.replace("schemaVersion: 11", "schemaVersion: 10")
                .replaceAll("(?m)^    forcedBy: .*\\n", "");
        assertTrue(StateFileFormat.parse(older).overrides().stream().allMatch(o -> o.forcedBy() == null));
    }

    @Test
    void everyLoggerIsUnderRoot() {
        assertTrue(LevelControlService.isDescendant("ROOT", "com.acme"));
        assertFalse(LevelControlService.isDescendant("ROOT", "ROOT"));
        assertFalse(LevelControlService.isDescendant("com.acme", "com.acmeish"));
        assertTrue(LevelControlService.isDescendant("com.acme", "com.acme.db.Pool"));
    }

    @Test
    void listLoggers_showsWhoForcedAnOverride() {
        force(Level.TRACE, SetLevelOptions.defaults());

        assertEquals(PARENT, service.listLoggers(OTHER).stream().filter(row -> row.name().equals(OTHER))
                .findFirst().orElseThrow().overrideForcedBy());
    }
}
