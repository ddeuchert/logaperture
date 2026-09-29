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
import org.logaperture.control.jmx.RecipeApplyResultData;
import org.logaperture.control.jmx.RecipeChangeData;
import org.logaperture.control.jmx.RecipeData;
import org.logaperture.control.jmx.RecipeDetailData;
import org.logaperture.control.jmx.RecipeResetResultData;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code logctl apply recipe} and {@code logctl reset recipe}, complete and guided --
 * doc/specs/recipes.md "logctl apply recipe", "logctl reset recipe", #10 and B10-B12.
 */
final class RecipeCommands {

    private static final String INDENT = "  ";

    private RecipeCommands() {
    }

    // ---- apply ----

    /**
     * {@code logctl apply recipe <id>}: shows the changes and asks first, as a pattern {@code set
     * logger} does; {@code --yes} applies without asking, and is required without a terminal and with
     * {@code --json} (B10).
     */
    static Command apply(String id, String from, String reason, Parser.TierChoice tier, boolean yes, boolean json) {
        return (mbean, out, in, interactive) -> {
            String fingerprint = null;
            if (!yes) {
                if (json) {
                    throw new CliError(CliError.USAGE, "--json never asks -- add --yes to apply recipe " + id + ".");
                }
                if (!interactive) {
                    throw new CliError(CliError.USAGE, "'apply recipe' shows the changes and asks first -- run it in "
                            + "a terminal, or pass --yes to apply without asking.");
                }
                Prompter prompter = new Prompter(in, out);
                RecipeDetailData detail = mbean.showRecipe(id, from);
                printRecipe(out, detail);
                try {
                    if (!prompter.askYesNo("", "Apply?", true)) {
                        out.println("Not applied.");
                        return CliError.OK;
                    }
                } catch (Prompter.Cancelled cancelled) {
                    out.println("Not applied.");
                    return CliError.OK;
                }
                fingerprint = detail.getFingerprint(); // B1: apply exactly what was shown
            }
            return applyAndReport(mbean, out, id, from, fingerprint, reason, tier, json);
        };
    }

    /**
     * {@code logctl apply recipe} (or {@code logctl apply}) alone on a terminal -- #10, B12: pick one
     * recipe, see its changes, say how long, see the command, confirm.
     */
    static Command guidedApply(String reason) {
        return (mbean, out, in, interactive) -> {
            Prompter prompter = new Prompter(in, out);
            try {
                List<RecipeData> offered = mbean.listRecipes().getRecipes().stream()
                        .filter(row -> row.isOffered() && !row.isShadowed()).toList();
                if (offered.isEmpty()) {
                    out.println("No recipes found. Libraries' recipes appear once the library has loaded.");
                    return CliError.OK;
                }
                out.println("Recipes:");
                RecipeData picked = offered.get(pickOne(prompter, recipeRows(offered), "recipe") - 1);
                String from = picked.isAmbiguous() ? picked.getSourceLabel() : null;
                out.println();
                RecipeDetailData detail = mbean.showRecipe(picked.getId(), from);
                printRecipe(out, detail);
                Parser.TierChoice tier = Questions.askTier(prompter, "How long?");

                out.println();
                out.println("This is the command:");
                out.println(INDENT + applyCommandLine(picked.getId(), from, tier, reason));
                if (!prompter.askYesNo("", "Apply?", true)) {
                    throw new Prompter.Cancelled();
                }
                return applyAndReport(mbean, out, picked.getId(), from, detail.getFingerprint(), reason, tier, false);
            } catch (Prompter.Cancelled cancelled) {
                out.println("Not applied.");
                return CliError.OK;
            }
        };
    }

    /** B12: {@code --from} only when the id is ambiguous; the tier only when it isn't the default. */
    static String applyCommandLine(String id, String from, Parser.TierChoice tier, String reason) {
        StringBuilder line = new StringBuilder("logctl apply recipe ").append(RuleExpression.quote(id));
        String tierWords = Questions.tierWords(tier);
        if (!tierWords.isEmpty()) {
            line.append(' ').append(tierWords);
        }
        if (from != null) {
            line.append(" --from ").append(RuleExpression.quote(from));
        }
        if (reason != null) {
            line.append(" --reason ").append(RuleExpression.quote(reason));
        }
        return line.toString();
    }

