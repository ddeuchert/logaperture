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

import org.logaperture.api.Level;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.RuleData;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * {@code logctl add rule}: a complete command attaches straight away; a leading-star target is
 * expanded to the loggers it currently matches, picked on a terminal or all of them with {@code
 * --yes}; an incomplete command on a terminal asks for every part not given, prints the equivalent
 * command, and confirms (doc/specs/guided-add-rule.md). Everything here is client-side: it lists
 * loggers and calls {@code addRuleDrop}/{@code addRuleTrim} once per chosen exact name (G2).
 */
final class AddRuleCommand implements Command {

    /** G5: more matches than this are not listed; the operator is asked for a narrower pattern. */
    static final int MAX_LISTED = 30;

    private static final String DEFAULT_BELOW = Parser.parseBelowLevel("ERROR");
    private static final Parser.TierChoice DEFAULT_TIER = Parser.resolveTier(List.of());
    private static final long DEFAULT_SAMPLE_FULL_MILLIS = SampleFullPolicy.DEFAULT_INTERVAL.toMillis();
    private static final String INDENT = "  ";

    private final AddRuleRequest request;

    AddRuleCommand(AddRuleRequest request) {
        this.request = request;
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        return run(mbean, out, out, in, interactive);
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, PrintStream err, InputStream in, boolean interactive) {
        Prompter prompter = new Prompter(in, out);
        try {
            return request.complete()
                    ? runComplete(mbean, prompter, err, interactive)
                    : runGuided(mbean, prompter, err);
        } catch (Prompter.Cancelled cancelled) {
            out.println("Not applied.");
            return CliError.OK;
        }
    }

    private int runComplete(LevelControlMXBean mbean, Prompter prompter, PrintStream err, boolean interactive) {
        String target = request.target();
        if (!Commands.isPattern(target)) {
            return apply(mbean, prompter.out(), err, request, List.of(target));
        }
        List<String> names;
        if (request.yes()) {
            names = matchingNames(mbean, target);
        } else if (!interactive || request.json()) {
            // As 'set logger' (pattern-selection-semantics.md Decision #4): fail fast rather than block
            // on a stdin nothing will write to.
            int count = matchingNames(mbean, target).size();
            throw new CliError(CliError.USAGE, count == 0
                    ? "'" + target + "' matches no currently-known logger."
                    : "'" + target + "' matches " + count + " currently-known logger" + (count == 1 ? "" : "s")
                            + ". Pass --yes to add this rule to all of them (this affects only loggers that exist "
                            + "right now).");
        } else {
            names = pickLoggers(mbean, prompter, target);
            if (names == null) {
                throw new Prompter.Cancelled();
            }
        }
        if (names.isEmpty()) {
            if (request.json()) {
                prompter.out().println("[]");
            } else {
                prompter.out().println("'" + target + "' matches no currently-known logger; nothing to add.");
            }
            return CliError.OK;
        }
        return apply(mbean, prompter.out(), err, request, names);
    }

    private int runGuided(LevelControlMXBean mbean, Prompter prompter, PrintStream err) {
        PrintStream out = prompter.out();
        List<String> names = pickLoggers(mbean, prompter, request.target());
        if (names == null) {
            throw new Prompter.Cancelled();
        }
        AddRuleRequest answered = request.action() != null ? request : request.withAction(askAction(prompter));
        answered = askMatchers(prompter, answered);
        answered = askOptions(prompter, answered);

        out.println();
        out.println(names.size() == 1 ? "This is the command:" : "These are the commands:");
        for (String name : names) {
            out.println(INDENT + commandLine(answered, name));
        }
        if (!prompter.askYesNo("", "Apply?", true)) {
            throw new Prompter.Cancelled();
        }
        return apply(mbean, out, err, answered, names);
    }

    // --- picking loggers (G4, G5) -------------------------------------------------------------

