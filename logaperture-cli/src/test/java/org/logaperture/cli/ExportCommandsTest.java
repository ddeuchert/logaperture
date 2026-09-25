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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults-export.md "Testing" -- the {@code logctl export vendor-defaults} side. */
class ExportCommandsTest {

    private static final String FILE = """
            # Exported by logctl export vendor-defaults, 2026-09-25T14:02:11Z, agent 0.1.0-alpha.3
            # Started from: /opt/app/vendor-defaults.yaml
            schemaVersion: 1
            loggers:
              - name: com.acme.a
                level: WARN
              - name: com.acme.b
                level: DEBUG
            handlers:
              - name: FILE
                level: WARN
            defaultHandlers:
              - FILE
            rules:
              # was r1
              - id: drop-healthcheck
                action: drop
                logger: com.acme.HealthCheck
                messageContains: x
                below: ERROR
                sampleFull: 5m
            """;

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(capturedErr, true, StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    {
        mbean.exportVendorDefaultsResult = FILE;
    }

    private int run(String... argv) {
        return Parser.parse(argv).command().run(mbean, out, err, InputStream.nullInputStream(), false);
    }

    private String out() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return capturedErr.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutOut_printsTheFileAndNothingElse() {
        assertEquals(CliError.OK, run("export", "vendor-defaults"));

        assertEquals(FILE, out());
        assertEquals("", err());
    }

    @Test
    void withOut_writesTheFile_andSaysWhatItWrote() throws Exception {
        Path target = dir.resolve("vendor-defaults.yaml");

        assertEquals(CliError.OK, run("export", "vendor-defaults", "--out", target.toString()));

        assertEquals(FILE, Files.readString(target));
        assertEquals("Wrote 2 loggers, 1 handler, default handlers, 1 rule to " + target + ".", out().strip());
        try (var leftovers = Files.list(dir)) { // no temporary file left behind
            assertEquals(1, leftovers.count(), "no temporary file left behind");
        }
    }

    @Test
    void anExistingFile_isRefused_andLeftAlone_unlessForce() throws Exception {
        Path target = dir.resolve("vendor-defaults.yaml");
        Files.writeString(target, "keep me");

        CliError refused = assertThrows(CliError.class,
                () -> run("export", "vendor-defaults", "--out", target.toString()));

        assertEquals(CliError.UNEXPECTED, refused.exitCode());
        assertTrue(refused.getMessage().contains("--force"), refused.getMessage());
        assertEquals("keep me", Files.readString(target));
        assertEquals(0, mbean.exportVendorDefaultsCalls, "refused before asking the agent");

        assertEquals(CliError.OK, run("export", "vendor-defaults", "--out", target.toString(), "--force"));
        assertEquals(FILE, Files.readString(target));
    }

    @Test
    void theWrittenFile_isNotOwnerOnly_andAnOverwrittenOneKeepsItsPermissions() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path fresh = dir.resolve("fresh.yaml");
        run("export", "vendor-defaults", "--out", fresh.toString());
        Path probe = Files.createFile(dir.resolve("umask-probe"));
        assertEquals(Files.getPosixFilePermissions(probe), Files.getPosixFilePermissions(fresh),
                "the umask applies, as for any file the user creates -- not createTempFile's 0600");

        Path existing = dir.resolve("existing.yaml");
        Files.writeString(existing, "old");
        var shared = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(existing, shared);
        run("export", "vendor-defaults", "--out", existing.toString(), "--force");
        assertEquals(shared, Files.getPosixFilePermissions(existing));
    }

    @Test
    void anUnwritableDirectory_failsWithoutAPartialFile() {
        Path target = dir.resolve("missing-dir/vendor-defaults.yaml");

        CliError error = assertThrows(CliError.class, () -> run("export", "vendor-defaults", "--out", target.toString()));

        assertEquals(CliError.UNEXPECTED, error.exitCode());
        assertTrue(error.getMessage().startsWith("Can't write " + target), error.getMessage());
        assertTrue(Files.notExists(target));
    }

    @Test
    void nothingToExport_isANoteOnStderr_andStillExit0() {
        mbean.exportVendorDefaultsResult = "# Exported by …\n# Started from: no vendor defaults file\nschemaVersion: 1\n";

        assertEquals(CliError.OK, run("export", "vendor-defaults"));

        assertTrue(out().endsWith("schemaVersion: 1\n"), out());
        assertEquals("Nothing to export: no vendor defaults file and no sticky settings.", err().strip());
    }

    @Test
    void usageErrors() {
        for (String[] argv : new String[][] {
                {"export"},
                {"export", "loggers"},
                {"export", "vendor-defaults", "extra"},
                {"export", "vendor-defaults", "--force"},
                {"export", "vendor-defaults", "--json"},
                {"export", "vendor-defaults", "--out"},
                {"list", "rules", "--out", "x.yaml"},
                {"status", "--force"}}) {
            CliError error = assertThrows(CliError.class, () -> Parser.parse(argv), String.join(" ", argv));
            assertEquals(CliError.USAGE, error.exitCode(), String.join(" ", argv));
        }
    }

    @Test
    void summary_countsEachSection() {
        assertEquals("2 loggers, 1 handler, default handlers, 1 rule", Commands.exportSummary(FILE));
        assertEquals("", Commands.exportSummary("# header\nschemaVersion: 1\n"));
    }
}
