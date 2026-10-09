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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code logctl}'s help on the terminal, printed from {@link HelpTopics}: the one-screen overview
 * ({@code --help}), one topic ({@code logctl help <command>}), and the short hint after a usage error
 * (doc/specs/user-documentation.md, slice 2, H2–H4).
 *
 * <p>{@link #SYNOPSES} is kept separately so the phone-test check (doc/specs/cli-transport.md §6.2,
 * "The phone test, enforced") can assert every one of them is dictatable — contains none of
 * {@code : = ( ) /}.
 */
final class HelpText {

    /** Every synopsis, in overview order: each topic's, topic by topic. */
    static final List<String> SYNOPSES = HelpTopics.ALL.stream().flatMap(t -> t.synopses().stream()).toList();

    static final String GUIDE_URL = "https://logaperture.org/";

    private static final int WIDTH = 80;
    private static final int OPTION_COLUMN = 24;
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

    private HelpText() {
    }

    /** The overview: {@code logctl --help}, and bare {@code logctl help}. */
    static String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("logctl — runtime logging control for a running JVM\n\n");
        sb.append("Usage:\n");
        for (String synopsis : SYNOPSES) {
            sb.append("  ").append(synopsis).append('\n');
        }
        sb.append("\nOptions for every command:\n");
        appendOptions(sb, HelpTopics.GLOBAL_OPTIONS);
        for (String note : HelpTopics.NOTES) {
            sb.append('\n').append(wrap(terminal(note), WIDTH, "", ""));
        }
        sb.append("\nRun 'logctl help <command>' for one command, e.g. 'logctl help add rule'.\n");
        sb.append("Full guide: ").append(GUIDE_URL).append('\n');
        return sb.toString();
    }

    /** One topic, as {@code logctl help <command>} prints it. */
    static String topic(HelpTopic topic) {
        StringBuilder sb = new StringBuilder();
        sb.append(wrap("logctl " + topic.name() + " — " + terminal(topic.summary()), WIDTH, "", "")).append('\n');
        sb.append("Usage:\n");
        for (String synopsis : topic.synopses()) {
            sb.append("  ").append(synopsis).append('\n');
        }
        for (String paragraph : topic.paragraphs()) {
            sb.append('\n').append(wrap(terminal(paragraph), WIDTH, "", ""));
        }
        if (!topic.options().isEmpty()) {
            sb.append("\nOptions:\n");
            appendOptions(sb, topic.options());
        }
        if (!topic.examples().isEmpty()) {
            sb.append("\nExamples:\n");
            for (String example : topic.examples()) {
                sb.append("  ").append(example).append('\n');
            }
        }
        sb.append("\nRun 'logctl --help' for the options every command takes.\n");
        return sb.toString();
    }

    /** What {@code logctl help <words>} (or {@code logctl <words> --help}) prints, and whether the words named anything. */
    record Answer(String text, boolean known) {
    }

    static Answer help(List<String> words) {
        if (words.isEmpty()) {
            return new Answer(usage(), true);
        }
        Lookup lookup = lookup(words);
        if (lookup.topics().size() == 1) {
            return new Answer(topic(lookup.topics().get(0)), true);
        }
        if (lookup.topics().size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append('\'').append(String.join(" ", lookup.words())).append("' is several commands:\n");
            int nameWidth = lookup.topics().stream().mapToInt(t -> t.name().length()).max().orElse(0) + 3;
            for (HelpTopic topic : lookup.topics()) {
                sb.append("  ").append(pad(topic.name(), nameWidth)).append(terminal(topic.summary())).append('\n');
            }
            sb.append("\nRun 'logctl help <command>' with one of them, for example 'logctl help ")
                    .append(lookup.topics().get(0).name()).append("'.\n");
            return new Answer(sb.toString(), true);
        }
        return new Answer("No command '" + String.join(" ", words) + "'.\n\n" + usage(), false);
    }

    /**
     * What follows a usage error's message (H4): the forms of the command the words point to, and
     * where to read more, or the whole overview when they point nowhere.
     */
    static String afterUsageError(List<String> words) {
        Lookup lookup = lookup(words);
        if (lookup.topics().isEmpty()) {
            return usage();
        }
        StringBuilder sb = new StringBuilder();
        for (HelpTopic topic : lookup.topics()) {
            for (String synopsis : topic.synopses()) {
                sb.append("  ").append(synopsis).append('\n');
            }
        }
        String name = lookup.topics().size() == 1 ? lookup.topics().get(0).name() : "<command>";
        sb.append("\nRun 'logctl help ").append(name).append("' for more.\n");
        return sb.toString();
    }

    /**
     * The topics some command words point to (H3), and the words that matched. The longest leading
     * run of words that names something wins, so a logger name or level after the command is ignored.
     * Within one run: a topic named exactly by the words, otherwise every topic owning a synopsis
     * that starts with them.
     */
    record Lookup(List<String> words, List<HelpTopic> topics) {
    }

    static Lookup lookup(List<String> words) {
        List<String> lower = words.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        for (int k = lower.size(); k >= 1; k--) {
            List<String> prefix = lower.subList(0, k);
            String joined = String.join(" ", prefix);
            for (HelpTopic topic : HelpTopics.ALL) {
                if (topic.name().equals(joined)) {
                    return new Lookup(prefix, List.of(topic));
                }
            }
            List<HelpTopic> owners = new ArrayList<>();
            for (HelpTopic topic : HelpTopics.ALL) {
                if (topic.synopses().stream().anyMatch(s -> startsWithWords(s, prefix))) {
                    owners.add(topic);
                }
            }
            if (!owners.isEmpty()) {
                return new Lookup(prefix, List.copyOf(owners));
            }
        }
        return new Lookup(List.of(), List.of());
    }

    private static boolean startsWithWords(String synopsis, List<String> words) {
        List<String> tokens = Arrays.asList(synopsis.split(" "));
        // tokens.get(0) is "logctl".
        return tokens.size() > words.size() && tokens.subList(1, words.size() + 1).equals(words);
    }

    private static void appendOptions(StringBuilder sb, List<HelpTopic.Option> options) {
        String hanging = " ".repeat(OPTION_COLUMN);
        for (HelpTopic.Option option : options) {
            String flag = "  " + option.flag();
            String description = terminal(option.description());
            if (flag.length() + 2 <= OPTION_COLUMN) {
                sb.append(wrap(description, WIDTH, pad(flag, OPTION_COLUMN), hanging));
            } else {
                sb.append(flag).append('\n').append(wrap(description, WIDTH, hanging, hanging));
            }
        }
    }

    /**
     * A paragraph's Markdown code spans as the terminal shows them: in single quotes, the way the
     * help has always quoted commands. A span that is already quoted ({@code `'*.Worker'`}) keeps
     * its own quotes.
     */
    static String terminal(String markdown) {
        Matcher m = CODE_SPAN.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1);
            String shown = code.startsWith("'") && code.endsWith("'") ? code : "'" + code + "'";
            m.appendReplacement(sb, Matcher.quoteReplacement(shown));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Word-wraps {@code text} to {@code width}; the first line starts with {@code first}, the rest with {@code rest}. */
    static String wrap(String text, int width, String first, String rest) {
        StringBuilder sb = new StringBuilder();
        StringBuilder line = new StringBuilder(first);
        boolean lineEmpty = true;
        for (String word : text.split(" +")) {
            if (!lineEmpty && line.length() + 1 + word.length() > width) {
                sb.append(line).append('\n');
                line = new StringBuilder(rest);
                lineEmpty = true;
            }
            if (!lineEmpty) {
                line.append(' ');
            }
            line.append(word);
            lineEmpty = false;
        }
        sb.append(line).append('\n');
        return sb.toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
