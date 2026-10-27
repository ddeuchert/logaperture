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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * A directory under a shared location such as {@code /tmp} that only this user may write
 * (doc/specs/logctl-attach.md #8, and the review of #282). The fallback state directory and
 * {@code logctl attach}'s fallback copy of the agent jar both live there when the user's home cannot
 * be used, and a predictable name in a world-writable directory is something another local user can
 * create first: for the state files that would mean writing them into a directory that user controls,
 * and for the agent jar it would mean loading a jar that user wrote.
 *
 * <p>Every directory from just below the shared root down to the target is created with owner-only
 * permissions, or, if it already exists, must be a real directory (not a symbolic link) owned by the
 * current user with no group or other write permission. Where the file system has no POSIX
 * permissions (Windows, whose temp directory is per user) nothing more than creating the directory is
 * done. A directory that is not under the shared root is the user's own and is only created.
 */
public final class PrivateDirectory {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OTHERS_WRITE = Set.of(PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);

    private PrivateDirectory() {
    }

    /**
     * Creates {@code dir} if needed and checks it as described above.
     *
     * @param sharedRoot the shared directory {@code dir} may sit under, such as {@code java.io.tmpdir}
     * @throws IOException if a directory below the root is not safe to use, or cannot be created
     */
    public static void ensure(Path dir, Path sharedRoot) throws IOException {
        Path target = dir.toAbsolutePath().normalize();
        Path root = sharedRoot.toAbsolutePath().normalize();
        boolean shared = target.startsWith(root) && !target.equals(root);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!shared || !posix) {
            Files.createDirectories(target);
            return;
        }

        Deque<Path> chain = new ArrayDeque<>(); // from just below the root down to the target
        for (Path p = target; !p.equals(root); p = p.getParent()) {
            chain.addFirst(p);
        }
        for (Path p : chain) {
            if (Files.notExists(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(p, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            }
            requirePrivate(p);
        }
    }

    private static void requirePrivate(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(dir + " is not a plain directory");
        }
        String owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).getName();
        String me = System.getProperty("user.name");
        if (!owner.equals(me)) {
            throw new IOException(dir + " is owned by " + owner + ", not " + me);
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
        for (PosixFilePermission write : OTHERS_WRITE) {
            if (permissions.contains(write)) {
                throw new IOException(dir + " can be written by other users (" + PosixFilePermissions.toString(permissions)
                        + ")");
            }
        }
    }
}
