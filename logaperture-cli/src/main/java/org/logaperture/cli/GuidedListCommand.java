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

import org.logaperture.api.RuleExpression;
import org.logaperture.control.jmx.LevelControlMXBean;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * {@code logctl list} alone, on a terminal (doc/specs/guided-commands.md #4, #5): asks what to list and,
 * for loggers, a name or pattern to look for; prints the equivalent command as a hint (#2), then the
 * listing. It changes nothing, so nothing is confirmed.
 */
final class GuidedListCommand implements Command {

    private final boolean showAll;

    /** @param showAll {@code --show-all} was given with the bare {@code list} */
    GuidedListCommand(boolean showAll) {
        this.showAll = showAll;
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        Prompter prompter = new Prompter(in, out);
        try {
            String noun = Questions.askChoice(prompter, "List loggers, handlers or rules?",
                    List.of("loggers", "handlers", "rules"));
            return switch (noun) {
                case "loggers" -> listLoggers(mbean, prompter, in, interactive);
                case "handlers" -> {
                    out.println("Command: logctl list handlers" + (showAll ? " --show-all" : ""));
                    yield Commands.listHandlers(showAll, false).run(mbean, out, in, interactive);
                }
                default -> {
                    if (showAll) {
                        throw new CliError(CliError.USAGE, "--show-all does not apply to 'list rules'.");
                    }
                    out.println("Command: logctl list rules");
                    yield Commands.listRules(false, false).run(mbean, out, in, interactive);
                }
            };
        } catch (Prompter.Cancelled cancelled) {
            return CliError.OK;
        }
    }

    /**
     * #5: a typed name gets guided {@code add rule}'s {@code *.<answer>} shorthand (G4) and implies
     * {@code --show-all} -- someone typing a name is looking for a logger, not only an overridden one.
     */
    private int listLoggers(LevelControlMXBean mbean, Prompter prompter, InputStream in, boolean interactive) {
        PrintStream out = prompter.out();
        while (true) {
            String answer = prompter.ask("", "Logger name or pattern, e.g. Deployer or org.jboss (Enter for every "
                    + "overridden logger)");
            String filter = answer.isEmpty() ? null : Picker.shortNameShorthand(answer);
            if (filter != null) {
                try {
                    mbean.listLoggers(filter);
                } catch (IllegalArgumentException invalid) {
                    out.println(Main.failureOf(invalid).message());
                    continue;
                }
            }
            boolean all = showAll || filter != null;
            out.println("Command: logctl list loggers" + (filter == null ? "" : " " + RuleExpression.quote(filter))
                    + (all ? " --show-all" : ""));
            return Commands.listLoggers(filter, all, false).run(mbean, out, in, interactive);
        }
    }
}
