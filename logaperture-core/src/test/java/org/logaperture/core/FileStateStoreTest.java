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
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "com.acme.payments", Level.WARN, true, "known-noisy, muted for good",
                Instant.parse("2026-08-15T10:00:00Z"), "jmx", PersistenceTier.STICKY, null);
        LevelOverride timed = new LevelOverride(
                "com.acme.batch.Worker", Level.DEBUG, false, "investigating slot exhaustion",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.FOR,
                Instant.parse("2026-08-21T03:44:02Z"));

        try (FileStateStore store = FileStateStore.open()) {
            store.save(sticky);
            store.save(timed);
        }

        try (FileStateStore reopened = FileStateStore.open()) {
            List<LevelOverride> loaded = reopened.loadAll();
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
    void remove_isANoOpWhenTheLoggerWasNeverPersisted() throws IOException {
        try (FileStateStore store = FileStateStore.open()) {
            store.remove("com.acme.NeverThere"); // must not throw
            assertTrue(store.loadAll().isEmpty());
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

    private static LevelOverride sampleOverride(String loggerName) {
        return new LevelOverride(loggerName, Level.DEBUG, false, null, Instant.now(), "jmx", PersistenceTier.STICKY, null);
    }
}
