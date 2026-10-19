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
package org.logaperture.container.wildfly;

import org.logaperture.bridge.Diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * Tells when WildFly's logging subsystem has committed a change (issue #232).
 *
 * <p>JBoss LogManager's configuration listeners never fire for a {@code /subsystem=logging}
 * change or a {@code :reload}: the subsystem doesn't go through {@code readConfiguration}. It
 * commits the change straight into the log context and then writes {@code logging.properties}
 * ({@code ConfigurationPersistence.writeConfiguration}, which commits before it opens the file).
 * So a rewritten {@code logging.properties} means the log context already holds the change.
 * Measured on WildFly 26.1.3: every logger, root-logger and handler change rewrites it, and so
 * does {@code :reload}; a read-only operation doesn't.
 *
 * <p>Two cases write nothing, and the periodic verification sweep stays the only re-apply there:
 * a server started with {@code -Djboss.server.persist.config=false}, and a change to a logging
 * profile.
 */
final class LoggingPropertiesWatch {

    /**
     * How long after its modification time the file's contents are compared too. A filesystem
     * with 1 s or 2 s timestamps (ext3, HFS+, FAT, some NFS) gives two same-length rewrites in one
     * tick the same modification time and size; this covers the coarsest tick plus a 1 s check
     * interval, with a second's slack, and the file is read only during it.
     */
    private static final Duration RECENTLY_WRITTEN = Duration.ofSeconds(4);

    private final Supplier<Path> location;
    private final Clock clock;
    private Path file;
    private Stamp last;

    /** {@code location} is asked on each call, and may return null while the file isn't known yet. */
    LoggingPropertiesWatch(Supplier<Path> location, Clock clock) {
        this.location = Objects.requireNonNull(location, "location");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The file a standalone server writes, {@code ${jboss.server.config.dir}/logging.properties}.
     * The property is read on each call, since the agent starts before WildFly sets it. (A
     * managed domain server writes under {@code jboss.server.data.dir}, but the agent declines
     * domain mode.)
     */
    static LoggingPropertiesWatch forThisServer() {
        Supplier<Path> configDirectoryFile = () -> {
            String directory = System.getProperty("jboss.server.config.dir");
            return directory == null ? null : Path.of(directory, "logging.properties");
        };
        return new LoggingPropertiesWatch(configDirectoryFile, Clock.systemUTC());
    }

    /**
     * Whether the file has been rewritten since the last call. The first time the file is seen
     * only records it, and a missing or unreadable file never counts as a change. Called from the
     * sweep thread only.
     */
    boolean changed() {
        Path current = location.get();
        if (current == null || !Files.isRegularFile(current)) {
            return false;
        }
        if (!current.equals(file)) {
            file = current;
            last = null;
            Diagnostics.debug("watching " + file + " for logging subsystem changes");
        }
        Stamp before = last;
        Stamp now = Stamp.of(file, clock);
        if (now == null) {
            return false;
        }
        last = now;
        return before != null && !before.sameAs(now);
    }

    /** The file being watched, once found. */
    Path file() {
        return file;
    }

    /** {@code checksum} is set only while the file is recently written; otherwise null. */
    private record Stamp(FileTime modified, long size, Long checksum) {

        static Stamp of(Path file, Clock clock) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                FileTime modified = attributes.lastModifiedTime();
                boolean recent = clock.instant().isBefore(modified.toInstant().plus(RECENTLY_WRITTEN));
                Long checksum = recent ? checksum(file) : null;
                return new Stamp(modified, attributes.size(), checksum);
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        boolean sameAs(Stamp other) {
            if (!modified.equals(other.modified) || size != other.size) {
                return false;
            }
            return checksum == null || other.checksum == null || checksum.equals(other.checksum);
        }

        private static long checksum(Path file) throws IOException {
            CRC32 crc = new CRC32();
            crc.update(Files.readAllBytes(file));
            return crc.getValue();
        }
    }
}
