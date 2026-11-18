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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-letter answers at the {@code Level?} question (doc/specs/easier-logger-entry.md E5). */
class QuestionsLevelTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    private String ask(boolean autoAllowed, String... answers) {
        Prompter prompter = new Prompter(
                new ByteArrayInputStream((String.join("\n", answers) + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8));
        return Questions.askLevel(prompter, "Level?", autoAllowed);
    }

    @Test
    void eachLetterExpandsToItsLevel_inEitherCase() {
        assertEquals("INFO", ask(false, "i"));
        assertEquals("DEBUG", ask(false, "d"));
        assertEquals("TRACE", ask(false, "T"));
        assertEquals("WARN", ask(false, "w"));
        assertEquals("ERROR", ask(false, "E"));
        assertEquals("OFF", ask(false, "o"));
    }

    @Test
    void aIsNotAShortcut_soItCannotBeMistakenForAuto() {
        assertEquals("INFO", ask(true, "a", "i"));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).length() > 0, "the bad answer was explained");
    }

    @Test
    void fullNamesAndAutoStillWork() {
        assertEquals("DEBUG", ask(false, "debug"));
        assertEquals("AUTO", ask(true, "auto"));
    }

    @Test
    void anUnknownLetter_isExplainedAndAskedAgain() {
        assertEquals("INFO", ask(false, "z", "i"));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).length() > 0, "the bad answer was explained");
    }

    @Test
    void autoIsRefusedWhereItIsNotAllowed() {
        assertEquals("OFF", ask(false, "auto", "o"));
    }
}
