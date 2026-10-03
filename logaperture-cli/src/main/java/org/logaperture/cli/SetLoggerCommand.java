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

import org.logaperture.api.DescendantLevel;
import org.logaperture.api.RuleExpression;
import org.logaperture.control.jmx.DescendantLevelData;
import org.logaperture.control.jmx.HandlerFloorData;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code logctl set logger}. {@code target} is an exact logger name or a leading-star pattern (doc/specs/
 * pattern-selection-semantics.md): on a terminal a pattern's matches are listed to pick from, as guided
 * {@code add rule} does (doc/specs/guided-commands.md #10); without one it needs {@code --yes}. With the
 * level left out on a terminal, it asks for the logger, level, lifetime and reason, prints the equivalent
 * command and confirms (#7).
 */
final class SetLoggerCommand implements Command {

    private static final String INDENT = "  ";

    private final String target;
    private final String level;
    private final String reason;
    private final Parser.TierChoice tier;
    private final boolean yes;
    private final boolean json;
    private final boolean force;

    /**
     * @param target {@code null} in guided mode when not given: it is asked for
     * @param level  {@code null} for guided mode, which then also asks for the lifetime ({@code tier} is ignored)
     * @param force  {@code --force}: also set the loggers under each target that keep a level of their own
     *               (doc/specs/set-logger-force.md); in guided mode, given means the question isn't asked
     */
    SetLoggerCommand(String target, String level, String reason, Parser.TierChoice tier, boolean yes, boolean json,
            boolean force) {
        this.target = target;
        this.level = level;
        this.reason = reason;
        this.tier = tier;
        this.yes = yes;
        this.json = json;
        this.force = force;
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        return run(mbean, out, out, in, interactive);
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, PrintStream err, InputStream in, boolean interactive) {
        Prompter prompter = new Prompter(in, out);
        try {
            return level != null ? runComplete(mbean, prompter, err, interactive) : runGuided(mbean, prompter, err);
        } catch (Prompter.Cancelled cancelled) {
            out.println("Not applied.");
            return CliError.OK;
        }
    }

    private int runComplete(LevelControlMXBean mbean, Prompter prompter, PrintStream err, boolean interactive) {
        PrintStream out = prompter.out();
        if (!Commands.isPattern(target) || yes || Commands.isTrailingWildcard(target)) {
            // An exact name, --yes, or a trailing-star target the server rejects on its own: one call,
            // already confirmed.
            return render(out, level, setLogger(mbean, target, level, reason, tier, force), target, tier, reason);
        }
        List<String> matches = Picker.matchingNames(mbean, target);
        if (!interactive) {
            // pattern-selection-semantics.md Decision #4: fail fast rather than block forever on a read
            // from a stdin nothing will ever write to.
            throw new CliError(CliError.USAGE, matches.isEmpty()
                    ? "'" + target + "' matches no currently-known logger. Pass --yes to apply " + level
                            + " anyway (this affects only loggers that exist right now)."
                    : "'" + target + "' matches " + matches.size() + " currently-known logger"
                            + (matches.size() == 1 ? "" : "s") + ". Pass --yes to apply " + level
                            + " to all of them non-interactively (this affects only loggers that exist right now).");
        }
        if (matches.size() > Picker.MAX_LISTED) {
            // #10: too many to pick from, and a complete command has no narrower pattern to ask for --
            // the all-or-nothing preview, as before.
            printPatternPreview(out, matches);
            if (!prompter.askYesNo("", "Apply?", false)) {
                throw new Prompter.Cancelled();
            }
            return render(out, level, setLogger(mbean, target, level, reason, tier, force), target, tier, reason);
        }
        List<String> names = Picker.loggers(mbean, prompter, target, "set it anyway?", SetLoggerCommand::currently);
        if (names == null) {
            throw new Prompter.Cancelled();
        }
        return apply(mbean, out, err, names, level, tier, reason, force);
    }

    private int runGuided(LevelControlMXBean mbean, Prompter prompter, PrintStream err) {
        PrintStream out = prompter.out();
        // guided-commands.md #7: 'set logger Deployer' with no level has no meaning of its own to keep, so
        // a short name given here is looked up as the short name a log line shows, as a typed one is.
        String lookup = target == null ? null : Picker.shortNameShorthand(target);
        List<String> names = Picker.loggers(mbean, prompter, lookup, "set it anyway?", SetLoggerCommand::currently);
        if (names == null) {
            throw new Prompter.Cancelled();
        }
        String chosenLevel = Questions.askLevel(prompter, "Level? e.g. DEBUG, TRACE, OFF", false);
        Parser.TierChoice chosenTier = Questions.askTier(prompter, "How long?");
        String chosenReason = reason != null ? reason : Questions.askOptional(prompter, "Reason (optional)");
        boolean chosenForce = force || askForce(mbean, prompter, names, chosenLevel);

        out.println();
        out.println(names.size() == 1 ? "This is the command:" : "These are the commands:");
        for (String name : names) {
            out.println(INDENT + commandLine(name, chosenLevel, chosenTier, chosenReason)
                    + (chosenForce ? " --force" : ""));
        }
        if (!prompter.askYesNo("", "Apply?", true)) {
            throw new Prompter.Cancelled();
        }
        return apply(mbean, out, err, names, chosenLevel, chosenTier, chosenReason, chosenForce);
    }

    /**
     * set-logger-force.md "Guided set logger" (F7): lists the loggers under the chosen ones that keep a
     * level of their own, or an earlier force's, and asks whether to set them too -- default Yes. Asks
     * nothing when there are none.
     */
    private static boolean askForce(LevelControlMXBean mbean, Prompter prompter, List<String> names,
            String chosenLevel) {
        Map<String, String> behind = new java.util.TreeMap<>();
        for (String name : names) {
            for (LoggerInfoData row : mbean.listLoggers(DescendantLevel.ROOT.equals(name) ? null : name)) {
                if (!DescendantLevel.isUnder(name, row.getName())) {
                    continue;
                }
                String forcedBy = row.getOverrideForcedBy();
                boolean tiedHere = forcedBy != null && (forcedBy.equals(name) || DescendantLevel.isUnder(forcedBy, name));
                // A row's configured level is the native one; the vendor level, where it applies, is its own.
                String own = row.getVendorDefaultLevel() != null && !row.isResetToNative()
                        ? row.getVendorDefaultLevel()
                        : row.getConfiguredLevel();
                String keeps = row.isOverrideActive() ? (tiedHere ? row.getEffectiveLevel() : null) : own;
                if (keeps != null && !keeps.equals(chosenLevel)) {
                    behind.putIfAbsent(row.getName(), keeps);
                }
            }
        }
        if (behind.isEmpty()) {
            return false;
        }
        PrintStream out = prompter.out();
        String under = names.size() == 1 ? names.get(0) : "them";
        out.println(behind.size() + " logger" + (behind.size() == 1 ? "" : "s") + " under " + under + " ha"
                + (behind.size() == 1 ? "s its" : "ve their") + " own level and won't follow " + chosenLevel
                + (behind.size() > Picker.MAX_LISTED ? "." : ":"));
        if (behind.size() <= Picker.MAX_LISTED) {
            int width = behind.keySet().stream().mapToInt(String::length).max().orElse(0);
            for (Map.Entry<String, String> entry : behind.entrySet()) {
                out.println(INDENT + String.format("%-" + width + "s", entry.getKey()) + "  " + entry.getValue());
            }
        }
        return prompter.askYesNo("", "Set " + (behind.size() == 1 ? "it" : "them") + " to " + chosenLevel
                + " too?", true);
    }

    /**
     * The 6-argument operation unless {@code force} is asked for, so a plain {@code set logger} still works
     * against an agent from before doc/specs/set-logger-force.md, as {@code reset --to-native} does.
     */
    private static SetLevelResultData setLogger(LevelControlMXBean mbean, String name, String chosenLevel,
            String chosenReason, Parser.TierChoice chosenTier, boolean chosenForce) {
        return chosenForce
                ? mbean.setLogger(name, chosenLevel, chosenReason, chosenTier.tierName(), chosenTier.forSeconds(),
                        true, true)
                : mbean.setLogger(name, chosenLevel, chosenReason, chosenTier.tierName(), chosenTier.forSeconds(),
                        true);
    }

    /** #7: a matching logger's current level and where it comes from. */
    private static String currently(LoggerInfoData row) {
        String source = row.isOverrideActive() ? "override" : row.getVendorDefaultLevel() != null ? "vendor" : "native";
        return (row.getEffectiveLevel() == null ? "no level" : row.getEffectiveLevel()) + ", " + source;
    }

    /** The one-line command that sets {@code level} on {@code loggerName} (guided-commands.md #1). */
    static String commandLine(String loggerName, String level, Parser.TierChoice tier, String reason) {
        StringBuilder line = new StringBuilder("logctl set logger ").append(RuleExpression.quote(loggerName))
                .append(' ').append(level);
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
     * Sets {@code level} on each chosen exact name in turn. With several, a refused one doesn't stop the
     * others: refusals are printed after the successes and the exit code is the first failure's
     * (guided-add-rule.md G10). With one, a failure is the command's, reported by {@code Main}.
     */
    private int apply(LevelControlMXBean mbean, PrintStream out, PrintStream err, List<String> names,
            String chosenLevel, Parser.TierChoice chosenTier, String chosenReason, boolean chosenForce) {
        if (names.size() == 1) {
            return render(out, chosenLevel, setLogger(mbean, names.get(0), chosenLevel, chosenReason, chosenTier,
                    chosenForce), names.get(0), chosenTier, chosenReason);
        }
        List<LevelOverrideData> overrides = new ArrayList<>();
        // One warning per handler, however many of the loggers it would block.
        Map<String, HandlerFloorData> blocking = new LinkedHashMap<>();
        Map<String, DescendantLevelData> descendants = new LinkedHashMap<>();
        List<String> refusals = new ArrayList<>();
        int exitCode = CliError.OK;
        for (String name : names) {
            try {
                SetLevelResultData result = setLogger(mbean, name, chosenLevel, chosenReason, chosenTier,
                        chosenForce);
                overrides.addAll(result.getOverrides());
                for (DescendantLevelData descendant : result.getDescendants()) {
                    descendants.putIfAbsent(descendant.getLoggerName(), descendant);
                }
                for (HandlerFloorData floor : result.getBlockingHandlers()) {
                    blocking.putIfAbsent(floor.getHandlerRef(), floor);
                }
            } catch (RuntimeException e) {
                Main.Failure failure = Main.failureOf(e);
                refusals.add(name + ": " + failure.message());
                if (exitCode == CliError.OK) {
                    exitCode = failure.exitCode();
                }
            }
        }
        render(out, chosenLevel, new SetLevelResultData(overrides, new ArrayList<>(blocking.values()),
                new ArrayList<>(descendants.values())), null, chosenTier, chosenReason);
        for (String refusal : refusals) {
            err.println(refusal);
        }
        return exitCode;
    }

    /**
     * @param target named in the "nothing to set" line when nothing was set, and in the note's {@code --force}
     *               command; {@code null} for several targets, which get neither
     */
    private int render(PrintStream out, String appliedLevel, SetLevelResultData result, String target,
            Parser.TierChoice appliedTier, String appliedReason) {
        if (json) {
            out.println(Json.setLevelResult(result));
            return CliError.OK;
        }
        List<LevelOverrideData> overrides = result.getOverrides();
        if (overrides.isEmpty()) {
            if (target != null) {
                // A pattern currently matching no logger -- a one-time selection over nothing does
                // nothing, and nothing will happen later either (pattern-selection-semantics.md).
                out.println("'" + target + "' matches no currently-known logger; nothing to set.");
            }
            return CliError.OK;
        }
        for (LevelOverrideData override : overrides) {
            out.println(override.getLoggerName() + " → " + override.getLevel() + "   ("
                    + Commands.tierDetail(override.getTier(), override.getExpiresAt())
                    + (override.getForcedBy() != null ? ", forced" : "") + ")");
        }
        printDescendants(out, appliedLevel, result.getDescendants(), target, appliedTier, appliedReason);
        Commands.printBlockingHandlersWarning(out, appliedLevel, result.getBlockingHandlers());
        return CliError.OK;
    }

    /** At most this many names in the note after a plain {@code set logger} (F6). */
    private static final int NOTE_NAMES = 5;

    /**
     * set-logger-force.md: with {@code --force}, the loggers left alone because the operator set them
     * ("Kept: ..."); without, a note naming the ones that didn't follow and the command that sets them too.
     */
    private static void printDescendants(PrintStream out, String appliedLevel, List<DescendantLevelData> descendants,
            String target, Parser.TierChoice appliedTier, String appliedReason) {
        List<String> kept = new ArrayList<>();
        List<String> behind = new ArrayList<>();
        for (DescendantLevelData descendant : descendants) {
            switch (descendant.getKind()) {
                case "OPERATOR_OVERRIDE" -> kept.add(descendant.getLoggerName() + " (" + descendant.getLevel()
                        + ", set by you)");
                case "FORCED_EARLIER" -> behind.add(descendant.getLoggerName() + " (" + descendant.getLevel()
                        + ", forced earlier)");
                default -> behind.add(descendant.getLoggerName() + " (" + descendant.getLevel() + ")");
            }
        }
        if (!kept.isEmpty()) {
            out.println("Kept: " + String.join(", ", kept));
        }
        if (behind.isEmpty()) {
            return;
        }
        String names = String.join(", ", behind.subList(0, Math.min(NOTE_NAMES, behind.size())))
                + (behind.size() > NOTE_NAMES ? ", … and " + (behind.size() - NOTE_NAMES) + " more" : "");
        String under = target != null ? " under " + target : "";
        out.println("NOTE: " + behind.size() + " logger" + (behind.size() == 1 ? "" : "s") + under
                + (behind.size() == 1 ? " keeps its" : " keep their") + " own level and won't follow " + appliedLevel
                + ": " + names + ".");
        out.println(target != null && appliedTier != null
                ? INDENT + "To set " + (behind.size() == 1 ? "it" : "them") + " too: "
                        + commandLine(target, appliedLevel, appliedTier, appliedReason) + " --force"
                : INDENT + "Add --force to set them too.");
    }

    /** The all-or-nothing preview (pattern-selection-semantics.md "Confirmation and CLI behavior"). */
    private void printPatternPreview(PrintStream out, List<String> matches) {
        out.println("This will set " + level + " on " + matches.size() + " currently-known loggers matching '"
                + target + "':");
        for (String match : matches) {
            out.println(INDENT + match);
        }
        out.println("A logger created later that would also match this pattern is not affected — re-run "
                + "this command if you need it too.");
    }
}
