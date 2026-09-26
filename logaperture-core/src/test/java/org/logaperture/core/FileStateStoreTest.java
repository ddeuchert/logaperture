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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistedRule;
import org.logaperture.api.PersistenceTier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against the real filesystem — a temp {@code logaperture.home} per test, so
 * no test touches the developer's actual {@code ~/.logaperture}. See
 * doc/specs/persistence.md "State store" and "Testing".
 */
class FileStateStoreTest {

    private final String originalUserDir = System.getProperty("user.dir");

    @TempDir
    private Path home;

    @TempDir
    private Path workingDir;

    @BeforeEach
    void pointAtTempHomeAndCwd() {
        System.setProperty("logaperture.home", home.toString());
        System.setProperty("user.dir", workingDir.toString());
    }

    @AfterEach
    void restoreProperties() {
        System.clearProperty("logaperture.home");
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty(InstanceIdentity.INSTANCE_ID_PROPERTY);
    }

    @Test
    void roundTrips_everyFieldIncludingBothExpiresAtCases() throws IOException {
        LevelOverride sticky = new LevelOverride(
                "com.acme.payments", Level.WARN, "known-noisy, muted for good",
                Instant.parse("2026-08-15T10:00:00Z"), "jmx", PersistenceTier.STICKY, null);
        LevelOverride timed = new LevelOverride(
                "com.acme.batch.Worker", Level.DEBUG, "investigating slot exhaustion",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.FOR,
                Instant.parse("2026-08-21T03:44:02Z"));

        try (FileStateStore store = FileStateStore.open()) {
            store.save(sticky);
            store.save(timed);
        }

        try (FileStateStore reopened = FileStateStore.open()) {
            // Every field but the state id, which the store assigns (covered by the stateId tests below).
            List<LevelOverride> loaded = reopened.loadAll().stream().map(o -> o.withStateId(null)).toList();
            assertEquals(2, loaded.size());
            assertTrue(loaded.contains(sticky));
            assertTrue(loaded.contains(timed));
        }
    }

    @Test
    void save_leavesNoTemporaryFilesBehind() throws IOException {
        try (FileStateStore store = FileStateStore.open()) {
            store.save(sampleOverride("com.acme.Worker"));
        }

        try (Stream<Path> files = Files.list(home.resolve("instances"))) {
            List<String> names = files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertTrue(names.stream().noneMatch(n -> n.endsWith(".tmp")));
            assertTrue(names.stream().anyMatch(n -> n.endsWith(".state.yaml")));
        }
    }

    @Test
    void location_isTheFullyQualifiedStateFilePath() throws IOException {
        // doc/specs/environment-report.md "State file".
        try (FileStateStore store = FileStateStore.open()) {
            Path location = store.location().orElseThrow();

            assertTrue(location.isAbsolute(), location.toString());
            assertTrue(location.startsWith(home.resolve("instances")), location.toString());
            assertTrue(location.toString().endsWith(".state.yaml"), location.toString());
        }
    }

    @Test
    void remove_isANoOpWhenTheLoggerWasNeverPersisted() throws IOException {
        try (FileStateStore store = FileStateStore.open()) {
            store.remove("com.acme.NeverThere"); // must not throw
            assertTrue(store.loadAll().isEmpty());
        }
    }

    @Test
    void removeAll_dropsEveryNamedEntryInOneRewrite() throws IOException {
        // doc/specs/persistence.md "Batch removal" (issue #17).
        try (FileStateStore store = FileStateStore.open()) {
            store.save(sampleOverride("com.acme.One"));
            store.save(sampleOverride("com.acme.Two"));
            store.save(sampleOverride("com.acme.Three"));

            store.removeAll(List.of("com.acme.One", "com.acme.Two", "com.acme.NeverThere"));

            assertEquals(1, store.loadAll().size());
            assertEquals("com.acme.Three", store.loadAll().get(0).loggerName());
        }
    }

    @Test
    void removeAll_emptyCollection_doesNotRewriteTheFile() throws IOException {
        try (FileStateStore store = FileStateStore.open()) {
            store.save(sampleOverride("com.acme.Untouched"));
            Path location = store.location().orElseThrow();
            var mtimeBefore = Files.getLastModifiedTime(location);

            store.removeAll(List.of());

            assertEquals(mtimeBefore, Files.getLastModifiedTime(location));
        }
    }

    @Test
    void removeAllHandlers_dropsEveryNamedEntryInOneRewrite() throws IOException {
        try (FileStateStore store = FileStateStore.open()) {
            store.saveHandler(sampleHandlerOverride(new HandlerRef("CONSOLE")));
            store.saveHandler(sampleHandlerOverride(new HandlerRef("FILE")));

            store.removeAllHandlers(List.of(new HandlerRef("CONSOLE"), new HandlerRef("NEVER-THERE")));

            assertEquals(1, store.loadAllHandlers().size());
            assertEquals(new HandlerRef("FILE"), store.loadAllHandlers().get(0).handlerRef());
        }
    }

