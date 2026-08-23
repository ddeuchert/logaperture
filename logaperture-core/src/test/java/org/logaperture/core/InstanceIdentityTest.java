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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure path/hash/slug logic — see doc/specs/persistence.md "Location and identity". */
class InstanceIdentityTest {

    private final String originalUserDir = System.getProperty("user.dir");

    @AfterEach
    void restoreProperties() {
        System.setProperty("user.dir", originalUserDir);
        System.clearProperty(InstanceIdentity.INSTANCE_ID_PROPERTY);
    }

    @Test
    void resolveIdentityString_noOverride_isTheCanonicalWorkingDirectory() {
        assertEquals(InstanceIdentity.canonicalWorkingDirectory().toString(), InstanceIdentity.resolveIdentityString());
    }

    @Test
    void resolveIdentityString_explicitInstanceId_takesPrecedenceOverPath() {
        System.setProperty(InstanceIdentity.INSTANCE_ID_PROPERTY, "my-fixed-id");

        assertEquals("my-fixed-id", InstanceIdentity.resolveIdentityString());
    }

    @Test
    void hash_sameIdentityString_alwaysYieldsTheSameHash() {
        assertEquals(InstanceIdentity.hash("/var/app"), InstanceIdentity.hash("/var/app"));
    }

    @Test
    void hash_differentIdentityStrings_yieldDifferentHashes() {
        assertNotEquals(InstanceIdentity.hash("/var/app-one"), InstanceIdentity.hash("/var/app-two"));
    }

    @Test
    void hash_isSixteenLowercaseHexCharacters() {
        String hash = InstanceIdentity.hash("/var/app");
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]{16}"));
    }

    @Test
    void sameCanonicalWorkingDirectory_reachedViaASymlink_yieldsTheSameHash(@TempDir Path tempDir) throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("real-app-dir"));
        Path link = tempDir.resolve("link-to-app");
        Files.createSymbolicLink(link, real);

        System.setProperty("user.dir", real.toString());
        String hashViaReal = InstanceIdentity.hash(InstanceIdentity.resolveIdentityString());

        System.setProperty("user.dir", link.toString());
        String hashViaSymlink = InstanceIdentity.hash(InstanceIdentity.resolveIdentityString());

        assertEquals(hashViaReal, hashViaSymlink);
    }

    @Test
    void differentWorkingDirectories_neverCollide(@TempDir Path tempDir) throws IOException {
        Path first = Files.createDirectory(tempDir.resolve("app-one"));
        Path second = Files.createDirectory(tempDir.resolve("app-two"));

        System.setProperty("user.dir", first.toString());
        String firstHash = InstanceIdentity.hash(InstanceIdentity.resolveIdentityString());

        System.setProperty("user.dir", second.toString());
        String secondHash = InstanceIdentity.hash(InstanceIdentity.resolveIdentityString());

        assertNotEquals(firstHash, secondHash);
    }

    @Test
    void slugOfCanonicalCwd_isLowercasedAndFilesystemSafe(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("My App!!"));
        System.setProperty("user.dir", dir.toString());

        String slug = InstanceIdentity.slugOfCanonicalCwd();

        assertTrue(slug.matches("[a-z0-9-]+"));
        assertTrue(slug.contains("my-app"));
    }

    @Test
    void slugOfCanonicalCwd_isNotAffectedByAnInstanceIdOverride(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("real-app-dir"));
        System.setProperty("user.dir", dir.toString());
        String slugWithoutOverride = InstanceIdentity.slugOfCanonicalCwd();

        System.setProperty(InstanceIdentity.INSTANCE_ID_PROPERTY, "totally-different-identity");

        assertEquals(slugWithoutOverride, InstanceIdentity.slugOfCanonicalCwd());
    }
}
