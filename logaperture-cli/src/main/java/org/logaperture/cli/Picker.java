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
    static final int MAX_LISTED = 50;

    private static final String INDENT = "  ";

    /** Typed at {@code Which logger?} to browse recent loggers; not a logger name or pattern. */
    private static final String RECENT_KEY = "?";

    private Picker() {
    }

    /**
     * The exact logger names chosen, or {@code null} if the operator cancelled. {@code target} is the
     * command line's (kept as given) or {@code null}, in which case it is asked for -- and an answer
     * typed here gets the {@code *.<answer>} shorthand (G4).
     *
     * @param absentQuestion asked, as {@code "No logger named X exists yet; <absentQuestion>"}, before an
     *                       exact name with no logger at or under it is accepted
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
                List<String> recent = RecentLoggers.read();
                String answer = prompter.ask("", "Which logger? A name or pattern, e.g. Deployer or "
                        + "*.deployment.* (Enter to cancel, ? for recent)");
                if (answer.isEmpty()) {
                    return null;
                }
                if (answer.equals(RECENT_KEY)) {
                    // A browse aid, not an answer: show what to type, then ask again.
                    if (recent.isEmpty()) {
                        out.println("No history yet.");
                        showTopLevelPrefixes(mbean, out);
                    } else {
                        List<String> picked = pickRecent(prompter, recent);
                        if (picked != null) {
                            return picked;
                        }
                    }
                    continue;
                }
                current = shortNameShorthand(answer);
                typed = true;
            }
            // A trailing '.*' only picks from a subtree here (issue #139): the picked exact names are what get
            // set or attached. A complete command's never reaches this -- the parser or the agent refuses it.
            List<String> known;
            TreeMap<String, LoggerInfoData> matches;
            try {
                known = Commands.isPattern(current) ? List.of() : atOrUnder(mbean, current);
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
                if (known.contains(current)) {
                    return List.of(current);
                }
                if (!known.isEmpty()) {
                    // Issue #140: a package node is often not a logger itself (just after a restart, say)
                    // while the loggers under it are -- naming it is how to reach all of them.
                    out.println(current + " isn't a logger itself yet; " + known.size() + " logger"
                            + (known.size() == 1 ? "" : "s") + " under it inherit" + (known.size() == 1 ? "s" : "")
                            + " from it (e.g. " + known.get(0) + ").");
                    return List.of(current);
                }
                if (prompter.askYesNo("", "No logger named " + current + " exists yet; " + absentQuestion, false)) {
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
            String[] narrowTo = {null};
            List<String> picked = pickOrNarrow(prompter, names, matches, current, detail, narrowTo);
            if (narrowTo[0] != null) {
                current = narrowNameShorthand(narrowTo[0]);
                typed = true;
                continue;
            }
            if (picked == null) {
                return null; // cancelled
            }
            return picked;
        }
    }

    /**
     * Shows a numbered page of matching loggers and asks the operator to pick, page, or narrow.
     * Returns the chosen exact names, {@code null} to cancel, or a single-element list containing
     * a new pattern to re-search with.
     */
    private static List<String> pickOrNarrow(Prompter prompter, List<String> allNames,
            TreeMap<String, LoggerInfoData> matches, String currentPattern,
            Function<LoggerInfoData, String> detail, String[] narrowTo) {
        PrintStream out = prompter.out();
        int total = allNames.size();
        int offset = 0;
        while (true) {
            int pageEnd = Math.min(offset + MAX_LISTED, total);
            List<String> pageNames = allNames.subList(offset, pageEnd);
            if (offset == 0 && total > MAX_LISTED) {
                out.println(total + " loggers match '" + currentPattern + "'. Page 1 of "
                        + ((total + MAX_LISTED - 1) / MAX_LISTED) + ":");
            } else if (offset == 0) {
                out.println(total + " loggers match '" + currentPattern + "':");
            } else {
                out.println("Page " + ((offset / MAX_LISTED) + 1) + " of "
                        + ((total + MAX_LISTED - 1) / MAX_LISTED) + ":");
            }
            List<String> rows = new ArrayList<>();
            int width = pageNames.stream().mapToInt(String::length).max().orElse(0);
            for (String name : pageNames) {
                String described = describe(detail, matches.get(name));
                rows.add(described.isEmpty() ? name : String.format("%-" + width + "s", name) + described);
            }
            int baseWidth = Integer.toString(pageEnd).length();
            for (int i = 0; i < rows.size(); i++) {
                out.println(INDENT + String.format("%" + baseWidth + "d", offset + i + 1)
                        + "  " + rows.get(i));
            }
            String enterHint = offset + MAX_LISTED < total ? "Enter for more, " : "Enter to cancel, ";
            while (true) {
                String answer = prompter.ask("", "Which? (e.g. 1,3 or 1-2 or all, "
                        + enterHint + "or type to narrow)");
                if (answer.isEmpty()) {
                    if (offset + MAX_LISTED < total) {
                        offset += MAX_LISTED;
                        break; // on to the next page
                    }
                    return null;
                }
                if (!looksLikeSelection(answer)) {
                    // A name, not numbers: search again under it (drill-down).
                    narrowTo[0] = answer;
                    return null;
                }
                try {
                    List<String> picked = new ArrayList<>();
                    for (int index : parseSelection(answer, total)) {
                        picked.add(allNames.get(index - 1));
                    }
                    return picked;
                } catch (IllegalArgumentException invalid) {
                    // Numbers, but not valid ones: explained and asked again, like every other selection.
                    out.println(invalid.getMessage());
                }
            }
        }
    }

    /** Whether {@code answer} is meant as a selection ({@code 1,3-5}, {@code all}) rather than a name. */
    private static boolean looksLikeSelection(String answer) {
        return answer.equalsIgnoreCase("all") || answer.matches("[0-9,\\- ]+");
    }

    /** G4: a name with no {@code *} and no {@code .} is looked up as {@code *.<name>} -- what a log line shows. */
    static String shortNameShorthand(String name) {
        return name.indexOf('*') < 0 && name.indexOf('.') < 0 ? "*." + name : name;
    }

    /** Drill-down shorthand: always appends {@code .*} so the user gets a numbered pick list. */
    private static String narrowNameShorthand(String name) {
        return name.indexOf('*') >= 0 ? name : name + ".*";
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

    /**
     * When the operator asks for recent loggers and there are none, show the first two segments of every
     * known logger name as a quick browse aid. Two segments ({@code org.hibernate}, {@code
     * com.acme}) are more meaningful than one ({@code org}, {@code com}).
     */
    private static void showTopLevelPrefixes(LevelControlMXBean mbean, PrintStream out) {
        TreeSet<String> prefixes = new TreeSet<>();
        int seen = 0;
        try {
            for (LoggerInfoData logger : mbean.listLoggers(null)) {
                String name = logger.getName();
                int firstDot = name.indexOf('.');
                if (firstDot < 0) {
                    prefixes.add(name);
                } else {
                    int secondDot = name.indexOf('.', firstDot + 1);
                    prefixes.add(secondDot < 0 ? name : name.substring(0, secondDot));
                }
                if (++seen >= 200) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            out.println("Could not list loggers: " + e);
            return;
        }
        if (prefixes.isEmpty()) {
            out.println("No loggers are known yet on this JVM.");
        } else {
            out.println(prefixes.size() + " top-level package" + (prefixes.size() == 1 ? "" : "s")
                    + " (from first " + seen + " loggers): " + String.join(", ", prefixes) + ".");
        }
    }

    /** Shows the recent-loggers history as a numbered pick list. Returns chosen names or null if cancelled. */
    private static List<String> pickRecent(Prompter prompter, List<String> recent) {
        prompter.out().println("Recent:");
        List<String> rows = new ArrayList<>(recent);
        List<Integer> chosen = choose(prompter, rows);
        if (chosen == null) {
            return null;
        }
        List<String> picked = new ArrayList<>();
        for (int index : chosen) {
            picked.add(recent.get(index - 1));
        }
        return picked;
    }

    /** The known loggers named {@code name} or under it, in name order ({@code name} itself first if known). */
    private static List<String> atOrUnder(LevelControlMXBean mbean, String name) {
        TreeSet<String> names = new TreeSet<>();
        for (LoggerInfoData logger : mbean.listLoggers(name)) { // a prefix filter: also org.wildflyx
            if (logger.getName().equals(name) || logger.getName().startsWith(name + ".")) {
                names.add(logger.getName());
            }
        }
        return new ArrayList<>(names);
    }
}