    /**
     * The exact logger names the rule goes on, or {@code null} if the operator cancelled. {@code
     * target} is the command line's (kept as given) or {@code null}, in which case it is asked for --
     * and an answer typed here gets the {@code *.<answer>} shorthand (G4).
     */
    private static List<String> pickLoggers(LevelControlMXBean mbean, Prompter prompter, String target) {
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
                current = answer.indexOf('*') < 0 && answer.indexOf('.') < 0 ? "*." + answer : answer;
                typed = true;
            }
            if (current.endsWith(".*")) {
                out.println("A trailing '.*' isn't needed -- a bare name already reaches every descendant.");
                current = null;
                continue;
            }
            boolean exists;
            List<String> matches;
            try {
                exists = !Commands.isPattern(current) && loggerExists(mbean, current);
                matches = Commands.isPattern(current) ? matchingNames(mbean, current) : List.of();
            } catch (IllegalArgumentException invalid) {
                if (!typed) {
                    throw invalid;
                }
                out.println(Main.failureOf(invalid).message());
                current = null;
                continue;
            }
            if (!Commands.isPattern(current)) {
                if (exists || prompter.askYesNo("", "No logger named " + current
                        + " exists yet; attach anyway?", false)) {
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
            if (matches.size() == 1) {
                out.println("1 logger matches '" + current + "': " + matches.get(0));
                return matches;
            }
            if (matches.size() > MAX_LISTED) {
                out.println(matches.size() + " loggers match '" + current + "' -- too many to list. Try a "
                        + "narrower pattern.");
                current = null;
                continue;
            }
            out.println(matches.size() + " loggers match '" + current + "':");
            int width = Integer.toString(matches.size()).length();
            for (int i = 0; i < matches.size(); i++) {
                out.println(INDENT + String.format("%" + width + "d", i + 1) + "  " + matches.get(i));
            }
            while (true) {
                String answer = prompter.ask("", "Which? (e.g. 1,3 or 1-2 or all; Enter to cancel)");
                if (answer.isEmpty()) {
                    return null;
                }
                try {
                    List<String> chosen = new ArrayList<>();
                    for (int index : parseSelection(answer, matches.size())) {
                        chosen.add(matches.get(index - 1));
                    }
                    return chosen;
                } catch (IllegalArgumentException invalid) {
                    out.println(invalid.getMessage());
                }
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

    private static List<String> matchingNames(LevelControlMXBean mbean, String pattern) {
        TreeSet<String> names = new TreeSet<>();
        for (LoggerInfoData logger : mbean.listLoggers(pattern)) {
            names.add(logger.getName());
        }
        return new ArrayList<>(names);
    }

    private static boolean loggerExists(LevelControlMXBean mbean, String name) {
        for (LoggerInfoData logger : mbean.listLoggers(name)) {
            if (logger.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // --- the remaining questions (G6) ---------------------------------------------------------

    private static String askAction(Prompter prompter) {
        prompter.out().println();
        while (true) {
            String answer = prompter.ask("", "Drop or trim? [drop/trim]").toLowerCase(Locale.ROOT);
            switch (answer) {
                case "drop", "d" -> {
                    return AddRuleRequest.DROP;
                }
                case "trim", "t" -> {
                    return AddRuleRequest.TRIM;
                }
                default -> prompter.out().println("Answer drop or trim.");
            }
        }
    }

    private static AddRuleRequest askMatchers(Prompter prompter, AddRuleRequest given) {
        PrintStream out = prompter.out();
        boolean askMessage = given.messageContains() == null;
        boolean askThrowable = given.throwableType() == null;
        boolean askThrowableMessage = given.throwableMessageContains() == null;
        boolean isDrop = given.action().equals(AddRuleRequest.DROP);
        while (true) {
            String message = given.messageContains();
            boolean ignoreCase = given.messageIgnoreCase();
            String throwable = given.throwableType();
            String throwableMessage = given.throwableMessageContains();
            boolean anyCause = given.anyCause();
            if (askMessage || askThrowable || askThrowableMessage) {
                out.println();
                out.println("What should the rule match? Leave a question empty to skip it.");
            }
            if (askMessage) {
                message = emptyToNull(prompter.ask(INDENT, "Message contains"));
                ignoreCase = message != null && prompter.askYesNo(INDENT, "Ignore case?", false);
            }
            if (askThrowable) {
                throwable = emptyToNull(prompter.ask(INDENT, "Exception class (fully qualified)"));
            }
            if (askThrowableMessage) {
                throwableMessage = emptyToNull(prompter.ask(INDENT, "Exception message contains"));
            }
            if (!anyCause && (throwable != null || throwableMessage != null)) {
                anyCause = prompter.askYesNo(INDENT, "Also look for the exception anywhere in the cause chain?",
                        false);
            }
            if (isDrop && message == null && throwable == null && throwableMessage == null) {
                out.println(INDENT + "A drop rule needs at least one of these -- otherwise use 'logctl set logger' "
                        + "to change the level.");
                continue;
            }
            return given.withMatchers(message, ignoreCase, throwable, throwableMessage, anyCause);
        }
    }

    private static AddRuleRequest askOptions(Prompter prompter, AddRuleRequest given) {
        PrintStream out = prompter.out();
        out.println();
        String below = given.belowLevel();
        while (below == null) {
            String answer = prompter.ask("", "Apply to events below which level? [ERROR]");
            try {
                below = answer.isEmpty() ? DEFAULT_BELOW : Parser.parseBelowLevel(answer);
            } catch (CliError invalid) {
                out.println(invalid.getMessage());
            }
        }

        Boolean sampleFullEnabled = given.sampleFullEnabled();
        Long sampleFullEveryMillis = given.sampleFullEveryMillis();
        Integer frames = given.frames();
        boolean collapseCauses = given.collapseCauses();
        if (given.action().equals(AddRuleRequest.DROP)) {
            while (sampleFullEnabled == null) {
                String answer = prompter.ask("", "Keep one full event every... (a duration such as 5m, or off) [5m]");
                try {
                    if (answer.equalsIgnoreCase("off")) {
                        sampleFullEnabled = false;
                    } else {
                        sampleFullEveryMillis = answer.isEmpty() ? DEFAULT_SAMPLE_FULL_MILLIS
                                : Durations.parse(answer).toMillis();
                        sampleFullEnabled = true;
                    }
                } catch (CliError invalid) {
                    out.println(invalid.getMessage());
                }
            }
        } else {
            while (frames == null) {
                String answer = prompter.ask("", "Stack frames to keep [0]");
                try {
                    int n = answer.isEmpty() ? 0 : Integer.parseInt(answer);
                    if (n < 0) {
                        out.println("Frames must be 0 or more.");
                    } else {
                        frames = n;
                    }
                } catch (NumberFormatException invalid) {
                    out.println("'" + answer + "' is not a number.");
                }
            }
            if (!collapseCauses) {
                collapseCauses = prompter.askYesNo("", "Collapse the cause chain too?", false);
            }
        }

        Parser.TierChoice tier = given.tier();
        while (tier == null) {
            String answer = prompter.ask("", "How long should the rule last? session, for <duration> "
                    + "(e.g. for 30m), or sticky [for 4h]");
            try {
                tier = answer.isEmpty() ? DEFAULT_TIER : parseTierAnswer(answer);
            } catch (CliError invalid) {
                out.println(invalid.getMessage());
            }
        }

        String reason = given.reason();
        if (reason == null) {
            reason = emptyToNull(prompter.ask("", "Reason (optional)"));
        }
        return given.withOptions(below, sampleFullEnabled, sampleFullEveryMillis, frames, collapseCauses, tier,
                reason);
    }

    /** The tier question also takes a bare duration ({@code 30m}) as {@code for 30m}. */
    private static Parser.TierChoice parseTierAnswer(String answer) {
        List<String> tokens = List.of(answer.split("\\s+"));
        if (tokens.size() == 1 && !tokens.get(0).equals("session") && !tokens.get(0).equals("sticky")
                && !tokens.get(0).equals("for")) {
            return new Parser.TierChoice("FOR", Durations.parse(tokens.get(0)).toSeconds());
        }
        return Parser.resolveTier(tokens);
    }

    private static String emptyToNull(String answer) {
        return answer.isEmpty() ? null : answer;
    }

    // --- the command and applying it (G7, G10) ------------------------------------------------

    /**
     * The one-line command that adds {@code rule} to {@code loggerName}: options that differ from the
     * default only, quoted as {@code list rules --verbose} quotes them (G7).
     */
    static String commandLine(AddRuleRequest rule, String loggerName) {
        StringJoiner line = new StringJoiner(" ");
        line.add("logctl add rule").add(rule.action()).add(org.logaperture.api.RuleExpression.quote(loggerName));
        if (rule.messageContains() != null) {
            line.add(rule.messageIgnoreCase() ? "--message-contains-ignore-case" : "--message-contains")
                    .add(org.logaperture.api.RuleExpression.quote(rule.messageContains()));
        }
        if (rule.throwableType() != null) {
            line.add("--throwable").add(org.logaperture.api.RuleExpression.quote(rule.throwableType()));
        }
        if (rule.throwableMessageContains() != null) {
            line.add("--throwable-message-contains")
                    .add(org.logaperture.api.RuleExpression.quote(rule.throwableMessageContains()));
        }
        if (rule.anyCause()) {
            line.add("--any-cause");
        }
        String below = effectiveBelow(rule);
        if (!below.equals(DEFAULT_BELOW)) {
            line.add("--below").add(org.logaperture.api.RuleExpression.belowFor(Level.valueOf(below)));
        }
        if (rule.action().equals(AddRuleRequest.DROP)) {
            if (Boolean.FALSE.equals(rule.sampleFullEnabled())) {
                line.add("--no-sample-full");
            } else if (effectiveSampleFullMillis(rule) != DEFAULT_SAMPLE_FULL_MILLIS) {
                line.add("--sample-full").add(org.logaperture.api.RuleExpression.duration(effectiveSampleFullMillis(rule)));
            }
        } else {
            if (effectiveFrames(rule) != 0) {
                line.add("--frames").add(Integer.toString(effectiveFrames(rule)));
            }
            if (rule.collapseCauses()) {
                line.add("--collapse-causes");
            }
        }
        Parser.TierChoice tier = effectiveTier(rule);
        switch (tier.tierName()) {
            case "SESSION" -> line.add("session");
            case "STICKY" -> line.add("sticky");
            default -> {
                if (!tier.equals(DEFAULT_TIER)) {
                    line.add("for").add(org.logaperture.api.RuleExpression.duration(tier.forSeconds() * 1000));
                }
            }
        }
        if (rule.reason() != null) {
            line.add("--reason").add(org.logaperture.api.RuleExpression.quote(rule.reason()));
        }
        return line.toString();
    }

    /**
     * Attaches {@code rule} to each name in turn. With several names a refused one doesn't stop the
     * others: refusals are printed after the successes and the exit code is the first failure's (G10).
     */
    private int apply(LevelControlMXBean mbean, PrintStream out, PrintStream err, AddRuleRequest rule,
            List<String> names) {
        if (names.size() == 1 && !Commands.isPattern(request.target() == null ? "" : request.target())) {
            // One exact name, as before guided mode: a failure is the command's failure, reported by Main.
            return print(out, rule, List.of(attach(mbean, rule, names.get(0))), jsonArray());
        }
        List<RuleData> created = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        int exitCode = CliError.OK;
        for (String name : names) {
            try {
                created.add(attach(mbean, rule, name));
            } catch (RuntimeException e) {
                Main.Failure failure = Main.failureOf(e);
                refusals.add(name + ": " + failure.message());
                if (exitCode == CliError.OK) {
                    exitCode = failure.exitCode();
                }
            }
        }
        print(out, rule, created, jsonArray());
        for (String refusal : refusals) {
            err.println(refusal);
        }
        return exitCode;
    }

    /** G9: a pattern target's {@code --json} output is an array, a single exact target's one object. */
    private boolean jsonArray() {
        return request.target() == null || Commands.isPattern(request.target());
    }

    private int print(PrintStream out, AddRuleRequest rule, List<RuleData> created, boolean jsonArray) {
        if (rule.json()) {
            if (jsonArray) {
                StringJoiner array = new StringJoiner(",", "[", "]");
                for (RuleData row : created) {
                    array.add(Json.rule(row));
                }
                out.println(array);
            } else {
                out.println(Json.rule(created.get(0)));
            }
            return CliError.OK;
        }
        for (RuleData row : created) {
            out.println(row.getId() + "   " + row.getLoggerName() + " → " + rule.action() + "   ("
                    + Commands.tierDetail(row.getTier(), row.getExpiresAt()) + ")");
        }
        return CliError.OK;
    }

    private static RuleData attach(LevelControlMXBean mbean, AddRuleRequest rule, String name) {
        Parser.TierChoice tier = effectiveTier(rule);
        if (rule.action().equals(AddRuleRequest.DROP)) {
            return mbean.addRuleDrop(name, rule.messageContains(), rule.messageIgnoreCase(), rule.throwableType(),
                    rule.throwableMessageContains(), rule.anyCause(), effectiveBelow(rule),
                    !Boolean.FALSE.equals(rule.sampleFullEnabled()), effectiveSampleFullMillis(rule), rule.reason(),
                    tier.tierName(), tier.forSeconds());
        }
        return mbean.addRuleTrim(name, rule.messageContains(), rule.messageIgnoreCase(), rule.throwableType(),
                rule.throwableMessageContains(), rule.anyCause(), effectiveBelow(rule), effectiveFrames(rule),
                rule.collapseCauses(), rule.reason(), tier.tierName(), tier.forSeconds());
    }

    // Omitted parts resolve to drop-rule.md's and trim-rule.md's defaults here, never left null -- an
    // omitted --below must still spare ERROR and above (drop-rule.md "Safety set").

    private static String effectiveBelow(AddRuleRequest rule) {
        return rule.belowLevel() != null ? rule.belowLevel() : DEFAULT_BELOW;
    }

    private static long effectiveSampleFullMillis(AddRuleRequest rule) {
        return rule.sampleFullEveryMillis() != null ? rule.sampleFullEveryMillis() : DEFAULT_SAMPLE_FULL_MILLIS;
    }

    private static int effectiveFrames(AddRuleRequest rule) {
        return rule.frames() != null ? rule.frames() : 0;
    }

    private static Parser.TierChoice effectiveTier(AddRuleRequest rule) {
        return rule.tier() != null ? rule.tier() : DEFAULT_TIER;
    }
}
