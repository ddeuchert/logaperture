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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ASCII stand-ins on a console that can't show {@code — – → …} (issue #190). */
class GlyphsTest {

    @AfterEach
    void unicodeAgain() {
        Glyphs.useAscii(false);
    }

    @Test
    void plain_swapsEachCharacterForItsStandIn() {
        assertEquals("- a-b x -> y ...", Glyphs.plain("— a–b x → y …"));
        assertEquals("café", Glyphs.plain("café"), "other non-ASCII text is left as it is");
    }

    @Test
    void canEncode_falseForAWindowsCodePage_trueForUtf8() {
        assertFalse(Glyphs.canEncode(Charset.forName("IBM437")));
        assertFalse(Glyphs.canEncode(StandardCharsets.US_ASCII));
        assertTrue(Glyphs.canEncode(StandardCharsets.UTF_8));
    }

    @Test
    void forStream_aUtf8Stream_isLeftAlone() {
        PrintStream utf8 = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertSame(utf8, Glyphs.forStream(utf8, StandardCharsets.UTF_8));
    }

    @Test
    void forStream_aCodePageStream_printsStandInsNotQuestionMarks() {
        Charset cp437 = Charset.forName("IBM437");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream console = Glyphs.forStream(new PrintStream(bytes, true, cp437), cp437);

        console.println("DEFAULT_HANDLERS → CONSOLE");
        console.print('—');
        console.println();
        console.printf("%s…%n", "more");
        console.println((Object) "a–b");
        console.print("café ");
        console.flush();

        String nl = System.lineSeparator();
        assertEquals("DEFAULT_HANDLERS -> CONSOLE" + nl + "-" + nl + "more..." + nl + "a-b" + nl + "café ",
                bytes.toString(cp437));
    }

    @Test
    void table_staysAlignedWhenAStandInIsWider() {
        Glyphs.useAscii(true);

        String table = Format.table(List.of("HANDLER", "LEVEL", "OVERRIDE"), List.of(
                List.of("CONSOLE", "INFO", "AUTO → DEBUG"),
                List.of("FILE", Format.NONE, Format.NONE)));

        assertEquals("""
                HANDLER  LEVEL  OVERRIDE
                CONSOLE  INFO   AUTO -> DEBUG
                FILE     -      -""", table);
    }
}