    @Test
    void loadAll_missingStateFile_isEmptyNotAnError() throws IOException {
        try (FileStateStore store = FileStateStore.open()) {
            assertTrue(store.loadAll().isEmpty());
        }
    }

    @Test
    void open_corruptExistingStateFile_startsEmptyRatherThanFailing() throws IOException {
        Path instancesDir = home.resolve("instances");
        Files.createDirectories(instancesDir);
        String baseName = InstanceIdentity.hash(InstanceIdentity.resolveIdentityString())
                + "-" + InstanceIdentity.slugOfCanonicalCwd();
        Files.writeString(instancesDir.resolve(baseName + ".state.yaml"), "not: valid\n- this is garbage {{{");

        try (FileStateStore store = FileStateStore.open()) { // must not throw
            assertTrue(store.loadAll().isEmpty());
        }
    }

    @Test
    void instanceIdOverride_isolatesTwoJvmsThatWouldOtherwiseShareAWorkingDirectory() throws IOException {
        System.setProperty(InstanceIdentity.INSTANCE_ID_PROPERTY, "instance-a");
        try (FileStateStore a = FileStateStore.open()) {
            a.save(sampleOverride("com.acme.OnlyA"));
        }

        System.setProperty(InstanceIdentity.INSTANCE_ID_PROPERTY, "instance-b");
        try (FileStateStore b = FileStateStore.open()) {
            assertTrue(b.loadAll().isEmpty()); // a fresh identity, not instance-a's data
            b.save(sampleOverride("com.acme.OnlyB"));
        }

        System.setProperty(InstanceIdentity.INSTANCE_ID_PROPERTY, "instance-a");
        try (FileStateStore a2 = FileStateStore.open()) {
            assertEquals("com.acme.OnlyA", a2.loadAll().get(0).loggerName());
        }
    }

    @Test
    void open_secondTimeForTheSameIdentityInTheSameProcess_throwsInstanceLocked() throws IOException {
        try (FileStateStore first = FileStateStore.open()) {
            FileStateStore.InstanceLockedException exception =
                    assertThrows(FileStateStore.InstanceLockedException.class, FileStateStore::open);
            assertEquals(ProcessHandle.current().pid(), exception.holderPid());
        }
    }

    @Test
    void afterClose_theLockCanBeReacquired() throws IOException {
        FileStateStore first = FileStateStore.open();
        first.close();

        try (FileStateStore second = FileStateStore.open()) { // must not throw
            assertTrue(second.loadAll().isEmpty());
        }
    }

    @Test
    void rules_roundTripThroughARealReopenedFile() throws IOException {
        PersistedRule rule = new PersistedRule("r1", "com.acme.Worker", "Drop",
                new CompiledMatchers(Level.ERROR, "This happens a lot", false, null, null, false),
                "INC-123", PersistenceTier.STICKY, null, Instant.parse("2026-09-22T03:14:02Z"), "system", Map.of());

        try (FileStateStore store = FileStateStore.open()) {
            store.saveRule(rule);
        }

        try (FileStateStore reopened = FileStateStore.open()) {
            assertEquals(List.of(rule), reopened.loadAllRules().stream().map(r -> r.withStateId(null)).toList());
        }
    }

    // doc/specs/export-round-trip.md "An id for every persisted setting" (S3) and "State file" (S9).

    @Test
    void save_assignsAStateId_andAReopenedFileKeepsIt() throws IOException {
        LevelOverride sticky = new LevelOverride("com.acme.Foo", Level.WARN, null,
                Instant.parse("2026-09-26T10:00:00Z"), "jmx", PersistenceTier.STICKY, null);
        String stateId;
        try (FileStateStore store = FileStateStore.open()) {
            store.save(sticky);
            stateId = store.loadAll().get(0).stateId();
            assertNotNull(stateId);
        }
        try (FileStateStore reopened = FileStateStore.open()) {
            assertEquals(stateId, reopened.loadAll().get(0).stateId());
        }
    }

