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
import org.logaperture.control.jmx.RecipeChangeData;
import org.logaperture.control.jmx.RecipeData;
import org.logaperture.control.jmx.RecipeDetailData;
import org.logaperture.control.jmx.RecipeFileProblemData;
import org.logaperture.control.jmx.RecipeListData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md "Commands", slice (a): {@code logctl list recipes} and {@code logctl show recipe}. */
class RecipeCommandsTest {

    private static final String JAR_URL =
            "jar:file:/opt/wildfly/modules/undertow-core-2.3.10.Final.jar!/META-INF/logaperture/recipes.yaml";

    private static final RecipeData SESSIONS = row("io.undertow:sessions",
            "Watch HTTP session creation, expiry and invalidation", "LIBRARY", "undertow-core-2.3.10.Final.jar",
            JAR_URL, List.of(), false, false);
    private static final RecipeData BILLING = row("com.acme:billing", "Trace invoice generation end to end",
            "VENDOR_DEFAULTS", "vendor defaults", "/opt/app/vendor-defaults.yaml", List.of(), false, false);

    private final FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(capturedErr, true, StandardCharsets.UTF_8);

    private int run(String... argv) {
        return Parser.parse(argv).command().run(mbean, out, err, InputStream.nullInputStream(), false);
    }

    private String out() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    // ---- list recipes ----

    @Test
    void list_showsIdSummaryAndSource() {
        mbean.recipeList = new RecipeListData(List.of(BILLING, SESSIONS), List.of());

        assertEquals(CliError.OK, run("list", "recipes"));

        assertEquals("""
                ID                    SUMMARY                                               SOURCE                          APPLIED
                com.acme:billing      Trace invoice generation end to end                   vendor defaults                 -
                io.undertow:sessions  Watch HTTP session creation, expiry and invalidation  undertow-core-2.3.10.Final.jar  -
                """, out());
    }

    @Test
    void list_mergedSources_ambiguousIds_shadowedRows_andBrokenFiles() {
        RecipeData merged = row("io.undertow:sessions", "s", "LIBRARY", "a.war!/WEB-INF/lib/u.jar",
                "vfs:/a", List.of("b.war!/WEB-INF/lib/u.jar"), false, false);
        RecipeData ambiguousA = row("com.acme:x", "s", "LIBRARY", "a.jar", "file:/a", List.of(), true, false);
        RecipeData ambiguousB = row("com.acme:x", "s", "LIBRARY", "b.jar", "file:/b", List.of(), true, false);
        RecipeData shadowed = row("com.acme:y", "s", "LIBRARY", "c.jar", "file:/c", List.of(), false, true);
        RecipeFileProblemData broken = new RecipeFileProblemData("FOLDER", "recipes/bad.yaml", "/r/bad.yaml",
                List.of("line 1: 'recipes' is missing"));
        mbean.recipeList = new RecipeListData(List.of(ambiguousA, ambiguousB, shadowed, merged), List.of(broken));

        assertEquals(CliError.OK, run("list", "recipes"));

        String text = out();
        assertTrue(text.contains("a.war!/WEB-INF/lib/u.jar (+1 more)"), text);
        assertFalse(text.contains("com.acme:y"), "a shadowed recipe is listed only with --verbose: " + text);
        assertTrue(text.contains("pick one with 'logctl show recipe <id> --from <source>'"), text);
        assertTrue(text.contains("1 recipe file could not be read -- see logctl list recipes --verbose"), text);
    }

    @Test
    void listVerbose_showsLocations_notes_andEachFilesErrors() {
        RecipeData shadowed = row("com.acme:y", "s", "LIBRARY", "c.jar", "file:/c", List.of(), false, true);
        RecipeData merged = row("io.undertow:sessions", "s", "LIBRARY", "a.jar", "file:/a",
                List.of("b.jar"), false, false);
        RecipeFileProblemData broken = new RecipeFileProblemData("FOLDER", "recipes/bad.yaml", "/r/bad.yaml",
                List.of("line 1: 'recipes' is missing"));
        mbean.recipeList = new RecipeListData(List.of(shadowed, merged), List.of(broken));

        assertEquals(CliError.OK, run("list", "recipes", "--verbose"));

        String text = out();
        assertTrue(text.contains("NOTE"), text);
        assertTrue(text.contains("com.acme:y            s        file:/c  -        shadowed"), text);
        assertTrue(text.contains("file:/a  -        also b.jar"), text);
        assertTrue(text.contains("Could not read /r/bad.yaml:\n  line 1: 'recipes' is missing"), text);
    }

