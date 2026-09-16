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
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelControlServiceTest {

    private FakeLoggingAdapter adapter;
    private BaselineRegistry baselines;
    private OverrideRegistry overrides;
    private InMemoryAuditLog auditLog;
    private StateStore stateStore;
    private LevelControlService service;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        baselines = new BaselineRegistry();
        overrides = new OverrideRegistry();
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        service = newService(CapabilityPolicy.allowAll());
    }

    private LevelControlService newService(CapabilityPolicy policy) {
        return new LevelControlService(adapter, baselines, overrides, policy, auditLog, stateStore, "alice", "jmx");
    }

    // --- setLevel / resetLevel / resetAll round-trip -----------------------------------------

    @Test
    void setLevel_onExistingLogger_appliesAndRecordsOverride() {
        adapter.addKnownLogger("com.acme.Worker");

        LevelOverride override = service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.withReason("INC-1")).overrides().get(0);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
        assertEquals("com.acme.Worker", override.loggerName());
        assertEquals(Level.DEBUG, override.level());
        assertEquals("INC-1", override.reason());
        assertTrue(overrides.get("com.acme.Worker").isPresent());
    }

    // --- blockingHandlers (doc/specs/handler-floor-control.md "Warning on level commands") ----

    @Test
    void setLevel_raiseBelowAHandlerFloor_reportsIt() {
        adapter.addKnownLogger("com.acme.Worker");
        HandlerRef console = new HandlerRef("CONSOLE");
        adapter.addHandler(console, Level.INFO, "com.acme.Worker");

        SetLevelResult result = service.setLevel("com.acme.Worker", Level.TRACE, SetLevelOptions.defaults());

        assertEquals(1, result.blockingHandlers().size());
        assertEquals(console, result.blockingHandlers().get(0).handlerRef());
        assertEquals(Level.INFO, result.blockingHandlers().get(0).currentLevel());
    }

    @Test
    void setLevel_pattern_reportsBlockingHandlersOnAMatchsOwnPathToo() {
        // A code-review finding (originally against --include-children, now
        // the pattern-target fan-out that replaced it): blockingHandlers
        // used to be computed once for the named logger alone, so a match
        // with its own, different handler on its own path lost its warning
        // entirely.
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Worker");
        HandlerRef fileOnOtherOnly = new HandlerRef("FILE");
        adapter.addHandler(fileOnOtherOnly, Level.INFO, "com.other.Worker"); // not on "com.acme.Worker"'s own path

        SetLevelResult result = service.setLevel(
                "*.Worker", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

        assertEquals(1, result.blockingHandlers().size());
        assertEquals(fileOnOtherOnly, result.blockingHandlers().get(0).handlerRef());
    }

    @Test
    void setLevel_pattern_dedupesAHandlerSharedAcrossMatches() {
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Worker");
        HandlerRef console = new HandlerRef("CONSOLE");
        adapter.addHandler(console, Level.INFO, "com.acme.Worker", "com.other.Worker"); // on both paths

        SetLevelResult result = service.setLevel(
                "*.Worker", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

        assertEquals(1, result.blockingHandlers().size(), "reported once, not once per match");
    }

    @Test
    void setLevel_pattern_keepsTheStricterFloorWhenTwoMatchesShareARef() {
        // Code-review finding: on WildFly, handlerFloorsBelow collapses to
        // one shared ALL_HANDLERS ref (Decision #7) -- two different
        // matches under one pattern can each report a *different*
        // currentLevel for that same ref, and the old putIfAbsent-based
        // dedup silently dropped whichever wasn't seen first, understating
        // the real block.
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Worker");
        HandlerRef allHandlers = HandlerRef.ALL_HANDLERS;
        adapter.addHandlerWithPerTargetLevel(allHandlers, Level.INFO, "com.acme.Worker");
        adapter.addHandlerWithPerTargetLevel(allHandlers, Level.WARN, "com.other.Worker"); // stricter

        SetLevelResult result = service.setLevel(
                "*.Worker", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

        assertEquals(1, result.blockingHandlers().size());
        assertEquals(Level.WARN, result.blockingHandlers().get(0).currentLevel(),
                "the stricter of the two matches' readings, not whichever was seen first");
    }

    @Test
    void setLevel_raiseNotBelowAHandlerFloor_reportsNothing() {
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addHandler(new HandlerRef("CONSOLE"), Level.TRACE, "com.acme.Worker");

        SetLevelResult result = service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        assertTrue(result.blockingHandlers().isEmpty());
    }

    @Test
    void setLevel_notARaise_reportsNothingEvenBelowAHandlerFloor() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.DEBUG);
        adapter.addHandler(new HandlerRef("CONSOLE"), Level.INFO, "com.acme.Worker");

        SetLevelResult result = service.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.defaults());

        assertTrue(result.blockingHandlers().isEmpty(), "WARN is less verbose than DEBUG -- not a raise");
    }

    @Test
    void setLevel_onNotYetExistingLogger_stillWorks() {
        // "com.acme.Later" was never registered with the adapter beforehand.
        LevelOverride override = service.setLevel("com.acme.Later", Level.TRACE, SetLevelOptions.defaults()).overrides().get(0);

        assertEquals(Level.TRACE, adapter.effectiveLevel("com.acme.Later"));
        assertEquals(Level.TRACE, override.level());
    }

    @Test
    void setLevel_calledTwice_replacesRatherThanDuplicates() {
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());
        service.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.defaults());

        assertEquals(Level.WARN, overrides.get("com.acme.Worker").orElseThrow().level());
        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void resetLevel_noOverride_isNoOpNotError() {
        service.resetLevel("com.acme.NeverTouched"); // must not throw
    }

    @Test
    void resetLevel_revertsToBaselineAndRemovesOverride() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.WARN);
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.resetLevel("com.acme.Worker");

        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Worker"));
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
    }

    @Test
    void resetLevel_baselineWasInherited_clearsToInherited() {
        // No explicit configured level for this logger -- baseline is "inherited".
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.resetLevel("com.acme.Worker");

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker")); // falls back to ROOT
    }

    @Test
    void resetAll_revertsEveryActiveOverride() {
        service.setLevel("com.acme.A", Level.DEBUG, SetLevelOptions.defaults());
        service.setLevel("com.acme.B", Level.TRACE, SetLevelOptions.defaults());

        service.resetAll();

        assertTrue(overrides.all().isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.A"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.B"));
    }

    // --- pattern targets: one-time selection (doc/specs/pattern-selection-semantics.md) ------

    @Test
    void setLevel_leadingStarPattern_appliesToEveryCurrentMatch() {
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Worker");
        adapter.addKnownLogger("com.other.Thing");

        service.setLevel("*.Worker", Level.DEBUG, SetLevelOptions.withReason("reason").withConfirmed(true));

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.other.Worker"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.other.Thing")); // untouched

        assertTrue(overrides.get("com.acme.Worker").isPresent());
        assertTrue(overrides.get("com.other.Worker").isPresent());
        assertTrue(overrides.get("com.other.Thing").isEmpty());
    }

    @Test
    void setLevel_exactName_doesNotTouchDescendants() {
        adapter.addKnownLogger("com.acme.http");

        service.setLevel("com.acme", Level.DEBUG, SetLevelOptions.defaults());

        assertTrue(overrides.get("com.acme.http").isEmpty());
    }

    @Test
    void setLevel_trailingWildcard_isRejectedBeforeMatchingCapabilityOrMutation() {
        adapter.addKnownLogger("org.apache.tomcat");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setLevel("org.apache.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)));

        assertTrue(e.getMessage().contains("org.apache.*"), e.getMessage());
        assertTrue(e.getMessage().contains("org.apache"), e.getMessage());
        assertTrue(overrides.all().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_leadingAndTrailingWildcard_isRejectedTheSameWay() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setLevel("*.apache.tomcat.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)));

        assertTrue(overrides.all().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void resetLevel_trailingWildcard_isUnaffectedByTheSetLevelRejection() {
        // Same string setLevel rejects -- reset keeps full selection
        // semantics for both wildcard shapes (Decision #5).
        adapter.addKnownLogger("org.apache.tomcat");
        service.setLevel("org.apache.tomcat", Level.DEBUG, SetLevelOptions.defaults());

        ResetOutcome outcome = service.resetLevel("org.apache.*");

        assertEquals(List.of("org.apache.tomcat"), outcome.revertedLoggerNames());
    }

    @Test
    void listLoggers_trailingWildcard_isUnaffectedByTheSetLevelRejection() {
        adapter.addKnownLogger("org.apache.tomcat");

        List<LoggerInfo> matched = service.listLoggers("org.apache.*");

        assertEquals(1, matched.size());
        assertEquals("org.apache.tomcat", matched.get(0).name());
    }

    @Test
    void setLevel_leadingStarPattern_doesNotCoverALoggerAddedAfterward() {
        // The direct negative of the old standing-rule assertion: nothing is
        // left running that would ever reach for a logger created later.
        adapter.addKnownLogger("com.acme.Worker");
        service.setLevel("*.Worker", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));

        adapter.addKnownLogger("com.other.Worker");

        assertTrue(overrides.get("com.other.Worker").isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel("com.other.Worker"));
    }

    @Test
    void setLevel_pattern_overwritesAPreExistingOverrideOfAnyTierUnconditionally() {
        // Precedence retired (doc/specs/pattern-selection-semantics.md
        // "Precedence, retired") -- a pattern-based set no longer special-
        // cases a match that already carries its own override.
        adapter.addKnownLogger("com.acme.Worker");
        service.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.sticky());

        service.setLevel("*.Worker", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

        assertEquals(Level.TRACE, overrides.get("com.acme.Worker").orElseThrow().level());
        assertEquals(Level.TRACE, adapter.effectiveLevel("com.acme.Worker"));
    }

    // --- resetLevel on a pattern: resolves current matches, no rule lookup -------------------

    @Test
    void resetLevel_pattern_revertsEveryCurrentlyOverriddenMatch() {
        adapter.addKnownLogger("com.acme.http");
        adapter.addKnownLogger("com.acme.db");
        service.setLevel("*.http", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));
        service.setLevel("*.db", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));

        var outcome = service.resetLevel("com.acme.*");

        assertEquals(Set.of("com.acme.http", "com.acme.db"), Set.copyOf(outcome.revertedLoggerNames()));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.http"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.db"));
    }

    @Test
    void resetLevel_pattern_neverATrackedRuleButMatchesAnOverriddenLogger_revertsIt() {
        // The direct positive of today's shipped no-op: the logger was set
        // by exact name (never a pattern), yet a pattern that happens to
        // match it still finds and reverts it (doc/specs/
        // pattern-selection-semantics.md "Operations").
        adapter.addKnownLogger("org.apache.tomcat");
        service.setLevel("org.apache.tomcat", Level.DEBUG, SetLevelOptions.defaults());

        var outcome = service.resetLevel("org.apache.*");

        assertEquals(List.of("org.apache.tomcat"), outcome.revertedLoggerNames());
        assertEquals(Level.INFO, adapter.effectiveLevel("org.apache.tomcat"));
    }

    @Test
    void resetLevel_pattern_noCurrentMatchWithAnOverride_isANoOpNotAnError() {
        var outcome = service.resetLevel("com.never.seen.*");

        assertEquals(ResetOutcome.nothingReset(), outcome);
    }

    // --- capability checks ---------------------------------------------------------------------

    @Test
    void listLoggers_deniesWhenViewNotGranted() {
        LevelControlService denied = newService(CapabilityPolicy.denyAll());

        assertThrows(CapabilityDeniedException.class, () -> denied.listLoggers(null));
    }

    @Test
    void setLevel_raisingDenied_throwsBeforeMutating() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.INFO);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults()));

        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Worker")); // unchanged
        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_loweringDenied_throwsBeforeMutating() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.DEBUG);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_LOWER);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.Worker", Level.WARN, SetLevelOptions.defaults()));

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Worker"));
    }

    @Test
    void setLevel_pattern_denialOnLaterMatchPreventsEarlierMatchFromBeingMutated() {
        // First (alphabetically) match already at DEBUG (its own baseline)
        // -- moving it to DEBUG again is "lower or equal", which the policy
        // below allows.
        adapter.setConfiguredLevel("com.acme.Legacy", Level.DEBUG);
        // The other match has its OWN explicit baseline at ERROR (not
        // inherited) -- moving it to DEBUG is a genuine raise, which the
        // policy below denies.
        adapter.setConfiguredLevel("com.other.Legacy", Level.ERROR);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("*.Legacy", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)));

        // The first match passed its own check but must NOT have been
        // mutated -- the whole batch is pre-checked before anything applies.
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Legacy")); // its baseline, unchanged
        assertEquals(Level.ERROR, adapter.effectiveLevel("com.other.Legacy")); // untouched
        assertTrue(overrides.all().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_pattern_zeroCurrentMatches_stillRequiresPersist() {
        // Code-review finding: the PERSIST check used to be nested inside a
        // per-target loop, so a pattern matching no currently-known logger
        // (an empty target list) skipped it entirely.
        LevelControlService denied = newService(capability -> capability != Capability.PERSIST);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("*.brandnew", Level.DEBUG,
                        SetLevelOptions.sticky().withConfirmed(true)));

        assertTrue(overrides.all().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    // --- audit -----------------------------------------------------------------------------------

    @Test
    void setLevel_writesMutationAuditRecordWithCorrectValues() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.INFO);

        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.withReason("INC-1"));

        assertEquals(1, auditLog.records().size());
        AuditRecord record = auditLog.records().get(0);
        assertEquals(AuditRecord.Action.MUTATION, record.action());
        assertEquals("alice", record.principal());
        assertEquals("jmx", record.source());
        assertEquals("com.acme.Worker", record.loggerName());
        assertEquals("INFO", record.previousValue());
        assertEquals("DEBUG", record.newValue());
        assertEquals("INC-1", record.reason());
    }

    @Test
    void resetLevel_writesReversionAuditRecord() {
        adapter.setConfiguredLevel("com.acme.Worker", Level.WARN);
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        service.resetLevel("com.acme.Worker");

        AuditRecord record = auditLog.records().get(auditLog.records().size() - 1);
        assertEquals(AuditRecord.Action.REVERSION, record.action());
        assertEquals("DEBUG", record.previousValue());
        assertEquals("WARN", record.newValue());
    }

    // --- chaos: adapter throws mid-operation -----------------------------------------------------

    @Test
    void setLevel_adapterThrows_leavesNoRegistryOrAuditTrace() {
        adapter.throwOnApply("com.acme.Worker");

        assertThrows(RuntimeException.class,
                () -> service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults()));

        assertTrue(overrides.get("com.acme.Worker").isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_pattern_adapterThrowsMidFanout_earlierMatchesStayCommitted() {
        adapter.addKnownLogger("a.Worker");
        adapter.addKnownLogger("b.Worker");
        adapter.addKnownLogger("c.Worker");
        // matchesFor iterates a sorted TreeSet, so the order is alphabetical.
        adapter.throwOnApply("b.Worker");

        assertThrows(RuntimeException.class,
                () -> service.setLevel("*.Worker", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)));

        // The first match, processed before the throwing one, is applied and recorded.
        assertEquals(Level.DEBUG, adapter.effectiveLevel("a.Worker"));
        assertTrue(overrides.get("a.Worker").isPresent());
        assertEquals(1, auditLog.records().size());

        // The throwing match was never committed; nothing after it was attempted either.
        assertTrue(overrides.get("b.Worker").isEmpty());
        assertTrue(overrides.get("c.Worker").isEmpty());
    }

    // --- listLoggers -------------------------------------------------------------------------------

    @Test
    void listLoggers_reflectsOverrideStateAndFilter() {
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Thing");
        service.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.withReason("why"));

        List<LoggerInfo> all = service.listLoggers("com.acme");

        assertEquals(1, all.size());
        LoggerInfo info = all.get(0);
        assertEquals("com.acme.Worker", info.name());
        assertTrue(info.overrideActive());
        assertEquals("jmx", info.overrideSource());
        assertEquals("why", info.overrideReason());
        assertEquals(Level.DEBUG, info.effectiveLevel());
    }

    @Test
    void listLoggers_noOverride_reportsInactiveOverrideFields() {
        adapter.addKnownLogger("com.acme.Quiet");

        Optional<LoggerInfo> info = service.listLoggers(null).stream()
                .filter(li -> li.name().equals("com.acme.Quiet"))
                .findFirst();

        assertTrue(info.isPresent());
        assertFalse(info.get().overrideActive());
    }

    @Test
    void listLoggers_honoursAGlobFilter() {
        // A caller who only saw "infinispan" in a log line finds the real
        // category with a leading-* glob, without knowing its prefix.
        adapter.addKnownLogger("org.jboss.as.clustering.infinispan");
        adapter.addKnownLogger("com.acme.Worker");
        adapter.addKnownLogger("com.other.Thing");

        List<LoggerInfo> matched = service.listLoggers("*.infinispan");

        assertEquals(1, matched.size());
        assertEquals("org.jboss.as.clustering.infinispan", matched.get(0).name());
    }

    @Test
    void listLoggers_rejectsAnInvalidPatternAtTheServiceSeam() {
        adapter.addKnownLogger("com.acme.Worker");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> service.listLoggers("org.*apache"));
        assertTrue(e.getMessage().contains("org.*apache"), e.getMessage());
    }

    @Test
    void listLoggers_rejectsAnInvalidPatternEvenWithNoKnownLoggers() {
        // Regression: validation must not be a side effect of iterating
        // candidate names -- an invalid filter has to be rejected even when
        // there happen to be zero names to check it against. FakeLoggingAdapter
        // always seeds a "ROOT" entry (like a real framework's root logger),
        // so this needs a bare-minimum adapter that genuinely reports none.
        LoggingAdapter emptyAdapter = new LoggingAdapter() {
            @Override
            public List<String> knownLoggerNames() {
                return List.of();
            }

            @Override
            public Optional<Level> configuredLevel(String loggerName) {
                return Optional.empty();
            }

            @Override
            public Level effectiveLevel(String loggerName) {
                return Level.INFO;
            }

            @Override
            public void applyLevel(String loggerName, Level level) {
                // unused by this test
            }
        };
        LevelControlService emptyService = new LevelControlService(
                emptyAdapter, new BaselineRegistry(), new OverrideRegistry(), CapabilityPolicy.allowAll(),
                auditLog, stateStore, "alice", "jmx");
        assertTrue(emptyAdapter.knownLoggerNames().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> emptyService.listLoggers("org.*apache"));
    }

    // --- multi-context: activeOverrides / adoptOverride -------------------------------------------

    @Test
    void activeOverrides_returnsEveryTrackedOverride() {
        adapter.addKnownLogger("com.acme.A");
        adapter.addKnownLogger("com.acme.B");
        service.setLevel("com.acme.A", Level.DEBUG, SetLevelOptions.sticky());
        service.setLevel("com.acme.B", Level.TRACE, SetLevelOptions.defaults());

        List<LevelOverride> active = service.activeOverrides();

        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(o -> o.loggerName().equals("com.acme.A") && o.level() == Level.DEBUG));
        assertTrue(active.stream().anyMatch(o -> o.loggerName().equals("com.acme.B") && o.level() == Level.TRACE));
    }

    @Test
    void adoptOverride_appliesToAdapterAndRegistry_andRecordsAResume() {
        LevelOverride fromAnotherContext = new LevelOverride(
                "com.acme.Shared", Level.DEBUG, "why", java.time.Instant.now(), "jmx",
                PersistenceTier.STICKY, null);

        service.adoptOverride(fromAnotherContext);

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Shared"));
        assertTrue(overrides.get("com.acme.Shared").isPresent());
        assertTrue(auditLog.records().stream()
                .anyMatch(r -> r.source().equals("resume") && r.loggerName().equals("com.acme.Shared")
                        && r.action() == AuditRecord.Action.MUTATION));
    }

    // --- verification sweep -----------------------------------------------------------------------

    @Test
    void verifyAndReapply_reAppliesAnOverrideThatDrifted_andAuditsIt() {
        adapter.addKnownLogger("com.acme.Drift");
        service.setLevel("com.acme.Drift", Level.DEBUG, SetLevelOptions.sticky());
        adapter.applyLevel("com.acme.Drift", Level.INFO); // something reset it out from under us

        int reapplied = service.verifyAndReapply(java.time.Instant.now());

        assertEquals(1, reapplied);
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.Drift"));
        assertTrue(auditLog.records().stream().anyMatch(r ->
                r.source().equals("verification-sweep") && r.loggerName().equals("com.acme.Drift")
                        && r.action() == AuditRecord.Action.MUTATION));
    }

    @Test
    void verifyAndReapply_isANoOpWhenEveryOverrideIsStillInForce() {
        adapter.addKnownLogger("com.acme.Steady");
        service.setLevel("com.acme.Steady", Level.DEBUG, SetLevelOptions.sticky());
        int auditBefore = auditLog.records().size();

        assertEquals(0, service.verifyAndReapply(java.time.Instant.now()));
        assertEquals(auditBefore, auditLog.records().size());
    }

    @Test
    void verifyAndReapply_doesNotResurrectAnOverrideResetConcurrently() {
        adapter.addKnownLogger("com.acme.Raced");
        service.setLevel("com.acme.Raced", Level.DEBUG, SetLevelOptions.sticky());

        // A control-plane resetLevel lands while the sweep is reading this logger.
        adapter.runOnEffectiveLevel("com.acme.Raced", () -> service.resetLevel("com.acme.Raced"));

        int reapplied = service.verifyAndReapply(java.time.Instant.now());

        assertEquals(0, reapplied);
        assertTrue(overrides.get("com.acme.Raced").isEmpty(), "the reset stands");
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.Raced"),
                "the override was not resurrected onto the adapter");
        assertTrue(auditLog.records().stream().noneMatch(r -> r.source().equals("verification-sweep")),
                "no phantom verification-sweep audit for an override that was reset");
    }

    @Test
    void verifyAndReapply_honoursAnOverrideReplacedConcurrently() {
        adapter.addKnownLogger("com.acme.Swapped");
        service.setLevel("com.acme.Swapped", Level.DEBUG, SetLevelOptions.sticky());

        adapter.runOnEffectiveLevel("com.acme.Swapped",
                () -> service.setLevel("com.acme.Swapped", Level.TRACE, SetLevelOptions.defaults()));

        int reapplied = service.verifyAndReapply(java.time.Instant.now());

        assertEquals(0, reapplied);
        assertEquals(Level.TRACE, overrides.get("com.acme.Swapped").orElseThrow().level());
        assertEquals(Level.TRACE, adapter.effectiveLevel("com.acme.Swapped"),
                "the newer setLevel wins; the sweep did not shadow it with the stale level");
    }

    @Test
    void verifyAndReapply_leavesExpiredForOverridesForTheExpirySweep() {
        adapter.addKnownLogger("com.acme.Expired");
        service.setLevel("com.acme.Expired", Level.DEBUG,
                SetLevelOptions.forDuration(java.time.Duration.ofMillis(1)));
        adapter.applyLevel("com.acme.Expired", Level.INFO); // drifted

        int reapplied = service.verifyAndReapply(java.time.Instant.now().plusSeconds(60));

        assertEquals(0, reapplied, "an already-expired FOR override is the expiry sweep's job, not this one");
    }

    @Test
    void adoptOverride_doesNotWriteToTheStateStore() {
        LevelOverride fromAnotherContext = new LevelOverride(
                "com.acme.Shared", Level.DEBUG, null, java.time.Instant.now(), "jmx",
                PersistenceTier.STICKY, null);

        service.adoptOverride(fromAnotherContext);

        // The originating context already persisted it to the shared store;
        // adopting it in a second context must not write again.
        assertTrue(stateStore.loadAll().isEmpty());
    }

    // --- LoggerOverrideChangeListener (doc/specs/handler-floor-control.md "AUTO handler level", issue #20) -----

    private int changeCount;
    private LevelControlService serviceWithListener;

    private void setUpServiceWithListener() {
        changeCount = 0;
        LoggerOverrideChangeListener listener = () -> changeCount++;
        serviceWithListener = new LevelControlService(
                adapter, baselines, overrides, CapabilityPolicy.allowAll(), auditLog, stateStore, "alice", "jmx",
                listener);
    }

    @Test
    void setLevel_firesTheChangeListenerBeforeReturning() {
        setUpServiceWithListener();
        adapter.addKnownLogger("com.acme.Worker");

        serviceWithListener.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());

        assertEquals(1, changeCount);
    }

    @Test
    void setLevel_firesTheListenerBeforeComputingBlockingHandlers() {
        // The ordering itself, not just that it fires: the listener runs a
        // recompute (a real AUTO handler would lower itself here) that must
        // land before setLevel reads handler state for its own
        // blocking-handler answer -- doc/specs/handler-floor-control.md
        // "AUTO handler level", "Recompute trigger".
        adapter.addKnownLogger("com.acme.Worker");
        HandlerRef console = new HandlerRef("CONSOLE");
        adapter.addHandler(console, Level.INFO, "com.acme.Worker");
        LoggerOverrideChangeListener lowerConsoleOnChange = () -> adapter.setHandlerLevel(console, Level.TRACE);
        LevelControlService withAutoLikeListener = new LevelControlService(
                adapter, baselines, overrides, CapabilityPolicy.allowAll(), auditLog, stateStore, "alice", "jmx",
                lowerConsoleOnChange);

        SetLevelResult result = withAutoLikeListener.setLevel("com.acme.Worker", Level.TRACE, SetLevelOptions.defaults());

        assertTrue(result.blockingHandlers().isEmpty(),
                "CONSOLE was already lowered by the listener before the floor check ran");
    }

    @Test
    void resetLevel_firesTheChangeListener() {
        setUpServiceWithListener();
        adapter.addKnownLogger("com.acme.Worker");
        serviceWithListener.setLevel("com.acme.Worker", Level.DEBUG, SetLevelOptions.defaults());
        changeCount = 0;

        serviceWithListener.resetLevel("com.acme.Worker");

        assertEquals(1, changeCount);
    }

    @Test
    void resetLevel_withNothingToReset_doesNotFireTheChangeListener() {
        setUpServiceWithListener();

        serviceWithListener.resetLevel("com.acme.NeverOverridden");

        assertEquals(0, changeCount);
    }

    @Test
    void resetAll_firesTheChangeListenerOnce() {
        setUpServiceWithListener();
        adapter.addKnownLogger("a");
        adapter.addKnownLogger("b");
        serviceWithListener.setLevel("a", Level.DEBUG, SetLevelOptions.defaults());
        serviceWithListener.setLevel("b", Level.DEBUG, SetLevelOptions.defaults());
        changeCount = 0;

        serviceWithListener.resetAll();

        assertEquals(1, changeCount);
    }

    @Test
    void sweepExpiredOverrides_somethingExpired_firesTheChangeListener() {
        setUpServiceWithListener();
        adapter.addKnownLogger("com.acme.Worker");
        java.time.Instant now = java.time.Instant.now();
        serviceWithListener.setLevel("com.acme.Worker", Level.DEBUG,
                new SetLevelOptions(null, java.time.Duration.ofMinutes(30), PersistenceTier.FOR, false));
        changeCount = 0;

        serviceWithListener.sweepExpiredOverrides(now.plus(java.time.Duration.ofMinutes(31)));

        assertEquals(1, changeCount);
    }

    @Test
    void sweepExpiredOverrides_nothingExpired_doesNotFireTheChangeListener() {
        setUpServiceWithListener();
        adapter.addKnownLogger("com.acme.Worker");
        serviceWithListener.setLevel("com.acme.Worker", Level.DEBUG,
                new SetLevelOptions(null, java.time.Duration.ofMinutes(30), PersistenceTier.FOR, false));
        changeCount = 0;

        serviceWithListener.sweepExpiredOverrides(java.time.Instant.now()); // nowhere near the 30-minute deadline

        assertEquals(0, changeCount);
    }

    @Test
    void resumeFromStateStore_doesNotFireTheChangeListener() {
        setUpServiceWithListener();
        stateStore.save(new LevelOverride("com.acme.Worker", Level.DEBUG, null, java.time.Instant.now(),
                "jmx", PersistenceTier.STICKY, null));

        serviceWithListener.resumeFromStateStore(java.time.Instant.now());

        assertEquals(0, changeCount, "the composition root does one recompute pass after resume itself, not per entry");
    }

    @Test
    void adoptOverride_doesNotFireTheChangeListener() {
        setUpServiceWithListener();
        LevelOverride fromAnotherContext = new LevelOverride(
                "com.acme.Shared", Level.DEBUG, null, java.time.Instant.now(), "jmx",
                PersistenceTier.STICKY, null);

        serviceWithListener.adoptOverride(fromAnotherContext);

        assertEquals(0, changeCount, "the caller does one recompute pass after every override for the context lands");
    }
}
