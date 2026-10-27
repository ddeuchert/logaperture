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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The fallback directories under a shared temp directory are ours alone (review of #282). */
class PrivateDirectoryTest {

    @TempDir
    Path shared;

    private static void requirePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
    }

    @Test
    void createsEveryMissingLevelOwnerOnly() throws IOException {
        requirePosix();
        Path dir = shared.resolve("logaperture-me").resolve("agent");

        PrivateDirectory.ensure(dir, shared);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParent())));
    }

    @Test
    void anExistingPrivateDirectoryIsAccepted() throws IOException {
        requirePosix();
        Path dir = shared.resolve("logaperture-me");
        PrivateDirectory.ensure(dir, shared);

        PrivateDirectory.ensure(dir, shared); // idempotent
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void aDirectoryOtherUsersCanWriteIsRefused() throws IOException {
        requirePosix();
        Path dir = Files.createDirectory(shared.resolve("logaperture-me"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx")); // whatever the umask did

        assertThrows(IOException.class, () -> PrivateDirectory.ensure(dir.resolve("agent"), shared));
    }

    @Test
    void aGroupWritableDirectoryIsRefused() throws IOException {
        requirePosix();
        Path dir = Files.createDirectory(shared.resolve("logaperture-me"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"));

        assertThrows(IOException.class, () -> PrivateDirectory.ensure(dir, shared));
    }

    @Test
    void aSymbolicLinkIsRefused() throws IOException {
        requirePosix();
        Path elsewhere = Files.createTempDirectory("elsewhere");
        try {
            Files.createSymbolicLink(shared.resolve("logaperture-me"), elsewhere);

            assertThrows(IOException.class, () -> PrivateDirectory.ensure(shared.resolve("logaperture-me"), shared));
        } finally {
            Files.deleteIfExists(shared.resolve("logaperture-me"));
            Files.deleteIfExists(elsewhere);
        }
    }

    @Test
    void aDirectoryOutsideTheSharedRootIsOnlyCreated(@TempDir Path somewhereElse) throws IOException {
        Path dir = somewhereElse.resolve("home").resolve("agent");
        Files.createDirectory(somewhereElse.resolve("home"));
        requirePosix();
        Files.setPosixFilePermissions(somewhereElse.resolve("home"), PosixFilePermissions.fromString("rwxrwxr-x"));

        PrivateDirectory.ensure(dir, shared); // the user's own home may be group writable (umask 002)

        assertTrue(Files.isDirectory(dir));
    }
}
