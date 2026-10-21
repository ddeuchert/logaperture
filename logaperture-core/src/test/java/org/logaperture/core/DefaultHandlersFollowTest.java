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
import org.logaperture.api.FollowedHandler;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.SetLevelResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/default-handlers-follow.md. The fixture: CONSOLE at INFO is the one {@code DEFAULT_HANDLERS}
 * member (the only handler on the root); FILE at ALL and AUDIT at WARN are on the same loggers' paths but
 * aren't default members. Both services are wired as a container wires them.
 */
class DefaultHandlersFollowTest {

    private static final String APP = "com.acme";
    private static final String OTHER = "com.other";
    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef FILE = new HandlerRef("FILE");
    private static final HandlerRef AUDIT = new HandlerRef("AUDIT");

    private FakeLoggingAdapter adapter;
    private InMemoryAuditLog auditLog;
    private InMemoryStateStore stateStore;
    private OverrideRegistry overrides;
    private HandlerOverrideRegistry handlerOverrides;
    private HandlerLevelControlService handlers;
    private LevelControlService loggers;

    @BeforeEach
    void setUp() {
        adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addKnownLogger(APP);
        adapter.addKnownLogger(OTHER);
        adapter.addHandler(CONSOLE, Level.INFO, APP, OTHER);
        adapter.attachToRoot(CONSOLE);
        adapter.addHandler(FILE, Level.ALL, APP, OTHER);
        adapter.addHandler(AUDIT, Level.WARN, APP);
        auditLog = new InMemoryAuditLog();
        stateStore = new InMemoryStateStore();
        wire(CapabilityPolicy.allowAll());
    }

