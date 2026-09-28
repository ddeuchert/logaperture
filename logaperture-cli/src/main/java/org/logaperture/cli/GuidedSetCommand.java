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

import org.logaperture.api.HandlerRef;
import org.logaperture.api.RuleExpression;
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.LevelControlMXBean;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@code logctl set} with a part left out, on a terminal (doc/specs/guided-commands.md #6, #8, #9):
 * {@code set} alone asks which kind; {@code set handler [<name>]} picks handlers from the catalog and asks
 * the level, lifetime and reason; {@code set default-handler} picks the new membership. Each prints the
 * equivalent command and confirms (#1). Guided {@code set logger} is {@link SetLoggerCommand}'s.
 */
final class GuidedSetCommand implements Command {

    static final String LOGGER = "logger";
    static final String HANDLER = "handler";
    static final String DEFAULT_HANDLER = "default-handler";

    private static final String INDENT = "  ";

    private final String noun;
    private final String handlerRef;
    private final String reason;

    /**
     * @param noun       {@link #LOGGER}, {@link #HANDLER}, {@link #DEFAULT_HANDLER}, or {@code null} to ask
     * @param handlerRef the handler named on the command line, or {@code null} to pick
     */
    GuidedSetCommand(String noun, String handlerRef, String reason) {
        this.noun = noun;
        this.handlerRef = handlerRef;
        this.reason = reason;
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        return run(mbean, out, out, in, interactive);
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, PrintStream err, InputStream in, boolean interactive) {
        Prompter prompter = new Prompter(in, out);
        try {
            String chosen = noun != null ? noun : Questions.askChoice(prompter,
                    "Set a logger, a handler, or the default handlers?", List.of(LOGGER, HANDLER, DEFAULT_HANDLER));
            return switch (chosen) {
                case LOGGER -> Commands.setLoggerGuided(null, reason).run(mbean, out, err, in, interactive);
                case HANDLER -> setHandler(mbean, prompter, err, in, interactive);
                default -> setDefaultHandler(mbean, prompter, in, interactive);
            };
        } catch (Prompter.Cancelled cancelled) {
            out.println("Not applied.");
            return CliError.OK;
        }
    }

    // --- set handler (#8) -----------------------------------------------------------------------

    private int setHandler(LevelControlMXBean mbean, Prompter prompter, PrintStream err, InputStream in,
            boolean interactive) {
        PrintStream out = prompter.out();
        List<String> refs;
        if (handlerRef != null) {
            refs = List.of(handlerRef);
        } else {
            List<HandlerInfoData> catalog = catalog(mbean);
            if (catalog.isEmpty()) {
                out.println("This framework's handlers have no level of their own; nothing to set.");
                return CliError.OK;
            }
            out.println("Handlers:");
            refs = pick(prompter, catalog);
        }
        String level = Questions.askLevel(prompter, "Level? e.g. DEBUG, INFO, or AUTO", true);
        Parser.TierChoice tier = Questions.askTier(prompter, "How long?");
        String chosenReason = reason != null ? reason : Questions.askOptional(prompter, "Reason (optional)");

        out.println();
        out.println(refs.size() == 1 ? "This is the command:" : "These are the commands:");
        for (String ref : refs) {
            out.println(INDENT + handlerCommandLine(ref, level, tier, chosenReason));
        }
        if (!prompter.askYesNo("", "Apply?", true)) {
            throw new Prompter.Cancelled();
        }
        List<Command> commands = new ArrayList<>();
        for (String ref : refs) {
            commands.add(level.equals("AUTO")
                    ? Commands.setHandlerAuto(ref, chosenReason, tier.tierName(), tier.forSeconds(), false)
                    : Commands.setHandlerLevel(ref, level, chosenReason, tier.tierName(), tier.forSeconds(), false));
        }
        return runEach(mbean, out, err, in, interactive, refs, commands);
    }

    static String handlerCommandLine(String ref, String level, Parser.TierChoice tier, String reason) {
        StringBuilder line = new StringBuilder("logctl set handler ").append(RuleExpression.quote(ref)).append(' ')
                .append(level);
        String tierWords = Questions.tierWords(tier);
        if (!tierWords.isEmpty()) {
            line.append(' ').append(tierWords);
        }
        if (reason != null) {
            line.append(" --reason ").append(RuleExpression.quote(reason));
        }
        return line.toString();
    }

    /**
     * Runs each handler's command in turn. With several, a refused one doesn't stop the others: refusals
     * are printed after the successes and the exit code is the first failure's (guided-add-rule.md G10).
     */
    private static int runEach(LevelControlMXBean mbean, PrintStream out, PrintStream err, InputStream in,
            boolean interactive, List<String> refs, List<Command> commands) {
        if (commands.size() == 1) {
            return commands.get(0).run(mbean, out, err, in, interactive);
        }
        List<String> refusals = new ArrayList<>();
        int exitCode = CliError.OK;
        for (int i = 0; i < commands.size(); i++) {
            try {
                commands.get(i).run(mbean, out, err, in, interactive);
            } catch (RuntimeException e) {
                Main.Failure failure = Main.failureOf(e);
                refusals.add(refs.get(i) + ": " + failure.message());
                if (exitCode == CliError.OK) {
                    exitCode = failure.exitCode();
                }
            }
        }
        for (String refusal : refusals) {
            err.println(refusal);
        }
        return exitCode;
    }

    // --- set default-handler (#9) ---------------------------------------------------------------

    private int setDefaultHandler(LevelControlMXBean mbean, Prompter prompter, InputStream in, boolean interactive) {
        PrintStream out = prompter.out();
        List<HandlerInfoData> catalog = catalog(mbean);
        String current = null;
        List<HandlerInfoData> members = new ArrayList<>();
        for (HandlerInfoData row : catalog) {
            if (row.getRef().equals(HandlerRef.DEFAULT_HANDLERS.value())) {
                current = row.getMembersSummary();
            } else if (!row.getRef().equals(HandlerRef.ALL_HANDLERS.value())) {
                members.add(row);
            }
        }
        if (members.isEmpty()) {
            out.println("There are no handlers to choose from.");
            return CliError.OK;
        }
        if (current != null) {
            out.println("DEFAULT_HANDLERS is now: " + current);
        }
        out.println("Choose its members:");
        List<String> names = pick(prompter, members);

        StringJoiner line = new StringJoiner(" ", "logctl set default-handler ", "");
        for (String name : names) {
            line.add(RuleExpression.quote(name));
        }
        out.println();
        out.println("This is the command:");
        out.println(INDENT + line);
        if (!prompter.askYesNo("", "Apply?", true)) {
            throw new Prompter.Cancelled();
        }
        return Commands.setDefaultHandlerMembers(names, false).run(mbean, out, in, interactive);
    }

    // --- the handler list (#3) ------------------------------------------------------------------

    /** Every handler once, in the agent's order -- a handler in several logging contexts is listed once. */
    private static List<HandlerInfoData> catalog(LevelControlMXBean mbean) {
        Map<String, HandlerInfoData> byRef = new LinkedHashMap<>();
        for (HandlerInfoData row : mbean.listHandlers()) {
            byRef.putIfAbsent(row.getRef(), row);
        }
        return new ArrayList<>(byRef.values());
    }

    /** The chosen handler names; Enter cancels. */
    private static List<String> pick(Prompter prompter, List<HandlerInfoData> rows) {
        int width = rows.stream().mapToInt(row -> row.getRef().length()).max().orElse(0);
        List<String> labels = new ArrayList<>();
        for (HandlerInfoData row : rows) {
            labels.add(String.format("%-" + width + "s", row.getRef()) + "  " + describe(row));
        }
        List<Integer> chosen = Picker.choose(prompter, labels);
        if (chosen == null) {
            throw new Prompter.Cancelled();
        }
        List<String> refs = new ArrayList<>();
        for (int index : chosen) {
            refs.add(rows.get(index - 1).getRef());
        }
        return refs;
    }

    /** What a handler row shows next to its name: its current level, and any override on it. */
    private static String describe(HandlerInfoData row) {
        if (row.getRef().equals(HandlerRef.ALL_HANDLERS.value())) {
            return "every handler";
        }
        if (row.getRef().equals(HandlerRef.DEFAULT_HANDLERS.value())) {
            return row.getMembersSummary() == null ? "" : "now " + row.getMembersSummary();
        }
        String level = row.getLevel() == null ? "no level" : row.getLevel();
        if (!row.isOverrideActive()) {
            return level;
        }
        return level + ("AUTO".equals(row.getOverrideMode()) ? ", AUTO" : ", override");
    }
}
