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

import org.logaperture.api.LevelOverride;
import org.logaperture.core.spi.StateStore;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one {@link StateStore} implementation this slice ships — a single
 * hand-editable YAML file per JVM instance, per doc/specs/persistence.md
 * "State store". {@code core} has no dependency on {@code
 * logaperture-bridge} (see this module's pom, and {@link StderrAuditLog}'s
 * own note on the same constraint), so I/O failures are reported directly
 * to {@code System.err} rather than through {@code Diagnostics} -- the same
 * narrow, one-off exception to that module boundary.
 *
 * <p>Holds the whole persisted set in memory, keyed by logger name, built
 * once from disk at {@link #open()} and kept in sync with every {@link
 * #save}/{@link #remove}/{@link #clear} call -- this instance is the sole
 * writer of its file for the life of the JVM (that exclusivity is exactly
 * what {@link #open()}'s file lock enforces), so there is no concurrent
 * writer to reconcile with between calls.
 */
public final class FileStateStore implements StateStore, Closeable {

    private static final String HOME_PROPERTY = "logaperture.home";

    private final Path stateFile;
    private final FileLock lock;
    private final Map<String, LevelOverride> cache;

    private FileStateStore(Path stateFile, FileLock lock, Map<String, LevelOverride> initial) {
        this.stateFile = stateFile;
        this.lock = lock;
        this.cache = new LinkedHashMap<>(initial);
    }

    /**
     * Resolves this JVM's state file under {@code ${logaperture.home}/instances/}
     * (home defaulting to {@code ${user.home}/.logaperture/}) and acquires
     * its instance lock.
     *
     * @throws InstanceLockedException if another live process already holds
     *                                 this identity's lock — doc/specs/
     *                                 persistence.md "The same-working-directory
     *                                 collision"
     * @throws IOException             on any other failure to create the
     *                                 instances directory or open the lock file
     */
    public static FileStateStore open() throws IOException {
        Path instancesDir = resolveHome().resolve("instances");
        Files.createDirectories(instancesDir);

        String baseName = InstanceIdentity.hash(InstanceIdentity.resolveIdentityString())
                + "-" + InstanceIdentity.slugOfCanonicalCwd();
        Path stateFile = instancesDir.resolve(baseName + ".state.yaml");
        Path lockFile = instancesDir.resolve(baseName + ".lock");

        FileLock lock = acquireLock(lockFile);
        writeOwnPid(lock);

        return new FileStateStore(stateFile, lock, readExisting(stateFile));
    }

    @Override
    public synchronized List<LevelOverride> loadAll() {
        return List.copyOf(cache.values());
    }

    @Override
    public synchronized void save(LevelOverride override) {
        cache.put(override.loggerName(), override);
        persist();
    }

    @Override
    public synchronized void remove(String loggerName) {
        if (cache.remove(loggerName) != null) {
            persist();
        }
    }

    @Override
    public synchronized void clear() {
        if (!cache.isEmpty()) {
            cache.clear();
            persist();
        }
    }

    /** Releases the instance lock. Production never calls this (the OS releases it at process exit); tests do. */
    @Override
    public void close() throws IOException {
        lock.release();
        lock.channel().close();
    }

    private void persist() {
        try {
            String content = StateFileFormat.write(List.copyOf(cache.values()));
            Path tmp = Files.createTempFile(stateFile.getParent(), stateFile.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try (FileChannel fsync = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    fsync.force(true);
                }
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp); // no-op once the move above has succeeded
            }
        } catch (IOException e) {
            // One attempt, one diagnostic line, move on -- doc/specs/persistence.md
            // "Failure handling": the in-memory mutation this call followed
            // already succeeded, so degrading silently to session-only
            // behavior here is a safe direction to fail in.
            System.err.println("[logaperture-state] failed to persist state file '" + stateFile
                    + "', this change will not survive a restart: " + e);
        }
    }

    private static Map<String, LevelOverride> readExisting(Path stateFile) {
        if (!Files.exists(stateFile)) {
            return Map.of();
        }
        try {
            Map<String, LevelOverride> loaded = new LinkedHashMap<>();
            for (LevelOverride override : StateFileFormat.parse(Files.readString(stateFile, StandardCharsets.UTF_8))) {
                loaded.put(override.loggerName(), override);
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            // A JVM that can't read its own state starts clean rather than
            // refusing to start -- fail-open, per doc/logaperture-spec.md §9.
            System.err.println("[logaperture-state] failed to load state file '" + stateFile
                    + "', resuming with nothing persisted: " + e);
            return Map.of();
        }
    }

    private static Path resolveHome() {
        String explicit = System.getProperty(HOME_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        return Path.of(System.getProperty("user.home"), ".logaperture");
    }

    private static FileLock acquireLock(Path lockFile) throws IOException {
        FileLock lock = tryLockOnce(lockFile);
        if (lock != null) {
            return lock;
        }
        long holderPid = readPidBestEffort(lockFile);
        boolean stale = holderPid > 0 && ProcessHandle.of(holderPid).isEmpty();
        if (stale) {
            // The recorded holder is gone -- an abnormal exit that somehow
            // didn't release the OS lock (rare, but possible on some
            // network filesystems). Recreate the file and retry once.
            Files.deleteIfExists(lockFile);
            lock = tryLockOnce(lockFile);
            if (lock != null) {
                return lock;
            }
            holderPid = readPidBestEffort(lockFile);
        }
        throw new InstanceLockedException(holderPid);
    }

    private static FileLock tryLockOnce(Path lockFile) throws IOException {
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            // A second collision candidate: the JVM itself (not just the OS)
            // tracks locks per file, so a *second* instance racing for the
            // same identity from within this same process throws here
            // rather than returning null -- doc/specs/persistence.md's own
            // "two install() calls against the same identity in one test
            // process" case.
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return lock;
    }

    private static long readPidBestEffort(Path lockFile) {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(64);
            int read = channel.read(buffer);
            if (read <= 0) {
                return -1;
            }
            return Long.parseLong(new String(buffer.array(), 0, read, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static void writeOwnPid(FileLock lock) throws IOException {
        FileChannel channel = lock.channel();
        channel.truncate(0);
        channel.position(0);
        channel.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
    }

    /**
     * Thrown by {@link #open()} when this identity's lock is held by
     * another live process — doc/specs/persistence.md "The same-working-
     * directory collision". The composition root's response is to degrade
     * to {@link StateStore#noOp()} for this JVM's entire lifetime and log a
     * diagnostic naming {@link InstanceIdentity#INSTANCE_ID_PROPERTY} as
     * the fix; this is not itself an error condition, so it deliberately
     * doesn't extend {@link RuntimeException}.
     */
    public static final class InstanceLockedException extends IOException {

        private final long holderPid;

        InstanceLockedException(long holderPid) {
            super("this JVM's instance identity is already locked by live process pid=" + holderPid
                    + " -- set -D" + InstanceIdentity.INSTANCE_ID_PROPERTY + "=<unique-id> to disambiguate");
            this.holderPid = holderPid;
        }

        public long holderPid() {
            return holderPid;
        }
    }
}