    @Test
    void saveOverAnExistingEntry_keepsItsStateId_butRemoveThenSaveGetsANewOne() throws IOException {
        LevelOverride warn = new LevelOverride("com.acme.Foo", Level.WARN, null,
                Instant.parse("2026-09-26T10:00:00Z"), "jmx", PersistenceTier.STICKY, null);
        LevelOverride error = new LevelOverride("com.acme.Foo", Level.ERROR, null,
                Instant.parse("2026-09-26T10:05:00Z"), "jmx", PersistenceTier.STICKY, null);
        try (FileStateStore store = FileStateStore.open()) {
            store.save(warn);
            String first = store.loadAll().get(0).stateId();
            store.save(error);
            assertEquals(first, store.loadAll().get(0).stateId());
            assertEquals(Level.ERROR, store.loadAll().get(0).level());

            store.remove("com.acme.Foo");
            store.save(warn);
            assertNotEquals(first, store.loadAll().get(0).stateId());
        }
    }

    @Test
    void handlerRuleAndDefaultMembers_eachGetAStateIdKeptAcrossSaves() throws IOException {
        HandlerLevelOverride handler = HandlerLevelOverride.fixed(new HandlerRef("FILE"), Level.INFO, null,
                Instant.parse("2026-09-26T10:00:00Z"), "jmx", PersistenceTier.STICKY, null);
        PersistedRule rule = new PersistedRule("r1", "com.acme.Worker", "drop",
                new CompiledMatchers(Level.ERROR, "noise", false, null, null, false),
                null, PersistenceTier.STICKY, null, Instant.parse("2026-09-26T10:00:00Z"), "system", Map.of());
        try (FileStateStore store = FileStateStore.open()) {
            store.saveHandler(handler);
            store.saveRule(rule);
            store.saveDefaultHandlerMembers(List.of("CONSOLE"));
            String handlerId = store.loadAllHandlers().get(0).stateId();
            String ruleId = store.loadAllRules().get(0).stateId();
            String membersId = store.defaultHandlerMembersStateId().orElseThrow();

            store.saveHandler(handler);
            store.saveRule(rule);
            store.saveDefaultHandlerMembers(List.of("CONSOLE", "FILE"));
            assertEquals(handlerId, store.loadAllHandlers().get(0).stateId());
            assertEquals(ruleId, store.loadAllRules().get(0).stateId());
            assertEquals(membersId, store.defaultHandlerMembersStateId().orElseThrow());

            store.removeDefaultHandlerMembers();
            assertTrue(store.defaultHandlerMembersStateId().isEmpty());
        }
    }

    @Test
    void anEntryWrittenBeforeStateIds_getsOneWhenTheFileOpens_andItIsWrittenBack() throws IOException {
        String version8 = """
                schemaVersion: 8
                overrides:
                  - loggerName: "com.acme.Foo"
                    level: WARN
                    reason: null
                    appliedAt: 2026-09-01T10:00:00Z
                    source: "jmx"
                    tier: STICKY
                    expiresAt: null
                handlerOverrides: []
                defaultHandlerMembers:
                  - "CONSOLE"
                rules: []
                """;
        Path stateFile;
        try (FileStateStore store = FileStateStore.open()) {
            stateFile = store.location().orElseThrow();
        }
        Files.writeString(stateFile, version8);

        String stateId;
        String membersId;
        try (FileStateStore store = FileStateStore.open()) {
            stateId = store.loadAll().get(0).stateId();
            membersId = store.defaultHandlerMembersStateId().orElseThrow();
            assertNotNull(stateId);
        }
        String rewritten = Files.readString(stateFile);
        assertTrue(rewritten.contains("schemaVersion: 9"), rewritten);
        assertTrue(rewritten.contains(stateId), rewritten);
        try (FileStateStore reopened = FileStateStore.open()) {
            assertEquals(stateId, reopened.loadAll().get(0).stateId());
            assertEquals(membersId, reopened.defaultHandlerMembersStateId().orElseThrow());
        }
    }

    @Test
    void removeRule_dropsItAndSurvivesReopen() throws IOException {
        PersistedRule rule = new PersistedRule("r1", "com.acme.Worker", "Drop", CompiledMatchers.matchAll(), null,
                PersistenceTier.STICKY, null, Instant.now(), "system", Map.of());

        try (FileStateStore store = FileStateStore.open()) {
            store.saveRule(rule);
            store.removeRule("r1");
            assertTrue(store.loadAllRules().isEmpty());
        }

        try (FileStateStore reopened = FileStateStore.open()) {
            assertTrue(reopened.loadAllRules().isEmpty());
        }
    }

    private static LevelOverride sampleOverride(String loggerName) {
        return new LevelOverride(loggerName, Level.DEBUG, null, Instant.now(), "jmx", PersistenceTier.STICKY, null);
    }

    private static HandlerLevelOverride sampleHandlerOverride(HandlerRef ref) {
        return HandlerLevelOverride.fixed(ref, Level.WARN, null, Instant.now(), "jmx", PersistenceTier.STICKY, null);
    }
}
