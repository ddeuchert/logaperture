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

import java.util.List;
import java.util.Locale;

/**
 * Questions more than one guided command asks (doc/specs/guided-add-rule.md, doc/specs/
 * guided-commands.md), each validated with the command line's own checks and asked again with the
 * reason when the answer is invalid -- plus the spelling of a lifetime back as command-line words.
 */
final class Questions {

    /** What Enter means at a lifetime question: a bare {@code set logger}'s {@code for 4h}. */
    static final Parser.TierChoice DEFAULT_TIER = Parser.resolveTier(List.of());

    private Questions() {
    }

    /** A level name (case-insensitive), with {@code AUTO} too when {@code autoAllowed}; returned upper-case. */
    static String askLevel(Prompter prompter, String question, boolean autoAllowed) {
        while (true) {
            String answer = prompter.ask("", question);
            if (autoAllowed && answer.equalsIgnoreCase("auto")) {
                return "AUTO";
            }
            try {
                return Parser.parseLevel(answer);
            } catch (CliError invalid) {
                prompter.out().println(invalid.getMessage());
            }
        }
    }

    /** {@code session}, {@code for <duration>} (or a bare duration), or {@code sticky}; Enter is {@code for 4h}. */
    static Parser.TierChoice askTier(Prompter prompter, String question) {
        while (true) {
            String answer = prompter.ask("", question + " session, for <duration> (e.g. for 30m), or sticky [for 4h]");
            try {
                return answer.isEmpty() ? DEFAULT_TIER : parseTierAnswer(answer);
            } catch (CliError invalid) {
                prompter.out().println(invalid.getMessage());
            }
        }
    }

    /** The tier question also takes a bare duration ({@code 30m}) as {@code for 30m}. */
    static Parser.TierChoice parseTierAnswer(String answer) {
        List<String> tokens = List.of(answer.split("\\s+"));
        if (tokens.size() == 1 && !tokens.get(0).equals("session") && !tokens.get(0).equals("sticky")
                && !tokens.get(0).equals("for")) {
            return new Parser.TierChoice("FOR", Durations.parse(tokens.get(0)).toSeconds());
        }
        return Parser.resolveTier(tokens);
    }

    /** A free-text answer; {@code null} when left empty. */
    static String askOptional(Prompter prompter, String question) {
        return emptyToNull(prompter.ask("", question));
    }

    static String emptyToNull(String answer) {
        return answer.isEmpty() ? null : answer;
    }

    /**
     * One of {@code choices}, or its first letter when no other choice starts with it; returned as the
     * full word. Asked again, naming the choices, until one is given.
     */
    static String askChoice(Prompter prompter, String question, List<String> choices) {
        while (true) {
            String answer = prompter.ask("", question + " [" + String.join("/", choices) + "]")
                    .toLowerCase(Locale.ROOT);
            for (String choice : choices) {
                if (answer.equals(choice)) {
                    return choice;
                }
            }
            // A first letter answers only when no other choice shares it ('r' can't pick rules over recipes).
            List<String> byLetter = choices.stream()
                    .filter(choice -> answer.length() == 1 && choice.startsWith(answer)).toList();
            if (byLetter.size() == 1) {
                return byLetter.get(0);
            }
            prompter.out().println("Answer " + String.join(", ", choices.subList(0, choices.size() - 1)) + " or "
                    + choices.get(choices.size() - 1) + ".");
        }
    }

    /**
     * The command-line words for {@code tier}: nothing for the default {@code for 4h}, otherwise
     * {@code session}, {@code sticky} or {@code for <duration>} -- as guided {@code add rule} prints them.
     */
    static String tierWords(Parser.TierChoice tier) {
        return switch (tier.tierName()) {
            case "SESSION" -> "session";
            case "STICKY" -> "sticky";
            default -> tier.equals(DEFAULT_TIER) ? ""
                    : "for " + org.logaperture.api.RuleExpression.duration(tier.forSeconds() * 1000);
        };
    }
}
