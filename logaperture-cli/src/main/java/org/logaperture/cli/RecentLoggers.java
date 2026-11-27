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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Remembers recently set logger names in {@code ~/.logaperture/recent-loggers},
 * one per line, most recent first. Max 50 entries; duplicates are deduplicated
 * on save, and blank lines are ignored on read.
 */
final class RecentLoggers {

    private static final int MAX = 50;

    private RecentLoggers() {
    }

    /** {@code ${logaperture.home}/recent-loggers}, home defaulting to {@code ~/.logaperture} as the agent's state does. */
    static Path file() {
        String configured = System.getProperty("logaperture.home");
        Path home = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".logaperture");
        return home.resolve("recent-loggers");
    }

    /** Reads recent loggers, most recent first, without blanks or repeats, at most 50. Never throws. */
    static List<String> read() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return clean(Files.readAllLines(path));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Records {@code name} as the most recently set logger. Never throws. */
    static void record(String name) {
        recordAll(name == null ? List.of() : List.of(name));
    }

    /**
     * Records {@code names} in one read-modify-write: they become the most recent entries, in the order
     * given (the first is listed first), ahead of the existing history. Patterns and blanks are skipped.
     * Never throws.
     */
    static void recordAll(List<String> names) {
        List<String> fresh = new ArrayList<>();
        for (String name : names) {
            // A pattern is not a logger: listed back later it would be taken as an exact name.
            if (name != null && !name.isBlank() && name.indexOf('*') < 0) {
                fresh.add(name.strip());
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            List<String> merged = new ArrayList<>(fresh);
            if (Files.isRegularFile(path)) {
                merged.addAll(Files.readAllLines(path));
            }
            // Written beside the history and moved over it, so an interrupted write never leaves it
            // empty or half-written. Two logctl runs at once can still lose one's update; the history is
            // a convenience, not a record.
            Path temp = Files.createTempFile(path.getParent(), "recent-loggers", ".tmp");
            try {
                Files.write(temp, clean(merged));
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    private static List<String> clean(List<String> lines) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : lines) {
            String name = line.strip();
            if (!name.isEmpty() && name.indexOf('*') < 0) {
                unique.add(name);
            }
        }
        List<String> kept = new ArrayList<>(unique);
        return kept.size() > MAX ? new ArrayList<>(kept.subList(0, MAX)) : kept;
    }
}
