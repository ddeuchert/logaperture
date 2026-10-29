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
package org.logaperture.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Windows process sources behind discovery (doc/specs/service-account-access.md). */
class HostProcessesTest {

    private static final String JAVA_ROWS = "\"java.exe\",\"6772\",\"RDP-Tcp#19\",\"2\",\"287,436 K\"\r\n"
            + "\"java.exe\",\"7100\",\"Services\",\"0\",\"7,656,664 K\"\r\n";

    @TempDir
    Path root;

    private static HostProcesses host(boolean windows, boolean handleVisible, HostProcesses.Runner runner, Path root) {
        return new HostProcesses(windows, pid -> handleVisible, runner, root, 99L);
    }

    // --- tasklist output ---------------------------------------------------------------------------

    @Test
    void parsesTasklistRows() {
        assertEquals(List.of(6772L, 7100L), HostProcesses.parseTasklist(JAVA_ROWS));
    }

    @Test
    void aNoTasksNoticeInAnyLanguageYieldsNothing() {
        assertEquals(List.of(), HostProcesses.parseTasklist("INFO: No tasks are running which match the specified criteria.\r\n"));
        assertEquals(List.of(), HostProcesses.parseTasklist("INFORMATION : aucune tâche en cours ne correspond aux critères.\r\n"));
        assertEquals(List.of(), HostProcesses.parseTasklist(""));
    }

    // --- does the process exist (#289) ----------------------------------------------------------------

    @Test
    void aVisibleHandleMeansItExists_withoutAskingTasklist() {
        List<String> ran = new ArrayList<>();
        HostProcesses host = host(true, true, c -> {
            ran.add(String.join(" ", c));
            return "";
        }, root);
        assertTrue(host.exists(7100));
        assertTrue(ran.isEmpty());
    }

    @Test
    void anEmptyHandleOnWindows_isCheckedAgainstTasklist() {
        HostProcesses host = host(true, false, c -> JAVA_ROWS, root);
        assertTrue(host.exists(7100), "tasklist lists it, so it is running although the JDK cannot open it");
        assertFalse(host.exists(31337));
    }

    @Test
    void anEmptyHandleOnWindows_whenTasklistCannotRun_isUnknown_soTheAttachDecides() {
        assertTrue(host(true, false, c -> null, root).exists(7100));
    }

    @Test
    void tasklistIsRunByItsFullPath_neverByName() {
        List<String> ran = new ArrayList<>();
        host(true, false, c -> {
            ran.add(c[0]);
            return "";
        }, root).exists(7100);
        assertEquals(root.resolve("System32").resolve("tasklist.exe").toString(), ran.get(0));
    }

    @Test
    void anEmptyHandleElsewhere_meansItDoesNotExist_andTasklistIsNeverRun() {
        HostProcesses host = host(false, false, c -> {
            throw new AssertionError("no command should run off Windows");
        }, root);
        assertFalse(host.exists(7100));
    }

    // --- other accounts' JVMs -------------------------------------------------------------------------

    @Test
    void offWindows_thereAreNoOtherAccountJvms() {
        assertTrue(host(false, true, c -> {
            throw new AssertionError("no command should run off Windows");
        }, root).otherAccountJvmPids().isEmpty());
    }

    private static final String ALL_ROWS = "\"System\",\"4\",\"Services\",\"0\",\"1 K\"\r\n"
            + "\"java.exe\",\"6772\",\"RDP-Tcp#19\",\"2\",\"287,436 K\"\r\n"
            + "\"java.exe\",\"7100\",\"Services\",\"0\",\"7,656,664 K\"\r\n"
            + "\"javaw.exe\",\"4100\",\"Services\",\"0\",\"100 K\"\r\n"
            + "\"prunsrv.exe\",\"8200\",\"Services\",\"0\",\"100 K\"\r\n"
            + "\"postgres.exe\",\"4242\",\"Services\",\"0\",\"100 K\"\r\n"
            + "\"java.exe\",\"99\",\"Console\",\"1\",\"1 K\"\r\n";

    @Test
    void onWindows_javaAndJavawRows_andPidFilesOfRunningProcesses_areMerged_withoutThisProcess() throws IOException {
        pidFile("System32/config/systemprofile/AppData/Local/Temp/hsperfdata_SYSTEM", "7100", "8200");
        pidFile("ServiceProfiles/NetworkService/AppData/Local/Temp/hsperfdata_NetworkService", "4");
        pidFile("Temp/hsperfdata_other", "99"); // this process: never a candidate
        List<String> ran = new ArrayList<>();
        TreeSet<Long> pids = host(true, true, c -> {
            ran.add(String.join(" ", c));
            return ALL_ROWS;
        }, root).otherAccountJvmPids();
        // 8200 is a wrapper hosting a JVM (found through its pid file), 4 is a running non-java process
        // that has a pid file, which is the residual risk the Javadoc names
        assertEquals(new TreeSet<>(List.of(4L, 4100L, 6772L, 7100L, 8200L)), pids);
        assertEquals(1, ran.size(), "one tasklist for everything");
    }

    @Test
    void aPidFileForAProcessTasklistDoesNotList_isIgnored() throws IOException {
        pidFile("System32/config/systemprofile/AppData/Local/Temp/hsperfdata_SYSTEM", "7100", "5555");
        TreeSet<Long> pids = host(true, true, c -> ALL_ROWS, root).otherAccountJvmPids();
        assertFalse(pids.contains(5555L), "a crashed JVM's leftover file must not be probed");
        assertTrue(pids.contains(7100L));
    }

    @Test
    void whenTasklistCannotRun_thePidFilesAreUsedAsTheyAre() throws IOException {
        pidFile("System32/config/systemprofile/AppData/Local/Temp/hsperfdata_SYSTEM", "7100");
        assertEquals(new TreeSet<>(List.of(7100L)), host(true, true, c -> null, root).otherAccountJvmPids());
    }

    @Test
    void pidFilesAreOnlyReadFromHsperfdataDirectories_andOnlyNumericNames() throws IOException {
        pidFile("Temp/hsperfdata_SYSTEM", "7100", "notapid");
        pidFile("Temp/unrelated", "5555");
        assertEquals(new TreeSet<>(List.of(7100L)), HostProcesses.hsperfdataPids(root));
    }

    @Test
    void missingProfileDirectoriesAreSkipped() {
        assertTrue(HostProcesses.hsperfdataPids(root.resolve("does-not-exist")).isEmpty());
    }

    private void pidFile(String directory, String... names) throws IOException {
        Path dir = root.resolve(directory);
        Files.createDirectories(dir);
        for (String name : names) {
            Files.createFile(dir.resolve(name));
        }
    }
}