    private static int applyAndReport(LevelControlMXBean mbean, PrintStream out, String id, String from,
            String fingerprint, String reason, Parser.TierChoice tier, boolean json) {
        RecipeApplyResultData result = mbean.applyRecipe(id, from, fingerprint, reason, tier.tierName(),
                tier.forSeconds());
        if (json) {
            out.println(Json.recipeApply(result));
            return CliError.OK;
        }
        int rule = 0;
        List<List<String>> rows = new ArrayList<>();
        for (RecipeChangeData change : result.getApplied()) {
            if (change.getKind().startsWith("rule")) {
                String ruleId = rule < result.getRuleIds().size() ? result.getRuleIds().get(rule++) : "";
                rows.add(List.of(ruleId + " " + change.getKind(), change.getTarget(), orEmpty(change.getDetail())));
            } else {
                rows.add(List.of(change.getKind(), change.getTarget(), "→ " + change.getNewLevel()));
            }
        }
        if (!rows.isEmpty()) {
            out.println(Format.table(rows));
        }
        for (RecipeChangeData skipped : result.getSkipped()) {
            out.println("Skipped " + skipped.getKind() + " " + skipped.getTarget() + " -- "
                    + skipped.getNote().replaceFirst("^skipped: ", ""));
        }
        int count = result.getApplied().size();
        out.println("Applied recipe " + id + " (" + count + (count == 1 ? " change, " : " changes, ")
                + Commands.tierDetail(result.getTier(), result.getExpiresAt()) + ").");
        return CliError.OK;
    }

    // ---- reset ----

    /** {@code logctl reset recipe <id>} -- #9, B11: no confirmation, it only undoes what {@code apply} did. */
    static Command reset(String id, boolean includeSticky, boolean json) {
        return (mbean, out, in, interactive) -> {
            RecipeResetResultData result = mbean.resetRecipe(id, includeSticky);
            if (json) {
                out.println(Json.recipeReset(result));
                return CliError.OK;
            }
            report(out, result);
            return CliError.OK;
        };
    }

    private static void report(PrintStream out, RecipeResetResultData result) {
        for (String logger : result.getLoggers()) {
            out.println("logger " + logger + " reset.");
        }
        for (String handler : result.getHandlers()) {
            out.println("handler " + handler + " reset.");
        }
        for (String rule : result.getRules()) {
            out.println("rule " + rule + " removed.");
        }
        for (String kept : result.getKeptSticky()) {
            out.println(kept + " (sticky, kept -- add --include-sticky)");
        }
        int count = result.getLoggers().size() + result.getHandlers().size() + result.getRules().size();
        if (count > 0) {
            out.println("Reset recipe " + result.getRecipeId() + " (" + count + (count == 1 ? " change)." : " changes)."));
        } else if (result.getKeptSticky().isEmpty()) {
            out.println("Nothing to reset: no changes carry recipe " + result.getRecipeId() + ".");
        } else {
            out.println("Nothing reset: every change recipe " + result.getRecipeId() + " made is sticky.");
        }
    }

    /**
     * {@code logctl reset recipe} alone on a terminal -- #10, B12: pick one or more applied recipes
     * (including ones no longer on offer), asked about sticky changes once, one command line each.
     */
    static Command guidedReset(boolean includeSticky) {
        return (mbean, out, in, interactive) -> {
            Prompter prompter = new Prompter(in, out);
            try {
                List<RecipeData> applied = mbean.listRecipes().getRecipes().stream()
                        .filter(row -> row.getAppliedTier() != null && !row.isShadowed())
                        .collect(java.util.stream.Collectors.toMap(RecipeData::getId, row -> row, (a, b) -> a,
                                java.util.LinkedHashMap::new))
                        .values().stream().toList();
                if (applied.isEmpty()) {
                    out.println("No recipe is applied.");
                    return CliError.OK;
                }
                out.println("Applied recipes:");
                List<String> rows = new ArrayList<>();
                List<List<String>> cells = new ArrayList<>();
                for (RecipeData row : applied) {
                    cells.add(List.of(row.getId(), row.isOffered() ? row.getSummary() : "(no longer offered)",
                            appliedCell(row)));
                }
                rows.addAll(List.of(Format.table(cells).split("\n")));
                List<Integer> chosen = Picker.choose(prompter, rows);
                if (chosen == null) {
                    throw new Prompter.Cancelled();
                }
                boolean sticky = includeSticky;
                boolean anySticky = chosen.stream().anyMatch(i -> "STICKY".equals(applied.get(i - 1).getAppliedTier()));
                if (!sticky && anySticky) {
                    sticky = prompter.askYesNo("", "Some of these changes are sticky -- they are kept across "
                            + "restarts. Reset them too?", false);
                }
                out.println();
                out.println(chosen.size() == 1 ? "This is the command:" : "These are the commands:");
                for (int index : chosen) {
                    out.println(INDENT + "logctl reset recipe " + RuleExpression.quote(applied.get(index - 1).getId())
                            + (sticky ? " --include-sticky" : ""));
                }
                if (!prompter.askYesNo("", "Apply?", true)) {
                    throw new Prompter.Cancelled();
                }
                for (int index : chosen) {
                    report(out, mbean.resetRecipe(applied.get(index - 1).getId(), sticky));
                }
                return CliError.OK;
            } catch (Prompter.Cancelled cancelled) {
                out.println("Not applied.");
                return CliError.OK;
            }
        };
    }

