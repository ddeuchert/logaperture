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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Remembers recently set logger names in {@code ~/.logaperture/recent-loggers},
 * one per line, most recent first. Max 10 entries; duplicates are deduplicated
 * on save.
 */
final class RecentLoggers {

    private static final int MAX = 50;

    private RecentLoggers() {
    }

    static Path file() {
        return Path.of(System.getProperty("user.home"), ".logaperture", "recent-loggers");
    }

    /** Reads recent loggers, most recent first. Never throws. */
    static List<String> read() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Records {@code name} as the most recently set logger. Never throws. */
    static void record(String name) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            entries.add(name);
            if (Files.isRegularFile(path)) {
                entries.addAll(Files.readAllLines(path));
            }
            List<String> kept = new ArrayList<>(entries);
            if (kept.size() > MAX) {
                kept = kept.subList(0, MAX);
            }
            Files.write(path, kept, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // best effort
        }
    }
}