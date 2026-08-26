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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Derives a stable per-JVM identity for {@link FileStateStore}'s file name
 * — doc/specs/persistence.md "Location and identity". Pure functions, no
 * I/O beyond resolving the working directory's real path, so this is
 * testable without touching a lock file or the filesystem's home directory.
 */
final class InstanceIdentity {

    /** Explicit override for the one case the default can't disambiguate on its own — §6.4. */
    static final String INSTANCE_ID_PROPERTY = "logaperture.instanceId";

    private InstanceIdentity() {
    }

    /**
     * The {@code logaperture.instanceId} system property if set; otherwise
     * this JVM's canonical working directory.
     */
    static String resolveIdentityString() {
        String explicit = System.getProperty(INSTANCE_ID_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return canonicalWorkingDirectory().toString();
    }

    /**
     * Resolves symlinks and normalizes case (matters on Windows) so the
     * same real location always hashes the same way even if it was reached
     * via a different-looking path.
     */
    static Path canonicalWorkingDirectory() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        try {
            return cwd.toRealPath();
        } catch (IOException e) {
            // The working directory can't have vanished while this JVM is
            // running it -- but stay fail-open and fall back to the
            // unresolved absolute path rather than propagating.
            return cwd.toAbsolutePath();
        }
    }

    /**
     * The first 16 hex characters (64 bits — ample for disambiguating
     * identifiers on one machine, not a security boundary) of {@code
     * SHA-256(identityString)}.
     */
    static String hash(String identityString) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(identityString.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hashBytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * A filesystem-safe, human-readable tag for browsing the instances
     * directory by hand -- not part of the identity computation, always
     * derived from the canonical working directory regardless of whether
     * {@link #INSTANCE_ID_PROPERTY} overrides the identity itself.
     */
    static String slugOfCanonicalCwd() {
        Path fileName = canonicalWorkingDirectory().getFileName();
        String last = fileName != null ? fileName.toString() : canonicalWorkingDirectory().toString();
        String cleaned = last.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
        if (cleaned.isEmpty()) {
            cleaned = "root";
        }
        return cleaned.substring(0, Math.min(cleaned.length(), 40));
    }
}
