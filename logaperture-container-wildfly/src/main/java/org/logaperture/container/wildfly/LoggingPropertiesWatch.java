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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

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

    private static final String FILE_NAME = "logging.properties";

    private final Supplier<List<Path>> candidates;
    private Path file;
    private Stamp last;

    /** {@code candidates} is asked on each call until one of its paths exists. */
    LoggingPropertiesWatch(Supplier<List<Path>> candidates) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    /**
     * The file a standalone server writes ({@code jboss.server.config.dir}), then a managed domain
     * server's ({@code jboss.server.data.dir}). Read on each call, since the agent starts before
     * WildFly sets these properties.
     */
    static LoggingPropertiesWatch forThisServer() {
        Supplier<List<Path>> serverDirectories = () -> {
            List<Path> paths = new ArrayList<>(2);
            for (String property : List.of("jboss.server.config.dir", "jboss.server.data.dir")) {
                String directory = System.getProperty(property);
                if (directory != null) {
                    paths.add(Path.of(directory, FILE_NAME));
                }
            }
            return paths;
        };
        return new LoggingPropertiesWatch(serverDirectories);
    }

    /**
     * Whether the file has been rewritten since the last call. The first time the file is seen
     * only records it, and a missing or unreadable file never counts as a change. Called from the
     * sweep thread only.
     */
    boolean changed() {
        if (file == null) {
            file = candidates.get().stream().filter(Files::isRegularFile).findFirst().orElse(null);
            if (file == null) {
                return false;
            }
        }
        Stamp now = Stamp.of(file);
        if (now == null) {
            return false;
        }
        Stamp before = last;
        last = now;
        return before != null && !before.equals(now);
    }

    /** The file being watched, once found. */
    Path file() {
        return file;
    }

    private record Stamp(FileTime modified, long size) {

        static Stamp of(Path file) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                return new Stamp(attributes.lastModifiedTime(), attributes.size());
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
    }
}