    // ---- shared ----

    /** {@code show recipe}'s layout -- also what {@code apply recipe} shows before asking. */
    static void printRecipe(PrintStream out, RecipeDetailData detail) {
        RecipeData recipe = detail.getRecipe();
        out.println(recipe.getId() + " — " + recipe.getSummary());
        out.println("Source: " + recipe.getSourceLocation());
        if (!recipe.getOtherSourceLabels().isEmpty()) {
            out.println("Also offered by: " + String.join(", ", recipe.getOtherSourceLabels()));
        }
        if (detail.getDescription() != null) {
            out.println();
            for (String line : detail.getDescription().split("\n", -1)) {
                out.println(line.isEmpty() ? "" : INDENT + line);
            }
        }
        out.println();
        out.println("Changes:");
        List<List<String>> rows = new ArrayList<>();
        for (RecipeChangeData change : detail.getChanges()) {
            String what = change.getKind() + " " + change.getTarget();
            // The level column stays narrow: a skip or refusal note, and a rule's options, go last.
            if (change.getNote() != null) {
                rows.add(List.of(what, "", change.getNote()));
            } else if (change.getNewLevel() != null) {
                String current = change.getCurrentLevel() == null ? Format.NONE : change.getCurrentLevel();
                rows.add(List.of(what, current + " -> " + change.getNewLevel(), orEmpty(change.getDetail())));
            } else {
                rows.add(List.of(what, "", orEmpty(change.getDetail())));
            }
        }
        for (String line : Format.table(rows).split("\n")) {
            out.println(INDENT + line);
        }
    }

    /**
     * B6: {@code -}, or the tier of the change ending soonest and how many entries still carry the
     * recipe's id -- {@code for, 22m left (2 of 3)}; for one no longer offered, {@code (2 changes)}.
     */
    static String appliedCell(RecipeData row) {
        if (row.getAppliedTier() == null) {
            return "-";
        }
        String tier = switch (row.getAppliedTier()) {
            case "SESSION" -> "session";
            case "STICKY" -> "sticky";
            default -> row.getAppliedExpiresAt() == null ? "for"
                    : "for, " + Format.relative(row.getAppliedExpiresAt()).replaceFirst("^in ", "") + " left";
        };
        String count = row.isOffered() ? row.getAppliedCount() + " of " + row.getEntryCount()
                : row.getAppliedCount() + (row.getAppliedCount() == 1 ? " change" : " changes");
        return tier + " (" + count + ")";
    }

    /** One row picked by number -- guided {@code alter rule}'s single-pick shape. */
    private static int pickOne(Prompter prompter, List<String> rows, String what) {
        PrintStream out = prompter.out();
        int width = Integer.toString(rows.size()).length();
        for (int i = 0; i < rows.size(); i++) {
            out.println(INDENT + String.format("%" + width + "d", i + 1) + "  " + rows.get(i));
        }
        while (true) {
            String answer = prompter.ask("", "Which " + what + "? (a number; Enter to cancel)");
            if (answer.isEmpty()) {
                throw new Prompter.Cancelled();
            }
            try {
                List<Integer> chosen = Picker.parseSelection(answer, rows.size());
                if (chosen.size() == 1) {
                    return chosen.get(0);
                }
                out.println("Pick one " + what + ".");
            } catch (IllegalArgumentException invalid) {
                out.println(invalid.getMessage());
            }
        }
    }

    /** B12: the same columns as {@code list recipes}. */
    private static List<String> recipeRows(List<RecipeData> recipes) {
        List<List<String>> cells = new ArrayList<>();
        for (RecipeData row : recipes) {
            cells.add(List.of(row.getId(), row.getSummary(), row.getSourceLabel()));
        }
        return List.of(Format.table(cells).split("\n"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
