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
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PatternRule;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.ResetOutcome;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.util.ArrayList;
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
        adapter.addKnownLogger("com.acme");
        adapter.addKnownLogger("com.acme.http");
        HandlerRef fileOnChildOnly = new HandlerRef("FILE");
        adapter.addHandler(fileOnChildOnly, Level.INFO, "com.acme.http"); // not on "com.acme"'s own path

        SetLevelResult result = service.setLevel(
                "com.acme.*", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

        assertEquals(1, result.blockingHandlers().size());
        assertEquals(fileOnChildOnly, result.blockingHandlers().get(0).handlerRef());
    }

    @Test
    void setLevel_pattern_dedupesAHandlerSharedAcrossMatches() {
        adapter.addKnownLogger("com.acme");
        adapter.addKnownLogger("com.acme.http");
        HandlerRef console = new HandlerRef("CONSOLE");
        adapter.addHandler(console, Level.INFO, "com.acme", "com.acme.http"); // on both paths

        SetLevelResult result = service.setLevel(
                "com.acme.*", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

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
        adapter.addKnownLogger("com.acme");
        adapter.addKnownLogger("com.acme.http");
        HandlerRef allHandlers = HandlerRef.ALL_HANDLERS;
        adapter.addHandlerWithPerTargetLevel(allHandlers, Level.INFO, "com.acme");
        adapter.addHandlerWithPerTargetLevel(allHandlers, Level.WARN, "com.acme.http"); // stricter

        SetLevelResult result = service.setLevel(
                "com.acme.*", Level.TRACE, SetLevelOptions.defaults().withConfirmed(true));

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

    // --- pattern targets: self + descendants, standing rules ---------------------------------

    @Test
    void setLevel_pattern_appliesToTheNamedLoggerAndEveryDescendantIndependently() {
        adapter.addKnownLogger("com.acme");
        adapter.addKnownLogger("com.acme.http");
        adapter.addKnownLogger("com.acme.http.client");
        adapter.addKnownLogger("com.other");

        service.setLevel("com.acme.*", Level.DEBUG, SetLevelOptions.withReason("reason").withConfirmed(true));

        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme"));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.http"));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme.http.client"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.other")); // untouched

        assertTrue(overrides.get("com.acme").isPresent());
        assertTrue(overrides.get("com.acme.http").isPresent());
        assertTrue(overrides.get("com.acme.http.client").isPresent());
        assertTrue(overrides.get("com.other").isEmpty());
    }

    @Test
    void setLevel_exactName_doesNotTouchDescendants() {
        adapter.addKnownLogger("com.acme.http");

        service.setLevel("com.acme", Level.DEBUG, SetLevelOptions.defaults());

        assertTrue(overrides.get("com.acme.http").isEmpty());
    }

    @Test
    void setLevel_pattern_doesNotImmediatelyApplyToANotYetKnownMatch_butTheRuleStandsForTheSweep() {
        // Unlike an exact-name target (setLevel_onNotYetExistingLogger_stillWorks,
        // above), a pattern only mutates CURRENTLY-known matches at creation
        // time. The rule itself is still created, ready for the sweep to
        // pick up "com.acme" the moment it's discovered.
        service.setLevel("com.acme.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));

        assertTrue(overrides.get("com.acme").isEmpty());

        adapter.addKnownLogger("com.acme");
        service.applyStandingRules(java.time.Instant.now());

        assertTrue(overrides.get("com.acme").isPresent());
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme"));
    }

    // --- resetLevel on a pattern: retires the standing rule -----------------------------------

    @Test
    void resetLevel_pattern_revertsEveryMatchAndReportsTheRuleRetired() {
        adapter.addKnownLogger("com.acme.http");
        adapter.addKnownLogger("com.acme.db");
        service.setLevel("com.acme.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));

        var outcome = service.resetLevel("com.acme.*");

        assertEquals(List.of("com.acme.*"), outcome.retiredPatterns());
        assertEquals(Set.of("com.acme.http", "com.acme.db"), Set.copyOf(outcome.revertedNames()));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.http"));
        assertEquals(Level.INFO, adapter.effectiveLevel("com.acme.db"));
        assertTrue(service.activePatternRules().isEmpty());
    }

    @Test
    void resetLevel_pattern_ruleTrackedButNoCurrentMatch_stillReportsRetiredWithNothingReverted() {
        // The rule exists (nothing has matched it yet, or every match was
        // since reset individually) -- retiring it is real work, distinct
        // from "no rule existed under that exact string at all" below.
        service.setLevel("com.brandnew.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));

        var outcome = service.resetLevel("com.brandnew.*");

        assertEquals(List.of("com.brandnew.*"), outcome.retiredPatterns());
        assertTrue(outcome.revertedNames().isEmpty());
    }

    @Test
    void resetLevel_pattern_noRuleEverTracked_isANoOpNotAnError() {
        var outcome = service.resetLevel("com.never.seen.*");

        assertEquals(ResetOutcome.nothingReset(), outcome);
    }

    // --- partial reset: scoped exclusions (doc/specs/reset-command-surface.md) ---------------

    @Test
    void resetLevel_exactNameUnderALiveRule_excludesJustThatNameLeavingDescendantsCovered() {
        // Deliberately NOT adding "org.apache" itself as a known logger --
        // it would also match the pattern and pick up its own DEBUG
        // override, which "org.apache.tomcat" would then inherit once its
        // own override is removed (ordinary hierarchy-based inheritance,
        // not a property of the exclusion mechanism this test targets).
        // Keeping it out of the fixture isolates that from what's actually
        // under test: whether the excluded name's own override is gone and
        // whether the sweep honors the exclusion.
        adapter.addKnownLogger("org.apache.tomcat");
        adapter.addKnownLogger("org.apache.tomcat.connector");
        service.setLevel("org.apache.*", Level.DEBUG, SetLevelOptions.sticky().withConfirmed(true));

        var outcome = service.resetLevel("org.apache.tomcat", true); // includeSticky -- the rule is STICKY

        assertTrue(outcome.retiredPatterns().isEmpty());
        assertEquals(List.of("org.apache.*"), outcome.excludedFrom());
        assertEquals(List.of("org.apache.tomcat"), outcome.revertedNames());
        assertTrue(overrides.get("org.apache.tomcat").isEmpty(), "the excluded name's own override is gone");
        // The rule is still active for everything else, including this
        // excluded name's own descendants -- the exclusion is exactly the
        // literal name, nothing more.
        assertTrue(service.activePatternRules().stream().anyMatch(r -> r.pattern().equals("org.apache.*")));
        assertEquals(Level.DEBUG, adapter.effectiveLevel("org.apache.tomcat.connector"));

        // A newly-discovered descendant of the excluded name still inherits
        // the rule...
        adapter.addKnownLogger("org.apache.tomcat.connector.http11");
        service.applyStandingRules(java.time.Instant.now());
        assertEquals(Level.DEBUG, adapter.effectiveLevel("org.apache.tomcat.connector.http11"));

        // ...but the excluded name itself is not reclaimed by a later sweep.
        service.applyStandingRules(java.time.Instant.now());
        assertTrue(overrides.get("org.apache.tomcat").isEmpty(), "still excluded after another sweep tick");
        assertEquals(Level.INFO, adapter.effectiveLevel("org.apache.tomcat"));
    }

    @Test
    void resetLevel_subPatternUnderALiveRule_excludesTheWholeSubtreePresentAndFuture() {
        // See the note in the test above on why "org.apache" itself is
        // deliberately left out of the fixture.
        adapter.addKnownLogger("org.apache.tomcat");
        adapter.addKnownLogger("org.apache.tomcat.connector");
        service.setLevel("org.apache.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));

        var outcome = service.resetLevel("org.apache.tomcat.*");

        assertEquals(List.of("org.apache.*"), outcome.excludedFrom());
        assertEquals(Set.of("org.apache.tomcat", "org.apache.tomcat.connector"), Set.copyOf(outcome.revertedNames()));
        assertTrue(overrides.get("org.apache.tomcat").isEmpty());
        assertTrue(overrides.get("org.apache.tomcat.connector").isEmpty());

        // A brand-new logger under the excluded subtree does NOT get swept
        // in -- the whole branch was wiped out for good.
        adapter.addKnownLogger("org.apache.tomcat.connector.http11");
        service.applyStandingRules(java.time.Instant.now());
        assertTrue(overrides.get("org.apache.tomcat.connector.http11").isEmpty());
        assertEquals(Level.INFO, adapter.effectiveLevel("org.apache.tomcat.connector.http11"));
    }

    @Test
    void resetLevel_stickyRule_partialTarget_leftAloneWithoutIncludeSticky() {
        adapter.addKnownLogger("org.apache.tomcat");
        service.setLevel("org.apache.*", Level.DEBUG, SetLevelOptions.sticky().withConfirmed(true));

        var outcome = service.resetLevel("org.apache.tomcat"); // includeSticky defaults to false

        assertEquals(List.of("org.apache.tomcat"), outcome.skippedStickyNames());
        assertTrue(outcome.revertedNames().isEmpty());
        assertTrue(outcome.excludedFrom().isEmpty());
        assertEquals(Level.DEBUG, adapter.effectiveLevel("org.apache.tomcat")); // untouched
    }

    @Test
    void resetLevel_exclusion_doesNotSurviveARetireThenRecreateOfTheSamePattern() {
        adapter.addKnownLogger("org.apache.tomcat");
        service.setLevel("org.apache.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true));
        service.resetLevel("org.apache.tomcat"); // carve out the exclusion

        service.resetLevel("org.apache.*"); // full retirement -- the old rule, and its exclusion, are both gone
        service.setLevel("org.apache.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)); // fresh rule

        assertEquals(Level.DEBUG, adapter.effectiveLevel("org.apache.tomcat"),
                "a brand-new rule instance reapplies to everything it currently matches, exclusions included");
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
        // Parent already at DEBUG (its own baseline) -- moving it to DEBUG again
        // is "lower or equal", which the policy below allows.
        adapter.setConfiguredLevel("com.acme", Level.DEBUG);
        // Child has its OWN explicit baseline at ERROR (not inherited from the
        // parent) -- moving it to DEBUG is a genuine raise, which the policy
        // below denies. (A child with no baseline of its own would inherit the
        // parent's DEBUG here, making this indistinguishable from the parent's
        // own direction -- the explicit child baseline is what creates the
        // asymmetry this test needs.)
        adapter.setConfiguredLevel("com.acme.http", Level.ERROR);
        LevelControlService denied = newService(capability -> capability != Capability.LEVEL_RAISE);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("com.acme.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)));

        // The parent passed its own check but must NOT have been mutated --
        // the whole batch is pre-checked before anything is applied.
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme")); // its baseline, unchanged
        assertEquals(Level.ERROR, adapter.effectiveLevel("com.acme.http")); // untouched
        assertTrue(overrides.all().isEmpty());
        assertTrue(auditLog.records().isEmpty());
    }

    @Test
    void setLevel_pattern_zeroCurrentMatches_stillRequiresPersist() {
        // Code-review finding: the PERSIST check used to be nested inside a
        // per-target loop, so a pattern matching no currently-known logger
        // (an empty target list) skipped it entirely -- a principal denied
        // PERSIST could still create a durable standing rule.
        LevelControlService denied = newService(capability -> capability != Capability.PERSIST);

        assertThrows(CapabilityDeniedException.class,
                () -> denied.setLevel("org.brandnew.*", Level.DEBUG,
                        SetLevelOptions.sticky().withConfirmed(true)));

        assertTrue(stateStore.loadAllPatternRules().isEmpty(),
                "no rule should have been created or persisted when PERSIST is denied");
    }

    @Test
    void setLevel_pattern_precedenceSkippedMatch_isNotConsultedForCapability() {
        // Code-review finding: capability used to be checked against every
        // resolved match, including one that precedence will skip entirely
        // (an existing exact-name override) -- denying that skipped
        // logger's own direction should not block the call.
        adapter.setConfiguredLevel("com.acme.Other", Level.TRACE); // no override; TRACE -> DEBUG is a lower
        service.setLevel("com.acme.Legacy", Level.WARN, SetLevelOptions.defaults()); // exact-name override, at WARN
        LevelControlService lowerOnly = newService(capability -> capability != Capability.LEVEL_RAISE);

        // "com.acme.Legacy" would need LEVEL_RAISE to go from WARN to DEBUG
        // (more verbose) if it were consulted, but it's covered by an
        // exact-name override and precedence skips it -- only
        // com.acme.Other (a lower, granted) actually gets mutated, so this
        // must succeed rather than throw CapabilityDeniedException(RAISE).
        SetLevelResult result = lowerOnly.setLevel("com.acme.*", Level.DEBUG,
                SetLevelOptions.defaults().withConfirmed(true));

        assertEquals(1, result.overrides().size());
        assertEquals("com.acme.Other", result.overrides().get(0).loggerName());
        assertEquals(Level.WARN, adapter.effectiveLevel("com.acme.Legacy"), "precedence-skipped, untouched");
    }

    @Test
    void setLevel_pattern_persistsTheRuleBeforeAnyMatchedOverride() {
        // Code-review finding: persisting per-logger overrides before the
        // rule itself left them orphaned (no way to group-revert, and the
        // sweep would never re-derive the rule) if a crash landed between
        // the two. Persisting the rule first means a logger whose override
        // write is lost simply looks uncovered and gets swept up again.
        adapter.addKnownLogger("com.acme.Worker");
        List<String> persistOrder = new ArrayList<>();
        StateStore orderTrackingStore = new StateStore() {
            @Override
            public List<LevelOverride> loadAll() {
                return List.of();
            }

            @Override
            public void save(LevelOverride override) {
                persistOrder.add("override:" + override.loggerName());
            }

            @Override
            public void remove(String loggerName) {
            }

            @Override
            public List<HandlerLevelOverride> loadAllHandlers() {
                return List.of();
            }

            @Override
            public void saveHandler(HandlerLevelOverride override) {
            }

            @Override
            public void removeHandler(HandlerRef ref) {
            }

            @Override
            public List<PatternRule> loadAllPatternRules() {
                return List.of();
            }

            @Override
            public void savePatternRule(PatternRule rule) {
                persistOrder.add("rule:" + rule.pattern());
            }

            @Override
            public void removePatternRule(String pattern) {
            }

            @Override
            public void clear() {
            }
        };
        LevelControlService withTrackingStore = new LevelControlService(adapter, baselines, overrides,
                CapabilityPolicy.allowAll(), auditLog, orderTrackingStore, "alice", "jmx");

        withTrackingStore.setLevel("com.acme.*", Level.DEBUG, SetLevelOptions.sticky().withConfirmed(true));

        assertEquals(List.of("rule:com.acme.*", "override:com.acme.Worker"), persistOrder);
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
        adapter.addKnownLogger("com.acme");
        adapter.addKnownLogger("com.acme.a");
        adapter.addKnownLogger("com.acme.b");
        // matchesFor iterates a sorted TreeSet, and "com.acme" is a prefix of
        // both, so the order is "com.acme", then "com.acme.a", then "com.acme.b".
        adapter.throwOnApply("com.acme.a");

        assertThrows(RuntimeException.class,
                () -> service.setLevel("com.acme.*", Level.DEBUG, SetLevelOptions.defaults().withConfirmed(true)));

        // The parent, processed before the throwing child, is applied and recorded.
        assertEquals(Level.DEBUG, adapter.effectiveLevel("com.acme"));
        assertTrue(overrides.get("com.acme").isPresent());
        assertEquals(1, auditLog.records().size());

        // The throwing child was never committed; nothing after it was attempted either.
        assertTrue(overrides.get("com.acme.a").isEmpty());
        assertTrue(overrides.get("com.acme.b").isEmpty());
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
                "com.acme.Shared", Level.DEBUG, null, "why", java.time.Instant.now(), "jmx",
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

        // A control-plane resetLevel lands while the sweep is reading this
        // logger -- includeSticky=true since this override is STICKY-tier
        // and the new default otherwise leaves it untouched (doc/specs/
        // reset-command-surface.md), which is a different concern from what
        // this test is actually exercising.
        adapter.runOnEffectiveLevel("com.acme.Raced", () -> service.resetLevel("com.acme.Raced", true));

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
                "com.acme.Shared", Level.DEBUG, null, null, java.time.Instant.now(), "jmx",
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
        stateStore.save(new LevelOverride("com.acme.Worker", Level.DEBUG, null, null, java.time.Instant.now(),
                "jmx", PersistenceTier.STICKY, null));

        serviceWithListener.resumeFromStateStore(java.time.Instant.now());

        assertEquals(0, changeCount, "the composition root does one recompute pass after resume itself, not per entry");
    }

    @Test
    void adoptOverride_doesNotFireTheChangeListener() {
        setUpServiceWithListener();
        LevelOverride fromAnotherContext = new LevelOverride(
                "com.acme.Shared", Level.DEBUG, null, null, java.time.Instant.now(), "jmx",
                PersistenceTier.STICKY, null);

        serviceWithListener.adoptOverride(fromAnotherContext);

        assertEquals(0, changeCount, "the caller does one recompute pass after every override for the context lands");
    }
}
