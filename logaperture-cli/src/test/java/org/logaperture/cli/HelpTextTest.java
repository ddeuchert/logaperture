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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The help topics and how they are found and printed (doc/specs/user-documentation.md, slice 2, H1–H5). */
class HelpTextTest {

    // --- H1: the topics ---------------------------------------------------------

    @Test
    void everySynopsisBelongsToExactlyOneTopic() {
        Set<String> seen = new HashSet<>();
        for (HelpTopic topic : HelpTopics.ALL) {
            assertFalse(topic.synopses().isEmpty(), "topic '" + topic.name() + "' has no synopsis");
            for (String synopsis : topic.synopses()) {
                assertTrue(synopsis.startsWith("logctl "), synopsis);
                assertTrue(seen.add(synopsis), "synopsis in two topics: " + synopsis);
            }
        }
        assertEquals(seen.size(), HelpText.SYNOPSES.size());
    }

    @Test
    void topicNamesAndAnchorsAreUnique() {
        Set<String> names = new HashSet<>();
        for (HelpTopic topic : HelpTopics.ALL) {
            assertTrue(names.add(topic.anchor()), "duplicate topic " + topic.name());
        }
    }

    /** Drift guard: an option a synopsis shows is explained in that topic or among the global ones. */
    @Test
    void everyOptionInASynopsisIsDocumented() {
        Pattern optionInSynopsis = Pattern.compile("--[a-z-]+");
        Set<String> global = flags(HelpTopics.GLOBAL_OPTIONS);
        for (HelpTopic topic : HelpTopics.ALL) {
            Set<String> documented = flags(topic.options());
            for (String synopsis : topic.synopses()) {
                Matcher m = optionInSynopsis.matcher(synopsis);
                while (m.find()) {
                    assertTrue(documented.contains(m.group()) || global.contains(m.group()),
                            "topic '" + topic.name() + "' shows " + m.group() + " but doesn't explain it");
                }
            }
        }
    }

    @Test
    void everyValueOptionTheParserKnowsIsDocumentedSomewhere() {
        Set<String> documented = new HashSet<>(flags(HelpTopics.GLOBAL_OPTIONS));
        HelpTopics.ALL.forEach(t -> documented.addAll(flags(t.options())));
        for (String option : Parser.VALUE_OPTIONS) {
            assertTrue(documented.contains(option), option + " is not in any help topic");
        }
    }

    @Test
    void everyExampleRunsAnOwnedCommand() {
        for (HelpTopic topic : HelpTopics.ALL) {
            for (String example : topic.examples()) {
                List<String> words = Parser.commandWords(example.substring("logctl ".length()).split(" "));
                assertEquals(List.of(topic), HelpText.lookup(words).topics(),
                        "example '" + example + "' doesn't lead back to topic '" + topic.name() + "'");
            }
        }
    }

    private static Set<String> flags(List<HelpTopic.Option> options) {
        Set<String> flags = new HashSet<>();
        for (HelpTopic.Option option : options) {
            for (String part : option.flag().split(", ")) {
                flags.add(part.split(" ")[0]);
            }
        }
        return flags;
    }

    // --- H2: the overview -------------------------------------------------------

    @Test
    void overviewHasEverySynopsisAndPointsAtTopicHelp() {
        String usage = HelpText.usage();
        HelpText.SYNOPSES.forEach(s -> assertTrue(usage.contains(s), s));
        assertTrue(usage.contains("logctl help <command>"));
        assertTrue(usage.contains(HelpText.GUIDE_URL));
        assertTrue(usage.lines().count() < 70, "the overview should fit on about a screen");
    }

    @Test
    void bareHelpIsTheOverview() {
        assertEquals(new HelpText.Answer(HelpText.usage(), true), HelpText.help(List.of()));
    }

    // --- H3: finding a topic ----------------------------------------------------

    private static List<String> found(String... words) {
        return HelpText.lookup(List.of(words)).topics().stream().map(HelpTopic::name).toList();
    }

