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

import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Picking from a numbered list: loggers matching a pattern (doc/specs/guided-add-rule.md G4, G5),
 * and handlers (doc/specs/guided-commands.md #3). The answer is always the same shape: {@code 1,3-5},
 * {@code all}, or Enter to cancel.
 */
final class Picker {

    /** G5: more matching loggers than this are not listed; the operator is asked for a narrower pattern. */
    static final int MAX_LISTED = 30;

    private static final String INDENT = "  ";

    private Picker() {
    }

    /**
     * The exact logger names chosen, or {@code null} if the operator cancelled. {@code target} is the
     * command line's (kept as given) or {@code null}, in which case it is asked for -- and an answer
     * typed here gets the {@code *.<answer>} shorthand (G4).
     *
     * @param absentQuestion asked, as {@code "No logger named X exists yet; <absentQuestion>"}, before an
     *                       exact name no logger has yet is accepted
     * @param detail         what to show after each matching name, e.g. its current level; {@code null}
     *                       for the name alone
     */
    static List<String> loggers(LevelControlMXBean mbean, Prompter prompter, String target, String absentQuestion,
            Function<LoggerInfoData, String> detail) {
        PrintStream out = prompter.out();
        String current = target;
        // Whether current was typed at the prompt: an invalid one is explained and asked again, while
        // an invalid command-line target stays the usage error it is today.
        boolean typed = false;
        while (true) {
            if (current == null) {
                String answer = prompter.ask("", "Which logger? A name or pattern, e.g. Deployer or "
                        + "*.deployment.* (Enter to cancel)");
                if (answer.isEmpty()) {
                    return null;
                }
                current = shortNameShorthand(answer);
                typed = true;
            }
            if (current.endsWith(".*")) {
                out.println("A trailing '.*' isn't needed -- a bare name already reaches every descendant.");
                current = null;
                continue;
            }
            boolean exists;
            TreeMap<String, LoggerInfoData> matches;
            try {
                exists = !Commands.isPattern(current) && loggerExists(mbean, current);
                matches = Commands.isPattern(current) ? matching(mbean, current) : new TreeMap<>();
            } catch (IllegalArgumentException invalid) {
                if (!typed) {
                    throw invalid;
                }
                out.println(Main.failureOf(invalid).message());
                current = null;
                continue;
            }
            if (!Commands.isPattern(current)) {
                if (exists || prompter.askYesNo("", "No logger named " + current + " exists yet; "
                        + absentQuestion, false)) {
                    return List.of(current);
                }
                current = null;
                continue;
            }
            if (matches.isEmpty()) {
                out.println("No logger matches '" + current + "'.");
                current = null;
                continue;
            }
            List<String> names = new ArrayList<>(matches.keySet());
            if (names.size() == 1) {
                out.println("1 logger matches '" + current + "': " + names.get(0)
                        + describe(detail, matches.get(names.get(0))));
                return names;
            }
            if (names.size() > MAX_LISTED) {
                out.println(names.size() + " loggers match '" + current + "' -- too many to list. Try a "
                        + "narrower pattern.");
                current = null;
                continue;
            }
            out.println(names.size() + " loggers match '" + current + "':");
            List<String> rows = new ArrayList<>();
            int width = names.stream().mapToInt(String::length).max().orElse(0);
            for (String name : names) {
                String described = describe(detail, matches.get(name));
                rows.add(described.isEmpty() ? name : String.format("%-" + width + "s", name) + described);
            }
            List<Integer> chosen = choose(prompter, rows);
            if (chosen == null) {
                return null;
            }
            List<String> picked = new ArrayList<>();
            for (int index : chosen) {
                picked.add(names.get(index - 1));
            }
            return picked;
        }
    }

    /** G4: a name with no {@code *} and no {@code .} is looked up as {@code *.<name>} -- what a log line shows. */
    static String shortNameShorthand(String name) {
        return name.indexOf('*') < 0 && name.indexOf('.') < 0 ? "*." + name : name;
    }

    private static String describe(Function<LoggerInfoData, String> detail, LoggerInfoData row) {
        return detail == null ? "" : " (" + detail.apply(row) + ")";
    }

    /**
     * Prints {@code rows} numbered from 1 and asks which; the chosen 1-based positions, or {@code null}
     * if the operator cancelled. An invalid answer is explained and asked again.
     */
    static List<Integer> choose(Prompter prompter, List<String> rows) {
        PrintStream out = prompter.out();
        int width = Integer.toString(rows.size()).length();
        for (int i = 0; i < rows.size(); i++) {
            out.println(INDENT + String.format("%" + width + "d", i + 1) + "  " + rows.get(i));
        }
        return askSelection(prompter, rows.size());
    }

    /**
     * Asks which of {@code count} already-printed, numbered rows; the chosen 1-based positions, or
     * {@code null} if the operator cancelled. An invalid answer is explained and asked again.
     */
    static List<Integer> askSelection(Prompter prompter, int count) {
        PrintStream out = prompter.out();
        while (true) {
            String answer = prompter.ask("", "Which? (e.g. 1,3 or 1-2 or all; Enter to cancel)");
            if (answer.isEmpty()) {
                return null;
            }
            try {
                return parseSelection(answer, count);
            } catch (IllegalArgumentException invalid) {
                out.println(invalid.getMessage());
            }
        }
    }

    /** {@code 1,3-5}, or {@code all}: the chosen 1-based positions, ascending, each once. */
    static List<Integer> parseSelection(String answer, int count) {
        if (answer.equalsIgnoreCase("all")) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                all.add(i);
            }
            return all;
        }
        TreeSet<Integer> chosen = new TreeSet<>();
        for (String part : answer.split(",")) {
            String token = part.trim();
            int dash = token.indexOf('-');
            int from = number(dash < 0 ? token : token.substring(0, dash), count, answer);
            int to = dash < 0 ? from : number(token.substring(dash + 1), count, answer);
            if (to < from) {
                throw new IllegalArgumentException("'" + token + "' is a backwards range -- write it low-high.");
            }
            for (int i = from; i <= to; i++) {
                chosen.add(i);
            }
        }
        return new ArrayList<>(chosen);
    }

    private static int number(String token, int count, String answer) {
        int n;
        try {
            n = Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + answer + "' isn't a selection -- answer with numbers from the "
                    + "list, e.g. 1,3 or 1-2, or all.");
        }
        if (n < 1 || n > count) {
            throw new IllegalArgumentException(n + " isn't in the list -- choose from 1 to " + count + ".");
        }
        return n;
    }

    /** The loggers {@code pattern} currently matches, by name, in name order. */
    static List<String> matchingNames(LevelControlMXBean mbean, String pattern) {
        return new ArrayList<>(matching(mbean, pattern).keySet());
    }

    private static TreeMap<String, LoggerInfoData> matching(LevelControlMXBean mbean, String pattern) {
        TreeMap<String, LoggerInfoData> byName = new TreeMap<>();
        for (LoggerInfoData logger : mbean.listLoggers(pattern)) {
            byName.putIfAbsent(logger.getName(), logger);
        }
        return byName;
    }

    private static boolean loggerExists(LevelControlMXBean mbean, String name) {
        for (LoggerInfoData logger : mbean.listLoggers(name)) {
            if (logger.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
