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

import org.junit.jupiter.api.Test;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.RecipeApplyResultData;
import org.logaperture.control.jmx.RecipeChangeData;
import org.logaperture.control.jmx.RecipeData;
import org.logaperture.control.jmx.RecipeDetailData;
import org.logaperture.control.jmx.RecipeListData;
import org.logaperture.control.jmx.RecipeResetResultData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md slice (b), CLI side: {@code apply recipe}, {@code reset recipe}, B6, B7, B10-B12. */
class RecipeApplyCommandsTest {

    private static final String FINGERPRINT = "fp0123456789abcd";

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);

    {
        RecipeData sessions = offered("io.undertow:sessions", "Watch HTTP sessions", "undertow-core.jar", false);
        mbean.recipeList = new RecipeListData(List.of(sessions), List.of());
        mbean.recipeDetail = new RecipeDetailData(sessions, null, List.of(
                new RecipeChangeData("logger", "io.undertow.server.session", "INFO", "DEBUG", null, null),
                new RecipeChangeData("rule trim", "io.undertow.request", null, null, "--frames 2 --below ERROR", null)),
                FINGERPRINT);
        mbean.recipeApplyResult = new RecipeApplyResultData(sessions, "FOR", "2026-09-28T14:32:00Z", List.of(
                new RecipeChangeData("logger", "io.undertow.server.session", "INFO", "DEBUG", null, null),
                new RecipeChangeData("rule trim", "io.undertow.request", null, null, "--frames 2 --below ERROR", null)),
                List.of(new RecipeChangeData("logger", "io.undertow.quiet", "DEBUG", "ERROR", null,
                        "skipped: would lower DEBUG -> ERROR (library recipes only raise levels)")),
                List.of("r7"));
    }

    private int run(String answers, boolean interactive, String... argv) {
        InputStream in = new ByteArrayInputStream(answers.getBytes(StandardCharsets.UTF_8));
        return Parser.parse(argv, interactive).command().run(mbean, out, out, in, interactive);
    }

    private String out() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    private static RecipeData offered(String id, String summary, String label, boolean ambiguous) {
        return new RecipeData(id, summary, "LIBRARY", label, "jar:file:/x/" + label + "!/r", List.of(), ambiguous,
                false, true, 0, 2, null, null);
    }

    // ---- apply ----

    @Test
    void applyWithYes_appliesWithoutAsking_andReportsEachChange() {
        assertEquals(CliError.OK, run("", false, "apply", "recipe", "io.undertow:sessions", "for", "30m", "--yes",
                "--reason", "INC-42"));

        assertArrayEquals(new Object[] {"io.undertow:sessions", null, null, "INC-42", "FOR", 1800L},
                mbean.applyRecipeCalls.get(0));
        String text = out();
        assertTrue(text.matches("(?s)logger +io.undertow.server.session  → DEBUG\n.*"), text);
        assertTrue(text.contains("r7 rule trim  io.undertow.request"), text);
        assertTrue(text.contains("Skipped logger io.undertow.quiet -- would lower DEBUG -> ERROR"), text);
        assertTrue(text.contains("Applied recipe io.undertow:sessions (2 changes, FOR, reverts"), text);
    }

    @Test
    void apply_onATerminal_showsTheChanges_asks_andAppliesWhatWasShown() {
        assertEquals(CliError.OK, run("\n", true, "apply", "recipe", "io.undertow:sessions"));

        String text = out();
        assertTrue(text.indexOf("Changes:") < text.indexOf("Apply?"), text);
        assertArrayEquals(new Object[] {"io.undertow:sessions", null, FINGERPRINT, null, "FOR", 14_400L},
                mbean.applyRecipeCalls.get(0), "B1: the fingerprint of what was shown; for 4h by default");
    }

    @Test
    void apply_answeredNo_appliesNothing() {
        assertEquals(CliError.OK, run("n\n", true, "apply", "recipe", "io.undertow:sessions"));

        assertTrue(out().contains("Not applied."), out());
        assertTrue(mbean.applyRecipeCalls.isEmpty());
    }

    @Test
    void apply_withoutATerminal_orWithJson_needsYes() {
        CliError noTerminal = assertThrows(CliError.class,
                () -> run("", false, "apply", "recipe", "io.undertow:sessions"));
        assertEquals(CliError.USAGE, noTerminal.exitCode());
        assertTrue(noTerminal.getMessage().contains("pass --yes"), noTerminal.getMessage());

        CliError json = assertThrows(CliError.class,
                () -> run("", true, "apply", "recipe", "io.undertow:sessions", "--json"));
        assertEquals("--json never asks -- add --yes to apply recipe io.undertow:sessions.", json.getMessage());
        assertTrue(mbean.applyRecipeCalls.isEmpty());
    }

    @Test
    void applyJson_printsTheResult() {
        assertEquals(CliError.OK, run("", false, "apply", "recipe", "io.undertow:sessions", "--yes", "--json"));

        String text = out().strip();
        assertTrue(text.startsWith("{\"id\":\"io.undertow:sessions\""), text);
        assertTrue(text.contains("\"tier\":\"FOR\",\"expiresAt\":\"2026-09-28T14:32:00Z\",\"applied\":[{"), text);
        assertTrue(text.endsWith("\"ruleIds\":[\"r7\"]}"), text);
    }

    @Test
    void guidedApply_picksARecipe_asksHowLong_printsTheCommand_andApplies() {
        assertEquals(CliError.OK, run("1\nfor 30m\n\n", true, "apply"));

        String text = out();
        assertTrue(text.contains("Recipes:\n  1  io.undertow:sessions  Watch HTTP sessions  undertow-core.jar"), text);
        assertTrue(text.contains("This is the command:\n  logctl apply recipe io.undertow:sessions for 30m\n"), text);
        assertArrayEquals(new Object[] {"io.undertow:sessions", null, FINGERPRINT, null, "FOR", 1800L},
                mbean.applyRecipeCalls.get(0));
    }

    @Test
    void guidedApply_anAmbiguousId_carriesFrom() {
        RecipeData a = offered("io.undertow:sessions", "s", "a.jar", true);
        RecipeData b = offered("io.undertow:sessions", "s", "b.jar", true);
        mbean.recipeList = new RecipeListData(List.of(a, b), List.of());

        assertEquals(CliError.OK, run("2\nsticky\n\n", true, "apply", "recipe"));

        assertTrue(out().contains("logctl apply recipe io.undertow:sessions sticky --from b.jar"), out());
        assertEquals("b.jar", mbean.applyRecipeCalls.get(0)[1]);
    }

    @Test
    void guidedApply_cancelled_appliesNothing() {
        assertEquals(CliError.OK, run("\n", true, "apply"));

        assertTrue(out().contains("Not applied."), out());
        assertTrue(mbean.applyRecipeCalls.isEmpty());
    }

    // ---- reset ----

    @Test
    void reset_reportsWhatWasPutBack_andStickyKept() {
        mbean.recipeResetResult = new RecipeResetResultData("io.undertow:sessions",
                List.of("io.undertow.server.session"), List.of(), List.of("r7"), List.of("handler CONSOLE"));

        assertEquals(CliError.OK, run("", false, "reset", "recipe", "io.undertow:sessions"));

        assertEquals("""
                logger io.undertow.server.session reset.
                rule r7 removed.
                handler CONSOLE (sticky, kept -- add --include-sticky)
                Reset recipe io.undertow:sessions (2 changes).
                """, out());
        assertArrayEquals(new Object[] {"io.undertow:sessions", false}, mbean.resetRecipeCalls.get(0));
    }

    @Test
    void reset_nothingTagged_saysSo() {
        assertEquals(CliError.OK, run("", false, "reset", "recipe", "io.undertow:sessions", "--include-sticky"));

        assertEquals("Nothing to reset: no changes carry recipe io.undertow:sessions.\n", out());
        assertArrayEquals(new Object[] {"io.undertow:sessions", true}, mbean.resetRecipeCalls.get(0));
    }

    @Test
    void resetJson_listsWhatWasResetAndKept() {
        mbean.recipeResetResult = new RecipeResetResultData("io.undertow:sessions", List.of("a"), List.of(),
                List.of(), List.of("rule r3"));

        run("", false, "reset", "recipe", "io.undertow:sessions", "--json");

        assertEquals("{\"id\":\"io.undertow:sessions\",\"loggers\":[\"a\"],\"handlers\":[],\"rules\":[],"
                + "\"keptSticky\":[\"rule r3\"]}", out().strip());
    }

    @Test
    void guidedReset_picksFromAppliedRecipes_andAsksAboutStickyOnce() {
        RecipeData sticky = new RecipeData("com.acme:billing", "Trace invoices", "FOLDER", "recipes/acme.yaml",
                "/r/acme.yaml", List.of(), false, false, true, 3, 3, "STICKY", null);
        RecipeData gone = new RecipeData("io.old:thing", null, null, null, null, List.of(), false, false, false, 1, 0,
                "SESSION", null);
        RecipeData notApplied = offered("io.undertow:sessions", "s", "u.jar", false);
        mbean.recipeList = new RecipeListData(List.of(sticky, notApplied, gone), List.of());

        assertEquals(CliError.OK, run("1-2\ny\n\n", true, "reset", "recipe"));

        String text = out();
        assertTrue(text.contains("com.acme:billing  Trace invoices       sticky (3 of 3)"), text);
        assertTrue(text.contains("io.old:thing      (no longer offered)  session (1 change)"), text);
        assertFalse(text.contains("io.undertow:sessions"), "only applied recipes are listed: " + text);
        assertTrue(text.contains("logctl reset recipe com.acme:billing --include-sticky"), text);
        assertEquals(2, mbean.resetRecipeCalls.size());
        assertEquals(true, mbean.resetRecipeCalls.get(0)[1]);
    }

    @Test
    void usageErrors() {
        assertUsage("'apply' needs 'recipe <id>' -- 'logctl list recipes' shows them.\n" + Parser.PROMPT_HINT,
                "apply");
        assertUsage("'apply' needs 'recipe <id>', got 'rule'.", "apply", "rule", "r1");
        assertUsage("'reset recipe' needs exactly one recipe id.\n" + Parser.PROMPT_HINT, "reset", "recipe");
        assertUsage("--from applies only to 'show recipe' or 'apply recipe'.", "reset", "recipe", "a:b", "--from", "x");
    }

    // ---- list columns ----

    @Test
    void listRecipes_appliedColumn_countsAndTiers_andNoLongerOffered() {
        String inThirty = Instant.now().plusSeconds(30 * 60 + 20).toString();
        RecipeData applied = new RecipeData("io.undertow:sessions", "Watch", "LIBRARY", "u.jar", "jar:u", List.of(),
                false, false, true, 2, 3, "FOR", inThirty);
        RecipeData gone = new RecipeData("io.old:thing", null, null, null, null, List.of(), false, false, false, 2, 0,
                "STICKY", null);
        mbean.recipeList = new RecipeListData(List.of(applied, gone), List.of());

        assertEquals(CliError.OK, run("", false, "list", "recipes"));

        String text = out();
        assertTrue(text.contains("u.jar   for, 30m left (2 of 3)"), text);
        assertTrue(text.contains("io.old:thing          (no longer offered)  —       sticky (2 changes)"), text);
    }

    @Test
    void listLoggers_showsARecipeColumn_onlyWhenARecipeMadeAnOverride() {
        mbean.loggers = new java.util.ArrayList<>(List.of(
                new LoggerInfoData("com.acme.a", "INFO", "DEBUG", true, "jmx", null, "SESSION", null, "system", null,
                        false, "com.acme:billing"),
                new LoggerInfoData("com.acme.b", "INFO", "WARN", true, "jmx", null, "SESSION", null, "system", null,
                        false, null)));

        run("", false, "list", "loggers");
        String withRecipe = out();
        assertTrue(withRecipe.contains("RECIPE"), withRecipe);
        assertTrue(withRecipe.contains("com.acme:billing"), withRecipe);

        capturedOut.reset();
        mbean.loggers = new java.util.ArrayList<>(List.of(mbean.loggers.get(1)));
        run("", false, "list", "loggers");
        assertFalse(out().contains("RECIPE"), out());
    }

    private static void assertUsage(String message, String... argv) {
        CliError error = assertThrows(CliError.class, () -> Parser.parse(argv));
        assertEquals(CliError.USAGE, error.exitCode());
        assertEquals(message, error.getMessage());
    }
}