    @Test
    void wordsNamingATopicFindIt() {
        assertEquals(List.of("reset"), found("reset"));
        assertEquals(List.of("add rule"), found("add", "rule"));
        assertEquals(List.of("storms"), found("storms"));
    }

    @Test
    void wordsStartingOneTopicsSynopsisFindThatTopic() {
        assertEquals(List.of("recipes"), found("reset", "recipe"));
        assertEquals(List.of("default-handler"), found("set", "default-handler"));
        assertEquals(List.of("storms"), found("enable", "storms"));
        assertEquals(List.of("export vendor-defaults"), found("export"));
        assertEquals(List.of("reset"), found("reset", "loggers"));
    }

    @Test
    void wordsStartingSeveralTopicsListThemAll() {
        assertEquals(List.of("set logger", "set handler", "default-handler"), found("set"));
        assertEquals(List.of("list loggers", "list handlers", "list rules", "recipes"), found("list"));
        HelpText.Answer answer = HelpText.help(List.of("set"));
        assertTrue(answer.known());
        assertTrue(answer.text().startsWith("'set' is several commands:"));
    }

    @Test
    void wordsAfterTheCommandAreIgnored() {
        assertEquals(List.of("set logger"), found("set", "logger", "com.acme", "DEBUG"));
        assertEquals(List.of("alter rule"), found("alter", "rule", "r3"));
    }

    @Test
    void lookupIgnoresCase() {
        assertEquals(List.of("set logger"), found("SET", "Logger"));
    }

    @Test
    void unknownWordsAreAnErrorWithTheOverview() {
        assertEquals(List.of(), found("frobnicate"));
        HelpText.Answer answer = HelpText.help(List.of("frobnicate"));
        assertFalse(answer.known());
        assertTrue(answer.text().startsWith("No command 'frobnicate'."));
        assertTrue(answer.text().contains("Usage:"));
    }

    @Test
    void aTopicShowsItsSynopsesOptionsAndExamples() {
        HelpTopic setLogger = HelpText.lookup(List.of("set", "logger")).topics().get(0);
        String text = HelpText.topic(setLogger);
        assertTrue(text.startsWith("logctl set logger — Change a logger's level."));
        setLogger.synopses().forEach(s -> assertTrue(text.contains("  " + s)));
        assertTrue(text.contains("  --force "));
        setLogger.examples().forEach(e -> assertTrue(text.contains("  " + e)));
    }

    // --- H4: usage errors -------------------------------------------------------

    @Test
    void usageErrorShowsTheCommandsFormsOnly() {
        String hint = HelpText.afterUsageError(List.of("set", "logger", "com.acme", "LOUD"));
        assertTrue(hint.contains("logctl set logger <target>"));
        assertFalse(hint.contains("logctl list loggers"));
        assertTrue(hint.endsWith("Run 'logctl help set logger' for more.\n"));
    }

    @Test
    void usageErrorWithNoKnownCommandShowsTheOverview() {
        assertEquals(HelpText.usage(), HelpText.afterUsageError(List.of()));
        assertEquals(HelpText.usage(), HelpText.afterUsageError(List.of("frobnicate")));
    }

    // --- H5: terminal rendering -------------------------------------------------

    @Test
    void codeSpansPrintInSingleQuotes() {
        assertEquals("run 'set logger' now", HelpText.terminal("run `set logger` now"));
        assertEquals("a pattern like '*.Worker'", HelpText.terminal("a pattern like `'*.Worker'`"));
    }

    @Test
    void wrappingKeepsLinesWithinWidthAndIndents() {
        String wrapped = HelpText.wrap("one two three four five six", 12, "> ", "  ");
        assertEquals("> one two\n  three four\n  five six\n", wrapped);
    }

    @Test
    void topicTextFitsTheTerminalWidth() {
        for (HelpTopic topic : HelpTopics.ALL) {
            for (String line : HelpText.topic(topic).split("\n")) {
                boolean verbatim = line.startsWith("  logctl ");
                assertTrue(verbatim || line.length() <= 80, "too wide in '" + topic.name() + "': " + line);
            }
        }
    }
}