    private void wire(CapabilityPolicy policy) {
        overrides = new OverrideRegistry();
        handlerOverrides = new HandlerOverrideRegistry();
        ActiveLoggerFloor floor = () -> List.copyOf(overrides.all().values());
        handlers = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(), handlerOverrides,
                new DefaultHandlerGroupRegistry(), policy, auditLog, stateStore, "alice", "jmx", floor);
        LoggerOverrideChangeListener recompute = handlers::recomputeAuto;
        HandlerFollowing follow = handlers::follow;
        loggers = new LevelControlService(adapter, new BaselineRegistry(), overrides, policy, auditLog, stateStore,
                "alice", "jmx", recompute, follow);
    }

    private static SetLevelOptions forMinutes(long minutes) {
        return SetLevelOptions.forDuration(Duration.ofMinutes(minutes)).withFollowHandlers(true);
    }

    private SetLevelResult set(String logger, Level level, SetLevelOptions options) {
        return loggers.setLogger(logger, level, options);
    }

    private Optional<HandlerLevelOverride> handlerOverride(HandlerRef ref) {
        return handlerOverrides.get(ref);
    }

    @Test
    void theDefaultHandlerFollowsAndOthersKeepTheWarning() {
        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE));
        HandlerLevelOverride follower = handlerOverride(CONSOLE).orElseThrow();
        assertEquals(HandlerLevelMode.AUTO, follower.mode());
        assertEquals(List.of(APP), follower.follows());
        assertEquals(PersistenceTier.FOR, follower.tier());
        assertEquals(overrides.get(APP).orElseThrow().expiresAt(), follower.expiresAt());

        assertEquals(1, result.following().size());
        FollowedHandler followed = result.following().get(0);
        assertEquals(CONSOLE, followed.handlerRef());
        assertEquals(Level.INFO, followed.previousLevel());
        assertEquals(Level.DEBUG, followed.level());
        assertFalse(followed.carriesOn());
        // AUDIT isn't a default member: still warned about. FILE lets DEBUG through.
        assertEquals(List.of(AUDIT), result.blockingHandlers().stream().map(f -> f.handlerRef()).toList());
        assertNull(result.followDenied());

        assertTrue(auditLog.records().stream().anyMatch(r -> "CONSOLE".equals(r.loggerName())
                && r.action() == AuditRecord.Action.MUTATION && ("follows " + APP).equals(r.origin())));
    }

    @Test
    void withoutFollowHandlersNothingFollows() {
        SetLevelResult result = set(APP, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(30)));

        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertTrue(result.following().isEmpty());
        assertTrue(result.blockingHandlers().stream().anyMatch(f -> f.handlerRef().equals(CONSOLE)));
    }

    @Test
    void aChangeTheHandlerDoesNotDropChangesNothing() {
        SetLevelResult result = set(APP, Level.WARN, forMinutes(30));

        assertTrue(result.following().isEmpty());
        assertTrue(handlerOverride(CONSOLE).isEmpty());
    }

    @Test
    void theFollowerEndsWhenTheChangeIsReset() {
        set(APP, Level.DEBUG, forMinutes(30));

        loggers.resetLogger(APP, false);

        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
        assertTrue(stateStore.loadAllHandlers().isEmpty());
        assertTrue(auditLog.records().stream().anyMatch(r -> "CONSOLE".equals(r.loggerName())
                && r.action() == AuditRecord.Action.REVERSION
                && HandlerLevelControlService.FOLLOW_AUDIT_SOURCE.equals(r.source())));
    }

    @Test
    void theFollowerEndsWhenTheChangeExpires() {
        set(APP, Level.DEBUG, forMinutes(30));

        Instant later = Instant.now().plus(Duration.ofHours(1));
        loggers.sweepExpiredOverrides(later);
        handlers.sweepExpiredOverrides(later);

        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void aQuieterChangeOnTheSameLoggerEndsIt() {
        set(APP, Level.DEBUG, forMinutes(30));

        set(APP, Level.WARN, forMinutes(30));

        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void keepHandlersOnTheSameLoggerLetsGoOfIt() {
        set(APP, Level.DEBUG, forMinutes(30));

        set(APP, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMinutes(30)));

        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void twoLoggersShareOneFollowerUntilBothEnd() {
        set(APP, Level.DEBUG, forMinutes(30));
        set(OTHER, Level.TRACE, SetLevelOptions.sticky().withFollowHandlers(true));

        HandlerLevelOverride follower = handlerOverride(CONSOLE).orElseThrow();
        assertEquals(List.of(APP, OTHER), follower.follows());
        assertEquals(PersistenceTier.STICKY, follower.tier()); // the longest of the two
        assertNull(follower.expiresAt());
        assertEquals(Optional.of(Level.TRACE), adapter.handlerLevel(CONSOLE)); // AUTO: the lowest active
        assertEquals(1, stateStore.loadAllHandlers().size());

        loggers.resetLogger(OTHER, true);
        follower = handlerOverride(CONSOLE).orElseThrow();
        assertEquals(List.of(APP), follower.follows());
        assertEquals(PersistenceTier.FOR, follower.tier());
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE));

        loggers.resetLogger(APP, false);
        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void anOperatorAutoLastingLongerChangesNothing() {
        handlers.setHandlerAuto(CONSOLE, SetHandlerLevelOptions.sticky());

        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        HandlerLevelOverride own = handlerOverride(CONSOLE).orElseThrow();
        assertFalse(own.isFollower());
        assertTrue(own.thenFollows().isEmpty());
        assertTrue(result.following().isEmpty());
        assertTrue(result.blockingHandlers().stream().noneMatch(f -> f.handlerRef().equals(CONSOLE)));
    }

    @Test
    void anOperatorLevelThatLetsItThroughForLongerChangesNothing() {
        handlers.setHandlerLevel(CONSOLE, Level.ALL, SetHandlerLevelOptions.forDuration(Duration.ofHours(2)));

        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        assertTrue(handlerOverride(CONSOLE).orElseThrow().thenFollows().isEmpty());
        assertTrue(result.following().isEmpty());
    }

    @Test
    void anOperatorAutoEndingSoonerCarriesOnFollowing() {
        handlers.setHandlerAuto(CONSOLE, SetHandlerLevelOptions.forDuration(Duration.ofMinutes(10)));
        HandlerLevelOverride own = handlerOverride(CONSOLE).orElseThrow();

        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        HandlerLevelOverride tagged = handlerOverride(CONSOLE).orElseThrow();
        assertEquals(own.expiresAt(), tagged.expiresAt()); // left as it is
        assertEquals(List.of(APP), tagged.thenFollows());
        assertTrue(result.following().get(0).carriesOn());
        assertEquals(own.expiresAt(), result.following().get(0).ownExpiresAt());

        Instant afterOwn = Instant.now().plus(Duration.ofMinutes(15));
        loggers.sweepExpiredOverrides(afterOwn);
        handlers.sweepExpiredOverrides(afterOwn);

        HandlerLevelOverride follower = handlerOverride(CONSOLE).orElseThrow();
        assertEquals(List.of(APP), follower.follows());
        assertEquals(overrides.get(APP).orElseThrow().expiresAt(), follower.expiresAt());
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE)); // never back to INFO in between
        assertEquals(1, stateStore.loadAllHandlers().size());

        Instant afterLogger = Instant.now().plus(Duration.ofMinutes(45));
        loggers.sweepExpiredOverrides(afterLogger);
        handlers.sweepExpiredOverrides(afterLogger);
        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void aCarryOnTagEndsWithTheChangeAndTheOverrideRevertsNormally() {
        handlers.setHandlerAuto(CONSOLE, SetHandlerLevelOptions.forDuration(Duration.ofMinutes(10)));
        set(APP, Level.DEBUG, forMinutes(30));

        loggers.resetLogger(APP, false);
        assertTrue(handlerOverride(CONSOLE).orElseThrow().thenFollows().isEmpty());

        Instant afterOwn = Instant.now().plus(Duration.ofMinutes(15));
        handlers.sweepExpiredOverrides(afterOwn);
        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void anOperatorLevelThatBlocksIsLeftAloneWithTheWarning() {
        handlers.setHandlerLevel(CONSOLE, Level.WARN, SetHandlerLevelOptions.forDuration(Duration.ofHours(2)));

        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        HandlerLevelOverride own = handlerOverride(CONSOLE).orElseThrow();
        assertEquals(Level.WARN, own.level());
        assertTrue(own.thenFollows().isEmpty());
        assertTrue(result.following().isEmpty());
        assertTrue(result.blockingHandlers().stream().anyMatch(f -> f.handlerRef().equals(CONSOLE)));
    }

    @Test
    void aLaterHandlerCommandReplacesTheFollower() {
        set(APP, Level.DEBUG, forMinutes(30));

        handlers.setHandlerLevel(CONSOLE, Level.WARN, SetHandlerLevelOptions.forDuration(Duration.ofHours(2)));
        loggers.resetLogger(APP, false);

        HandlerLevelOverride own = handlerOverride(CONSOLE).orElseThrow();
        assertFalse(own.isFollower());
        assertEquals(Level.WARN, own.level());
        assertEquals(Optional.of(Level.WARN), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void resettingTheHandlerEndsTheFollower() {
        set(APP, Level.DEBUG, forMinutes(30));

        handlers.resetHandler(CONSOLE, false);

        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
        set(OTHER, Level.WARN, forMinutes(30)); // any later change: nothing brings it back
        assertTrue(handlerOverride(CONSOLE).isEmpty());
    }

    @Test
    void aDeniedHandlerLowerKeepsTheWarningAndSaysWhy() {
        wire(capability -> capability != Capability.HANDLER_LOWER);

        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        assertTrue(result.following().isEmpty());
        assertEquals("HANDLER_LOWER", result.followDenied());
        assertTrue(result.blockingHandlers().stream().anyMatch(f -> f.handlerRef().equals(CONSOLE)));
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void turningFollowingOffWithTheSystemPropertyKeepsTheWarningAndSaysWhy() {
        System.setProperty(HandlerFollowing.PROPERTY, "false");
        try {
            SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

            assertTrue(result.following().isEmpty());
            assertEquals("-Dlogaperture.followHandlers=false", result.followDenied());
            assertTrue(result.blockingHandlers().stream().anyMatch(f -> f.handlerRef().equals(CONSOLE)));
            assertTrue(handlerOverride(CONSOLE).isEmpty());
            assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
        } finally {
            System.clearProperty(HandlerFollowing.PROPERTY);
        }
    }

    @Test
    void followingIsOnWhenTheSystemPropertyIsAbsentOrTrue() {
        assertTrue(HandlerFollowing.enabled());
        System.setProperty(HandlerFollowing.PROPERTY, "true");
        try {
            assertTrue(HandlerFollowing.enabled());
        } finally {
            System.clearProperty(HandlerFollowing.PROPERTY);
        }
    }

    @Test
    void aDeniedPersistKeepsTheWarningForALastingChange() {
        wire(capability -> capability != Capability.PERSIST || false);
        // PERSIST is needed for the logger change itself too: a session change follows without it.
        SetLevelResult result = set(APP, Level.DEBUG, SetLevelOptions.defaults().withFollowHandlers(true));

        assertEquals(1, result.following().size());
        assertEquals(PersistenceTier.SESSION, handlerOverride(CONSOLE).orElseThrow().tier());
        assertTrue(stateStore.loadAllHandlers().isEmpty());
    }

    @Test
    void forcedDescendantsAreFollowedToo() {
        String child = APP + ".db";
        adapter.addKnownLogger(child);
        adapter.setConfiguredLevel(child, Level.WARN);
        adapter.addHandler(CONSOLE, Level.INFO, child);

        set(APP, Level.DEBUG, forMinutes(30).withForce(true));

        assertEquals(List.of(APP, child), handlerOverride(CONSOLE).orElseThrow().follows());
    }

    @Test
    void aLoggerFrameworkWithoutHandlerLevelsFollowsNothing() {
        adapter.disableHandlerLevels();

        SetLevelResult result = set(APP, Level.DEBUG, forMinutes(30));

        assertTrue(result.following().isEmpty());
        assertNull(result.followDenied());
    }

    @Test
    void aStickyFollowerResumesWithItsTie() {
        set(APP, Level.DEBUG, SetLevelOptions.sticky().withFollowHandlers(true));
        assertEquals(List.of(APP), stateStore.loadAllHandlers().get(0).follows());

        adapter.setHandlerLevel(CONSOLE, Level.INFO); // a restart: the JVM starts at its own config
        adapter.applyLevel(APP, null);
        wire(CapabilityPolicy.allowAll());
        loggers.resumeFromStateStore(Instant.now());
        handlers.resumeFromStateStore(Instant.now());
        handlers.recomputeAuto();

        assertEquals(List.of(APP), handlerOverride(CONSOLE).orElseThrow().follows());
        assertEquals(Optional.of(Level.DEBUG), adapter.handlerLevel(CONSOLE));
        loggers.resetLogger(APP, true);
        assertTrue(handlerOverride(CONSOLE).isEmpty());
        assertEquals(Optional.of(Level.INFO), adapter.handlerLevel(CONSOLE));
    }

    @Test
    void exportLeavesAStickyFollowerOut() {
        set(APP, Level.DEBUG, SetLevelOptions.sticky().withFollowHandlers(true));

        assertTrue(handlers.exportHandlers().stream().noneMatch(h -> h.ref().equals(CONSOLE)));
    }
}
