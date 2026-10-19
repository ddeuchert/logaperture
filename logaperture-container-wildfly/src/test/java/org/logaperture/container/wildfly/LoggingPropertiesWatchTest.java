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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #232: a rewritten {@code logging.properties} is the signal a logging change was committed. */
class LoggingPropertiesWatchTest {

    @TempDir
    private Path dir;

    private static final FileTime T1 = FileTime.from(Instant.parse("2026-10-18T10:00:00Z"));
    private static final FileTime T2 = FileTime.from(Instant.parse("2026-10-18T10:00:05Z"));

    private Path write(String name, String content, FileTime modified) throws Exception {
        Path file = Files.writeString(dir.resolve(name), content);
        Files.setLastModifiedTime(file, modified);
        return file;
    }

    @Test
    void firstSighting_onlyRecordsTheFile() throws Exception {
        Path file = write("logging.properties", "a", T1);
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(() -> List.of(file));

        assertFalse(watch.changed());
        assertEquals(file, watch.file());
        assertFalse(watch.changed(), "nothing changed since");
    }

    @Test
    void aNewModificationTime_isAChange_reportedOnce() throws Exception {
        Path file = write("logging.properties", "a", T1);
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(() -> List.of(file));
        watch.changed();

        write("logging.properties", "a", T2);

        assertTrue(watch.changed());
        assertFalse(watch.changed());
    }

    @Test
    void aNewSizeWithTheSameModificationTime_isAChange() throws Exception {
        Path file = write("logging.properties", "a", T1);
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(() -> List.of(file));
        watch.changed();

        write("logging.properties", "a longer file", T1);

        assertTrue(watch.changed());
    }

    @Test
    void noFileYet_isNoChange_andTheFileIsPickedUpWhenItAppears() throws Exception {
        Path missing = dir.resolve("logging.properties");
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(() -> List.of(missing));

        assertFalse(watch.changed());
        assertNull(watch.file());

        write("logging.properties", "a", T1);
        assertFalse(watch.changed(), "first sighting only records it");
        write("logging.properties", "b", T2);
        assertTrue(watch.changed());
    }

    @Test
    void theFirstCandidateThatExists_isTheOneWatched() throws Exception {
        Path configDir = dir.resolve("configuration").resolve("logging.properties");
        Path dataDir = write("logging.properties", "a", T1);
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(() -> List.of(configDir, dataDir));

        watch.changed();

        assertEquals(dataDir, watch.file());
    }

    @Test
    void aFileThatDisappears_isNoChange_andItsReturnIsOne() throws Exception {
        Path file = write("logging.properties", "a", T1);
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(() -> List.of(file));
        watch.changed();

        Files.delete(file);
        assertFalse(watch.changed());

        write("logging.properties", "b", T2);
        assertTrue(watch.changed());
    }
}
