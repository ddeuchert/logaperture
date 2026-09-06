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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatTest {

    @Test
    void tableColumnsAlignOnTheWidestCell() {
        List<String> lines = Format.table(
                List.of("LOGGER", "LEVEL"),
                List.of(
                        List.of("com.acme.batch.Worker", "DEBUG"),
                        List.of("a", "INFO"))).lines().toList();

        assertEquals(3, lines.size());
        int secondColumn = lines.get(0).indexOf("LEVEL");
        assertEquals(secondColumn, lines.get(1).indexOf("DEBUG"));
        assertEquals(secondColumn, lines.get(2).indexOf("INFO"));
        assertTrue(lines.get(1).startsWith("com.acme.batch.Worker "));
        assertTrue(lines.get(2).startsWith("a "));
    }

    @Test
    void tableTrimsTrailingPaddingOnEveryLine() {
        String rendered = Format.table(List.of("A", "B"), List.of(List.of("xxxx", "y")));
        for (String line : rendered.split("\n")) {
            assertEquals(line.stripTrailing(), line, "line has trailing whitespace: <" + line + ">");
        }
    }

    @Test
    void relativeTimeIsCoarseAndCapsAtTwoComponents() {
        assertEquals("in 27m", Format.relative(Duration.ofMinutes(27)));
        assertEquals("in 3h 59m", Format.relative(Duration.ofMinutes(239)));
        assertEquals("in 8s", Format.relative(Duration.ofSeconds(8)));
        assertEquals("in 4h", Format.relative(Duration.ofHours(4)));
        assertEquals("in 1d 6h", Format.relative(Duration.ofHours(30)));
        assertEquals("now", Format.relative(Duration.ofSeconds(-5)));
    }

    // --- top (doc/specs/top.md) ------------------------------------------------------------------

    @Test
    void bytesScalesToTheNearestSensibleUnit() {
        assertEquals("412 MB", Format.bytes(412.0 * 1024 * 1024));
        assertEquals("9.6 GB", Format.bytes(9.6 * 1024 * 1024 * 1024));
        assertEquals("0 MB", Format.bytes(0));
    }

    @Test
    void elapsedIsCoarseLikeRelativeButWithoutTheInPrefix() {
        assertEquals("6h 12m", Format.elapsed(Duration.ofMinutes(372)));
        assertEquals("27m", Format.elapsed(Duration.ofMinutes(27)));
        assertEquals("under a minute", Format.elapsed(Duration.ofSeconds(8)));
        assertEquals("4h", Format.elapsed(Duration.ofHours(4)));
    }

    @Test
    void paddedLeftAlignsToTheGivenWidth() {
        assertEquals("abc  ", Format.padded("abc", 5));
        assertEquals("abcde", Format.padded("abcde", 5));
        assertEquals("abcdef", Format.padded("abcdef", 5), "never truncates -- just doesn't pad further");
    }
}