    @Test
    void list_empty_saysWhenLibrariesShowUp() {
        assertEquals(CliError.OK, run("list", "recipes"));

        assertEquals("No recipes found. Libraries' recipes appear once the library has loaded.", out().strip());
    }

    @Test
    void list_json() {
        RecipeFileProblemData broken = new RecipeFileProblemData("LIBRARY", "x.jar", "jar:file:/x.jar!/r",
                List.of("line 2: bad"));
        mbean.recipeList = new RecipeListData(List.of(SESSIONS), List.of(broken));

        assertEquals(CliError.OK, run("list", "recipes", "--json"));

        assertEquals("{\"recipes\":[{\"id\":\"io.undertow:sessions\",\"summary\":\"Watch HTTP session creation, expiry "
                + "and invalidation\",\"sourceKind\":\"LIBRARY\",\"sourceLabel\":\"undertow-core-2.3.10.Final.jar\","
                + "\"sourceLocation\":\"" + JAR_URL + "\",\"otherSourceLabels\":[],\"ambiguous\":false,"
                + "\"shadowed\":false,\"offered\":true,\"appliedCount\":0,\"entryCount\":2,\"appliedTier\":null,"
                + "\"appliedExpiresAt\":null}],\"brokenFiles\":[{\"sourceKind\":\"LIBRARY\",\"sourceLabel\":\"x.jar\","
                + "\"sourceLocation\":\"jar:file:/x.jar!/r\",\"errors\":[\"line 2: bad\"]}]}", out().strip());
    }

    // ---- show recipe ----

    @Test
    void show_printsTheSpecsLayout() {
        mbean.recipeDetail = detail(SESSIONS,
                "Logs each session create/expire/invalidate with its id. Moderate volume\n"
                        + "under load. Session ids are sensitive; don't leave this on.",
                List.of(new RecipeChangeData("logger", "io.undertow.server.session", "INFO", "DEBUG",
                                "session lifecycle events", null),
                        new RecipeChangeData("logger", "io.undertow.request", "INFO", "DEBUG", null, null),
                        new RecipeChangeData("logger", "io.undertow.quiet", "DEBUG", "ERROR", null,
                                "skipped: would lower DEBUG -> ERROR (library recipes only raise levels)"),
                        new RecipeChangeData("rule drop", "io.undertow.poll", null, null,
                                "--message-contains \"poll ok\" --below ERROR --sample-full 5m", null),
                        new RecipeChangeData("logger", "io.undertow.new", null, "TRACE", null, null)));

        assertEquals(CliError.OK, run("show", "recipe", "io.undertow:sessions"));

        assertEquals("io.undertow:sessions — Watch HTTP session creation, expiry and invalidation\n"
                + "Source: " + JAR_URL + "\n"
                + "\n"
                + "  Logs each session create/expire/invalidate with its id. Moderate volume\n"
                + "  under load. Session ids are sensitive; don't leave this on.\n"
                + "\n"
                + "Changes:\n"
                + "  logger io.undertow.server.session  INFO -> DEBUG  session lifecycle events\n"
                + "  logger io.undertow.request         INFO -> DEBUG\n"
                + "  logger io.undertow.quiet                          skipped: would lower DEBUG -> ERROR (library recipes "
                + "only raise levels)\n"
                + "  rule drop io.undertow.poll                        --message-contains \"poll ok\" --below ERROR "
                + "--sample-full 5m\n"
                + "  logger io.undertow.new             — -> TRACE\n", out());
        assertArrayEquals(new String[] {"io.undertow:sessions", null}, mbean.showRecipeCalls.get(0));
    }

