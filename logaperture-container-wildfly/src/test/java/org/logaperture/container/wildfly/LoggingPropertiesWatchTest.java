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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Long after every write, so contents are never compared. */
    private static final Clock LATER = Clock.fixed(Instant.parse("2026-10-18T11:00:00Z"), ZoneOffset.UTC);

    private Path write(String content, FileTime modified) throws Exception {
        Path file = Files.writeString(dir.resolve("logging.properties"), content);
        Files.setLastModifiedTime(file, modified);
        return file;
    }

    private LoggingPropertiesWatch watching(Path file, Clock clock) {
        return new LoggingPropertiesWatch(() -> file, clock);
    }

    @Test
    void firstSighting_onlyRecordsTheFile() throws Exception {
        Path file = write("a", T1);
        LoggingPropertiesWatch watch = watching(file, LATER);

        assertFalse(watch.changed());
        assertEquals(file, watch.file());
        assertFalse(watch.changed(), "nothing changed since");
    }

    @Test
    void aNewModificationTime_isAChange_reportedOnce() throws Exception {
        Path file = write("a", T1);
        LoggingPropertiesWatch watch = watching(file, LATER);
        watch.changed();

        write("a", T2);

        assertTrue(watch.changed());
        assertFalse(watch.changed());
    }

    @Test
    void aNewSizeWithTheSameModificationTime_isAChange() throws Exception {
        Path file = write("a", T1);
        LoggingPropertiesWatch watch = watching(file, LATER);
        watch.changed();

        write("a longer file", T1);

        assertTrue(watch.changed());
    }

    @Test
    void aSameLengthRewriteInTheSameTimestampTick_isAChange_whileTheFileIsRecent() throws Exception {
        Clock justAfterT1 = Clock.fixed(T1.toInstant().plusSeconds(1), ZoneOffset.UTC);
        Path file = write("level=INFO", T1);
        LoggingPropertiesWatch watch = watching(file, justAfterT1);
        watch.changed();

        write("level=WARN", T1);

        assertTrue(watch.changed());
        assertFalse(watch.changed());
    }

    @Test
    void aSameLengthRewriteWithTheSameTimestamp_isNotSeen_onceTheFileIsOld() throws Exception {
        Path file = write("level=INFO", T1);
        LoggingPropertiesWatch watch = watching(file, LATER);
        watch.changed();

        write("level=WARN", T1);

        assertFalse(watch.changed(), "contents are read only shortly after a write");
    }

    @Test
    void noFileYet_isNoChange_andTheFileIsPickedUpWhenItAppears() throws Exception {
        LoggingPropertiesWatch watch = watching(dir.resolve("logging.properties"), LATER);

        assertFalse(watch.changed());
        assertNull(watch.file());

        write("a", T1);
        assertFalse(watch.changed(), "first sighting only records it");
        write("b", T2);
        assertTrue(watch.changed());
    }

    @Test
    void anUnknownLocation_isNoChange_andIsAskedAgainOnTheNextCall() throws Exception {
        AtomicReference<Path> location = new AtomicReference<>();
        LoggingPropertiesWatch watch = new LoggingPropertiesWatch(location::get, LATER);

        assertFalse(watch.changed());

        location.set(write("a", T1));
        watch.changed();
        assertEquals(location.get(), watch.file());
    }

    @Test
    void aFileThatDisappears_isNoChange_andItsReturnIsOne() throws Exception {
        Path file = write("a", T1);
        LoggingPropertiesWatch watch = watching(file, LATER);
        watch.changed();

        Files.delete(file);
        assertFalse(watch.changed());

        write("b", T2);
        assertTrue(watch.changed());
    }
}
