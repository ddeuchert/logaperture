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

import org.logaperture.api.SampleFullPolicy;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.RuleData;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * {@code logctl alter rule} with a part left out, on a terminal (doc/specs/guided-commands.md #16–#18):
 * without an id it picks one rule from a list; without changes it lists the rule's parts with their
 * current values and asks only for the ones picked. The answers become an ordinary {@code alter rule}
 * command naming only what changed (doc/specs/alter-rule.md "Patch semantics"), printed and confirmed.
 */
final class GuidedAlterRuleCommand implements Command {

    private static final String INDENT = "  ";
    private static final long DEFAULT_SAMPLE_FULL_MILLIS = SampleFullPolicy.DEFAULT_INTERVAL.toMillis();

    /** One part of a rule the operator can pick to change (#17). */
    private enum Part {
        MESSAGE("Message contains"),
        IGNORE_CASE("Ignore case"),
        THROWABLE("Exception class"),
        THROWABLE_MESSAGE("Exception message"),
        ANY_CAUSE("Any cause"),
        BELOW("Below level"),
        SAMPLE_FULL("Keep one full event every"),
        FRAMES("Stack frames to keep"),
        COLLAPSE("Collapse cause chain"),
        LIFETIME("Lifetime"),
        REASON("Reason");

        final String label;

        Part(String label) {
            this.label = label;
        }
    }

    private final String id;
    private final Commands.RuleAlteration given;

    /**
     * @param id    the rule named on the command line, or {@code null} to pick one (#16)
     * @param given the changes given on the command line, or {@code null} to ask which parts (#17)
     */
    GuidedAlterRuleCommand(String id, Commands.RuleAlteration given) {
        this.id = id;
        this.given = given;
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        Prompter prompter = new Prompter(in, out);
        try {
            List<RuleData> rules = mbean.listRules();
            RuleData rule;
            if (id == null) {
                if (rules.isEmpty()) {
                    out.println("No rules are attached; nothing to alter.");
                    return CliError.OK;
                }
                // The agent refuses to alter a vendor rule switched off until restart, so offering one would
                // only fail after every question has been answered.
                List<RuleData> alterable = rules.stream().filter(row -> !row.isToNative()).toList();
                if (alterable.isEmpty()) {
                    out.println("Every rule is a vendor rule switched off until restart -- 'logctl reset rule <id>' "
                            + "switches one back on first.");
                    return CliError.OK;
                }
                rule = pickRule(prompter, alterable);
            } else {
                rule = rules.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow(
                        () -> new CliError(CliError.USAGE, "No rule with id '" + id + "' -- see 'logctl list rules'."));
                if (rule.isToNative()) {
                    // The agent's own refusal, before any question rather than after the last one.
                    throw new CliError(CliError.USAGE, id + " is switched off until restart -- 'reset rule " + id
                            + "' switches it back on first.");
                }
            }
            Commands.RuleAlteration alteration = given != null ? given : askChanges(prompter, rule);
            if (alteration == null) {
                // #18: no empty 'alter rule' call.
                out.println("Nothing changed. Not applied.");
                return CliError.OK;
            }
            out.println();
            out.println("This is the command:");
            out.println(INDENT + commandLine(rule.getId(), alteration));
            if (!prompter.askYesNo("", "Apply?", true)) {
                throw new Prompter.Cancelled();
            }
            return Commands.alterRule(rule.getId(), alteration, false).run(mbean, out, in, interactive);
        } catch (Prompter.Cancelled cancelled) {
            out.println("Not applied.");
            return CliError.OK;
        }
    }

    // --- picking the rule (#16) -----------------------------------------------------------------

    /** Exactly one rule: {@code alter} changes one at a time. */
    private static RuleData pickRule(Prompter prompter, List<RuleData> rules) {
        PrintStream out = prompter.out();
        int idWidth = rules.stream().mapToInt(row -> row.getId().length()).max().orElse(0);
        int targetWidth = rules.stream().mapToInt(row -> (row.getAction() + " " + row.getLoggerName()).length())
                .max().orElse(0);
        int width = Integer.toString(rules.size()).length();
        out.println("Rules:");
        for (int i = 0; i < rules.size(); i++) {
            RuleData row = rules.get(i);
            out.println(INDENT + String.format("%" + width + "d", i + 1) + "  "
                    + String.format("%-" + idWidth + "s  %-" + targetWidth + "s  ", row.getId(),
                            row.getAction() + " " + row.getLoggerName())
                    + RuleExpression.of(row));
        }
        while (true) {
            String answer = prompter.ask("", "Which rule? (a number; Enter to cancel)");
            if (answer.isEmpty()) {
                throw new Prompter.Cancelled();
            }
            try {
                List<Integer> chosen = Picker.parseSelection(answer, rules.size());
                if (chosen.size() == 1) {
                    return rules.get(chosen.get(0) - 1);
                }
                out.println("Pick one rule -- 'alter rule' changes one at a time.");
            } catch (IllegalArgumentException invalid) {
                out.println(invalid.getMessage());
            }
        }
    }

    // --- the parts and their answers (#17) ------------------------------------------------------

    /** The alteration the answers add up to, or {@code null} when they change nothing (#18). */
    private static Commands.RuleAlteration askChanges(Prompter prompter, RuleData rule) {
        PrintStream out = prompter.out();
        boolean drop = rule.getAction().equals(AddRuleRequest.DROP);
        List<Part> parts = new ArrayList<>();
        for (Part part : Part.values()) {
            boolean applies = switch (part) {
                case SAMPLE_FULL -> drop;
                case FRAMES, COLLAPSE -> !drop;
                default -> true;
            };
            if (applies) {
                parts.add(part);
            }
        }
        Current current = new Current(rule);
        while (true) {
            out.println(rule.getId() + "  " + rule.getAction() + " " + rule.getLoggerName());
            int labelWidth = parts.stream().mapToInt(part -> part.label.length()).max().orElse(0);
            List<String> rows = new ArrayList<>();
            for (Part part : parts) {
                rows.add(String.format("%-" + labelWidth + "s  ", part.label) + current.display(part));
            }
            List<Integer> chosen = Picker.choose(prompter, rows);
            if (chosen == null) {
                throw new Prompter.Cancelled();
            }
            Current answered = current.copy();
            for (int index : chosen) {
                answered.ask(prompter, parts.get(index - 1));
            }
            if (drop && answered.message == null && answered.throwable == null && answered.throwableMessage == null) {
                out.println("A drop rule needs at least one of the message or exception matchers -- otherwise use "
                        + "'logctl reset rule " + rule.getId() + "' to remove it.");
                continue;
            }
            return answered.alterationFrom(current, out);
        }
    }

    /** A rule's parts, as it has them or as answered so far. */
    private static final class Current {
        final boolean vendorAsIs;
        final String lifetime;
        String message;
        boolean ignoreCase;
        String throwable;
        String throwableMessage;
        boolean anyCause;
        String levelAtMost;
        boolean sampleFull;
        long sampleFullMillis;
        int frames;
        boolean collapse;
        Parser.TierChoice tier;
        String reason;

        Current(RuleData rule) {
            vendorAsIs = rule.getOrigin() != null && !rule.isAltered();
            lifetime = vendorAsIs ? "vendor definition (a change makes it for 4h unless you pick this)"
                    : Commands.tierDetail(rule.getTier(), rule.getExpiresAt());
            message = rule.getMessageContains();
            ignoreCase = rule.isMessageIgnoreCase();
            throwable = rule.getThrowableType();
            throwableMessage = rule.getThrowableMessageContains();
            anyCause = rule.isAnyCause();
            levelAtMost = rule.getLevelAtMost();
            sampleFull = !Boolean.FALSE.equals(rule.getSampleFullEnabled());
            sampleFullMillis = rule.getSampleFullEveryMillis() != null ? rule.getSampleFullEveryMillis()
                    : DEFAULT_SAMPLE_FULL_MILLIS;
            frames = rule.getFrames() != null ? rule.getFrames() : 0;
            collapse = Boolean.TRUE.equals(rule.getCollapseCauses());
            reason = rule.getReason();
        }

        private Current(Current other) {
            vendorAsIs = other.vendorAsIs;
            lifetime = other.lifetime;
            message = other.message;
            ignoreCase = other.ignoreCase;
            throwable = other.throwable;
            throwableMessage = other.throwableMessage;
            anyCause = other.anyCause;
            levelAtMost = other.levelAtMost;
            sampleFull = other.sampleFull;
            sampleFullMillis = other.sampleFullMillis;
            frames = other.frames;
            collapse = other.collapse;
            tier = other.tier;
            reason = other.reason;
        }

        Current copy() {
            return new Current(this);
        }

        String display(Part part) {
            return switch (part) {
                case MESSAGE -> text(message);
                case IGNORE_CASE -> yesNo(ignoreCase);
                case THROWABLE -> orNone(throwable);
                case THROWABLE_MESSAGE -> text(throwableMessage);
                case ANY_CAUSE -> yesNo(anyCause);
                case BELOW -> below();
                case SAMPLE_FULL -> sampleFull ? RuleExpression.duration(sampleFullMillis) : "off";
                case FRAMES -> Integer.toString(frames);
                case COLLAPSE -> yesNo(collapse);
                case LIFETIME -> lifetime;
                case REASON -> orNone(reason);
            };
        }

        private String below() {
            return levelAtMost == null ? Format.NONE : RuleExpression.belowFor(levelAtMost);
        }

        void ask(Prompter prompter, Part part) {
            switch (part) {
                case MESSAGE -> message = askText(prompter, "Message contains", message);
                case IGNORE_CASE -> ignoreCase = prompter.askYesNo("", "Ignore case?", ignoreCase);
                case THROWABLE -> throwable = askText(prompter, "Exception class (fully qualified)", throwable);
                case THROWABLE_MESSAGE -> throwableMessage = askText(prompter, "Exception message contains",
                        throwableMessage);
                case ANY_CAUSE -> anyCause = prompter.askYesNo("", "Also look for the exception anywhere in the cause "
                        + "chain?", anyCause);
                case BELOW -> askBelow(prompter);
                case SAMPLE_FULL -> askSampleFull(prompter);
                case FRAMES -> askFrames(prompter);
                case COLLAPSE -> collapse = prompter.askYesNo("", "Collapse the cause chain too?", collapse);
                case LIFETIME -> askLifetime(prompter);
                case REASON -> {
                    String answer = prompter.ask("", "Reason [" + orNone(reason) + "]");
                    if (!answer.isEmpty()) {
                        reason = answer;
                    }
                }
            }
        }

        /** Enter keeps the value, {@code -} removes it (the {@code --no-} option), anything else replaces it. */
        private static String askText(Prompter prompter, String question, String value) {
            String answer = prompter.ask("", question + (value == null ? " (Enter to leave it empty)"
                    : " [" + value + "] ('-' to remove)"));
            if (answer.isEmpty()) {
                return value;
            }
            return answer.equals("-") ? null : answer;
        }

        private void askBelow(Prompter prompter) {
            while (true) {
                String answer = prompter.ask("", "Apply to events below which level? [" + below() + "]");
                if (answer.isEmpty()) {
                    return;
                }
                try {
                    levelAtMost = Parser.parseBelowLevel(answer);
                    return;
                } catch (CliError invalid) {
                    prompter.out().println(invalid.getMessage());
                }
            }
        }

        private void askSampleFull(Prompter prompter) {
            while (true) {
                String answer = prompter.ask("", "Keep one full event every... (a duration such as 5m, or off) ["
                        + display(Part.SAMPLE_FULL) + "]");
                if (answer.isEmpty()) {
                    return;
                }
                if (answer.equalsIgnoreCase("off")) {
                    sampleFull = false;
                    return;
                }
                try {
                    sampleFullMillis = Durations.parse(answer).toMillis();
                    sampleFull = true;
                    return;
                } catch (CliError invalid) {
                    prompter.out().println(invalid.getMessage());
                }
            }
        }

        private void askFrames(Prompter prompter) {
            while (true) {
                String answer = prompter.ask("", "Stack frames to keep [" + frames + "]");
                if (answer.isEmpty()) {
                    return;
                }
                try {
                    int n = Integer.parseInt(answer);
                    if (n >= 0) {
                        frames = n;
                        return;
                    }
                    prompter.out().println("Frames must be 0 or more.");
                } catch (NumberFormatException invalid) {
                    prompter.out().println("'" + answer + "' is not a number.");
                }
            }
        }

        private void askLifetime(Prompter prompter) {
            while (true) {
                String answer = prompter.ask("", "How long should the rule last? session, for <duration> (e.g. for "
                        + "30m), or sticky [keep: " + lifetime + "]");
                if (answer.isEmpty()) {
                    return;
                }
                try {
                    tier = Questions.parseTierAnswer(answer);
                    return;
                } catch (CliError invalid) {
                    prompter.out().println(invalid.getMessage());
                }
            }
        }

        /** Only what differs from {@code before}; {@code null} when nothing does (#18). */
        Commands.RuleAlteration alterationFrom(Current before, PrintStream out) {
            boolean changed = false;
            String messageContains = null;
            boolean clearMessage = false;
            if (!Objects.equals(message, before.message) || ignoreCase != before.ignoreCase) {
                if (message == null && before.message != null) {
                    clearMessage = true;
                    changed = true;
                } else if (message != null) {
                    // --message-contains and --message-contains-ignore-case each set the text and its case
                    // rule together, so a change to either gives both.
                    messageContains = message;
                    changed = true;
                } else {
                    out.println("There is no message to match, so ignoring case changes nothing.");
                }
            }
            String throwableType = null;
            boolean clearThrowable = false;
            if (!Objects.equals(throwable, before.throwable)) {
                clearThrowable = throwable == null;
                throwableType = throwable;
                changed = true;
            }
            String throwableMessageContains = null;
            boolean clearThrowableMessage = false;
            if (!Objects.equals(throwableMessage, before.throwableMessage)) {
                clearThrowableMessage = throwableMessage == null;
                throwableMessageContains = throwableMessage;
                changed = true;
            }
            Boolean anyCauseChange = anyCause != before.anyCause ? anyCause : null;
            String belowLevel = !Objects.equals(levelAtMost, before.levelAtMost) ? levelAtMost : null;
            Boolean sampleFullEnabled = null;
            Long sampleFullEveryMillis = null;
            if (sampleFull != before.sampleFull || (sampleFull && sampleFullMillis != before.sampleFullMillis)) {
                sampleFullEnabled = sampleFull;
                sampleFullEveryMillis = sampleFull ? sampleFullMillis : null;
            }
            Integer framesChange = frames != before.frames ? frames : null;
            Boolean collapseChange = collapse != before.collapse ? collapse : null;
            String reasonChange = !Objects.equals(reason, before.reason) ? reason : null;
            changed |= anyCauseChange != null || belowLevel != null || sampleFullEnabled != null
                    || framesChange != null || collapseChange != null || reasonChange != null || tier != null;
            if (!changed) {
                return null;
            }
            return new Commands.RuleAlteration(messageContains, messageContains != null && ignoreCase, clearMessage,
                    throwableType, clearThrowable, throwableMessageContains, clearThrowableMessage, anyCauseChange,
                    belowLevel, sampleFullEnabled, sampleFullEveryMillis, framesChange, collapseChange, reasonChange,
                    tier == null ? null : tier.tierName(), tier == null ? 0L : tier.forSeconds());
        }

        private static String text(String value) {
            return value == null ? Format.NONE : RuleExpression.quote(value);
        }

        private static String orNone(String value) {
            return value == null ? Format.NONE : value;
        }

        private static String yesNo(boolean value) {
            return value ? "yes" : "no";
        }
    }

    // --- the command (#1) -----------------------------------------------------------------------

    /** The one-line {@code alter rule} command naming exactly the parts {@code alteration} changes. */
    static String commandLine(String id, Commands.RuleAlteration alteration) {
        StringJoiner line = new StringJoiner(" ");
        line.add("logctl alter rule").add(RuleExpression.quote(id));
        if (alteration.clearMessage()) {
            line.add("--no-message-contains");
        } else if (alteration.messageContains() != null) {
            line.add(alteration.messageIgnoreCase() ? "--message-contains-ignore-case" : "--message-contains")
                    .add(RuleExpression.quote(alteration.messageContains()));
        }
        if (alteration.clearThrowable()) {
            line.add("--no-throwable");
        } else if (alteration.throwableType() != null) {
            line.add("--throwable").add(RuleExpression.quote(alteration.throwableType()));
        }
        if (alteration.clearThrowableMessage()) {
            line.add("--no-throwable-message-contains");
        } else if (alteration.throwableMessageContains() != null) {
            line.add("--throwable-message-contains").add(RuleExpression.quote(alteration.throwableMessageContains()));
        }
        if (alteration.anyCause() != null) {
            line.add(alteration.anyCause() ? "--any-cause" : "--no-any-cause");
        }
        if (alteration.belowLevel() != null) {
            line.add("--below").add(RuleExpression.belowFor(alteration.belowLevel()));
        }
        if (Boolean.FALSE.equals(alteration.sampleFullEnabled())) {
            line.add("--no-sample-full");
        } else if (Boolean.TRUE.equals(alteration.sampleFullEnabled())) {
            line.add("--sample-full").add(RuleExpression.duration(alteration.sampleFullEveryMillis()));
        }
        if (alteration.frames() != null) {
            line.add("--frames").add(Integer.toString(alteration.frames()));
        }
        if (alteration.collapseCauses() != null) {
            line.add(alteration.collapseCauses() ? "--collapse-causes" : "--no-collapse-causes");
        }
        if (alteration.tierName() != null) {
            // Spelled out even for 'for 4h': without a tier, 'alter' keeps the rule's lifetime.
            line.add(switch (alteration.tierName()) {
                case "SESSION" -> "session";
                case "STICKY" -> "sticky";
                default -> "for " + RuleExpression.duration(alteration.forSeconds() * 1000);
            });
        }
        if (alteration.reason() != null) {
            line.add("--reason").add(RuleExpression.quote(alteration.reason()));
        }
        return line.toString();
    }
}