    @Test
    void show_passesFrom_andNamesOtherSources() {
        RecipeData merged = row("io.undertow:sessions", "s", "LIBRARY", "a.jar", "file:/a",
                List.of("b.jar"), false, false);
        mbean.recipeDetail = detail(merged, null,
                List.of(new RecipeChangeData("logger", "io.undertow", "INFO", "DEBUG", null, null)));

        assertEquals(CliError.OK, run("show", "recipe", "io.undertow:sessions", "--from", "a.jar"));

        assertArrayEquals(new String[] {"io.undertow:sessions", "a.jar"}, mbean.showRecipeCalls.get(0));
        assertTrue(out().contains("Also offered by: b.jar\n\nChanges:"), out());
    }

    @Test
    void show_json() {
        mbean.recipeDetail = detail(SESSIONS, "text",
                List.of(new RecipeChangeData("logger", "io.undertow", "INFO", "DEBUG", null, null)));

        assertEquals(CliError.OK, run("show", "recipe", "io.undertow:sessions", "--json"));

        String text = out().strip();
        assertTrue(text.startsWith("{\"id\":\"io.undertow:sessions\""), text);
        assertTrue(text.endsWith("\"description\":\"text\",\"changes\":[{\"kind\":\"logger\",\"target\":\"io.undertow\","
                + "\"currentLevel\":\"INFO\",\"newLevel\":\"DEBUG\",\"detail\":null,\"note\":null}],"
                + "\"fingerprint\":\"fp0123456789abcd\"}"), text);
    }

    // ---- usage ----

    @Test
    void usageErrors() {
        assertUsage("'show' needs 'recipe <id>'.", "show");
        assertUsage("'show' needs 'recipe <id>'.", "show", "rule", "r1");
        assertUsage("'show recipe' needs exactly one recipe id -- 'logctl list recipes' shows them.", "show", "recipe");
        assertUsage("'list recipes' takes no arguments.", "list", "recipes", "x");
        assertUsage("--from applies only to 'show recipe' or 'apply recipe'.", "list", "recipes", "--from", "a.jar");
        assertUsage("--from needs a source, as 'logctl list recipes' shows it.", "show", "recipe", "a:b", "--from");
        assertUsage("--verbose applies only to 'list rules' or 'list recipes'.", "show", "recipe", "a:b", "--verbose");
        assertUsage("--show-all does not apply to 'list recipes' -- '--verbose' also lists shadowed recipes.",
                "list", "recipes", "--show-all");
    }

    // ---- guided list ----

    @Test
    void guidedList_offersRecipes_andAnAmbiguousLetterIsAskedAgain() {
        mbean.recipeList = new RecipeListData(List.of(SESSIONS), List.of());
        ByteArrayInputStream answers = new ByteArrayInputStream("r\nrecipes\n".getBytes(StandardCharsets.UTF_8));

        int exit = Parser.parse(new String[] {"list"}, true).command().run(mbean, out, err, answers, true);

        assertEquals(CliError.OK, exit);
        assertTrue(out().contains("List loggers, handlers, rules or recipes? [loggers/handlers/rules/recipes]"), out());
        assertTrue(out().contains("Answer loggers, handlers, rules or recipes."), "'r' is ambiguous: " + out());
        assertTrue(out().contains("Command: logctl list recipes\n"), out());
        assertTrue(out().contains("io.undertow:sessions"), out());
    }

    /** An offered recipe with nothing applied. */
    private static RecipeData row(String id, String summary, String kind, String label, String location,
            List<String> others, boolean ambiguous, boolean shadowed) {
        return new RecipeData(id, summary, kind, label, location, others, ambiguous, shadowed, true, 0, 2, null, null);
    }

    private static RecipeDetailData detail(RecipeData recipe, String description, List<RecipeChangeData> changes) {
        return new RecipeDetailData(recipe, description, changes, "fp0123456789abcd");
    }

    private static void assertUsage(String message, String... argv) {
        CliError error = assertThrows(CliError.class, () -> Parser.parse(argv));
        assertEquals(CliError.USAGE, error.exitCode());
        assertEquals(message, error.getMessage());
    }
}
