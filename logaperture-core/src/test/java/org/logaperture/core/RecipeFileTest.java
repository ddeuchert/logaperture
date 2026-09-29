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
package org.logaperture.core;

import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.Level;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md "The recipe file": format, schema, namespaces, and what each source may contain. */
class RecipeFileTest {

    static final RecipeSource LIBRARY = new RecipeSource(RecipeSource.Kind.LIBRARY, "undertow-core.jar",
            "jar:file:/opt/undertow-core.jar!/META-INF/logaperture/recipes.yaml");
    static final RecipeSource FOLDER = new RecipeSource(RecipeSource.Kind.FOLDER, "recipes/undertow.yaml",
            "/home/app/.logaperture/recipes/undertow.yaml");

    /** The spec's own example. */
    static final String SPEC_EXAMPLE = """
            schemaVersion: 1
            namespace: io.undertow
            recipes:
              - name: sessions
                summary: Watch HTTP session creation, expiry and invalidation
                description: |
                  Logs each session create/expire/invalidate with its id. Moderate volume
                  under load. Session ids are sensitive; don't leave this on.
                loggers:
                  - name: io.undertow.server.session
                    level: DEBUG
                    reason: session lifecycle events
                  - name: io.undertow.request
                    level: DEBUG
            """;

    @Test
    void specExample_readsOneRecipe() {
        RecipeFileResult result = RecipeFile.parse(SPEC_EXAMPLE, LIBRARY);

        assertEquals(List.of(), result.errors());
        assertEquals(1, result.recipes().size());
        Recipe recipe = result.recipes().get(0);
        assertEquals("io.undertow:sessions", recipe.id());
        assertEquals("Watch HTTP session creation, expiry and invalidation", recipe.summary());
        assertEquals("Logs each session create/expire/invalidate with its id. Moderate volume\n"
                + "under load. Session ids are sensitive; don't leave this on.", recipe.description());
        assertEquals(List.of(
                new VendorDefaults.LoggerDefault("io.undertow.server.session", Level.DEBUG, "session lifecycle events"),
                new VendorDefaults.LoggerDefault("io.undertow.request", Level.DEBUG, null)), recipe.loggers());
        assertEquals(LIBRARY, recipe.source());
    }

    @Test
    void folderRecipe_mayCarryHandlersAndRules_withOptionalRuleIds() {
        RecipeFileResult result = RecipeFile.parse("""
                schemaVersion: 1
                namespace: com.acme
                recipes:
                  - name: billing
                    summary: Trace invoice generation end to end
                    loggers:
                      - name: com.acme.billing
                        level: TRACE
                    handlers:
                      - name: CONSOLE
                        level: AUTO
                    rules:
                      - action: trim
                        logger: com.acme.billing
                        throwable: java.net.ConnectException
                      - id: noise
                        action: drop
                        logger: com.acme.billing.poll
                        messageContains: "poll ok"
                """, FOLDER);

        assertEquals(List.of(), result.errors());
        Recipe recipe = result.recipes().get(0);
        assertEquals(HandlerLevelMode.AUTO, recipe.handlers().get(0).mode());
        assertEquals(List.of("rule-1", "noise"), recipe.rules().stream().map(VendorDefaults.RuleDefault::id).toList());
    }

    @Test
    void libraryRecipe_withHandlersOrRules_isAnError() {
        RecipeFileResult result = RecipeFile.parse("""
                schemaVersion: 1
                namespace: io.undertow
                recipes:
                  - name: sessions
                    summary: s
                    loggers:
                      - name: io.undertow
                        level: DEBUG
                    handlers:
                      - name: CONSOLE
                        level: DEBUG
                    rules:
                      - action: trim
                        logger: io.undertow
                """, LIBRARY);

        assertTrue(result.broken());
        assertTrue(result.recipes().isEmpty(), "all or nothing");
        assertContains(result.errors(), "line 9: a library's recipe may only set logger levels");
        assertContains(result.errors(), "line 12: a library's recipe may only set logger levels");
    }

    @Test
    void otherTopLevelKeys_areIgnoredWithANote() {
        RecipeFileResult result = RecipeFile.parse("""
                schemaVersion: 1
                namespace: io.undertow
                loggers:
                  - name: io.undertow
                    level: DEBUG
                recipes:
                  - name: sessions
                    summary: s
                    loggers:
                      - name: io.undertow.server.session
                        level: DEBUG
                """, LIBRARY);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("line 3: 'loggers' is ignored -- a recipe file is read only for schemaVersion, "
                + "namespace and recipes"), result.notes());
        assertEquals(1, result.recipes().size());
    }

    @Test
    void namespace_isRequired_andChecked() {
        assertContains(RecipeFile.parse("schemaVersion: 1\nrecipes:\n  - name: a\n    summary: s\n    loggers:\n"
                + "      - name: x\n        level: DEBUG\n", LIBRARY).errors(), "line 1: namespace is missing");
        for (String reserved : List.of("vendor", "logaperture", "org.logaperture", "org.logaperture.extras")) {
            assertContains(RecipeFile.parse(recipeFile(reserved), LIBRARY).errors(),
                    "namespace '" + reserved + "' is reserved");
        }
        assertContains(RecipeFile.parse(recipeFile("Io.Undertow"), LIBRARY).errors(),
                "namespace 'Io.Undertow' must be up to 64 characters");
        assertContains(RecipeFile.parse(recipeFile("a".repeat(65)), LIBRARY).errors(),
                "namespace '" + "a".repeat(65) + "' must be up to 64 characters");
        assertEquals(List.of(), RecipeFile.parse(recipeFile("org.jboss.resteasy.reactive"), LIBRARY).errors());
        assertEquals(List.of(), RecipeFile.parse(recipeFile("acme"), LIBRARY).errors(),
                "reverse-domain is recommended, not enforced");
    }

    @Test
    void recipeEntries_areChecked() {
        List<String> errors = RecipeFile.parse("""
                schemaVersion: 1
                namespace: com.acme
                recipes:
                  - name: Bad_Name
                    summary: s
                    loggers:
                      - name: a
                        level: DEBUG
                  - name: twice
                    summary: s
                    loggers:
                      - name: a
                        level: DEBUG
                  - name: twice
                    summary: s
                    loggers:
                      - name: a
                        level: DEBUG
                  - name: empty
                    summary: s
                  - name: multi
                    summary: |
                      two
                      lines
                    loggers:
                      - name: a
                        level: DEBUG
                        stateId: 0b0e7a6c-6f3e-4a51-9b0c-3a5b1c2d3e4f
                  - name: extra
                    summary: s
                    color: blue
                    loggers:
                      - name: a
                        level: LOUD
                """, FOLDER).errors();

        assertContains(errors, "line 4: recipe name 'Bad_Name' must be 1-40 characters");
        assertContains(errors, "line 14: recipe 'twice' is listed twice");
        assertContains(errors, "line 19: recipe 'empty' changes nothing -- give it loggers, handlers or rules");
        assertContains(errors, "line 22: summary must be one line");
        assertContains(errors, "line 28: unknown field 'stateId' in a logger entry");
        assertContains(errors, "line 31: unknown field 'color' in a recipe entry");
        assertContains(errors, "line 34: unknown level 'LOUD'");
    }

    @Test
    void missingRecipes_orEmptyList_isAnError() {
        assertContains(RecipeFile.parse("schemaVersion: 1\nnamespace: com.acme\n", FOLDER).errors(),
                "line 1: 'recipes' is missing");
        assertContains(RecipeFile.parse("schemaVersion: 1\nnamespace: com.acme\nrecipes: []\n", FOLDER).errors(),
                "'recipes' is empty");
    }

    @Test
    void syntaxAndEncodingErrors_rejectTheFile() {
        assertEquals(List.of("line 2: tabs are not allowed for indentation -- use spaces"),
                RecipeFile.parse("schemaVersion: 1\n\tnamespace: x\n", FOLDER).errors());
        assertEquals(List.of("the file is not valid UTF-8"),
                RecipeFile.parse(new byte[] {(byte) 0xC3, (byte) 0x28}, FOLDER).errors());
    }

    @Test
    void missingFile_isAReadError() {
        RecipeFileResult result = RecipeFile.load(Path.of("/does/not/exist.yaml"), FOLDER);

        assertTrue(result.broken());
        assertTrue(result.errors().get(0).startsWith("cannot read the file"), result.errors().get(0));
    }

    @Test
    void vendorDefaultsFile_offersRecipes_underItsNamespace() {
        VendorDefaults defaults = VendorDefaultsFile.parse("""
                schemaVersion: 1
                namespace: com.acme
                loggers:
                  - name: com.acme
                    level: WARN
                recipes:
                  - name: billing
                    summary: Trace invoice generation end to end
                    loggers:
                      - name: com.acme.billing
                        level: TRACE
                    rules:
                      - action: drop
                        logger: com.acme.billing.poll
                        messageContains: "poll ok"
                """, Path.of("/opt/app/vendor-defaults.yaml"), false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status());
        assertEquals(1, defaults.loggers().size(), "the file's own settings still apply");
        Recipe recipe = defaults.recipes().get(0);
        assertEquals("com.acme:billing", recipe.id());
        assertEquals(RecipeSource.Kind.VENDOR_DEFAULTS, recipe.source().kind());
        assertEquals("vendor defaults", recipe.source().label());
        assertEquals("/opt/app/vendor-defaults.yaml", recipe.source().location());
        assertEquals("1 logger, 1 recipe", defaults.summary());
    }

    @Test
    void vendorDefaultsFile_recipesWithoutNamespace_orBadRecipe_rejectsTheWholeFile() {
        VendorDefaults noNamespace = VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: com.acme
                    level: WARN
                recipes:
                  - name: billing
                    summary: s
                    loggers:
                      - name: com.acme.billing
                        level: TRACE
                """, Path.of("/opt/app/vendor-defaults.yaml"), false);
        assertEquals(VendorDefaults.Status.REJECTED, noNamespace.status());
        assertContains(noNamespace.errors(), "line 1: a vendor defaults file with 'recipes:' needs a 'namespace:'");
        assertTrue(noNamespace.loggers().isEmpty());

        VendorDefaults badRecipe = VendorDefaultsFile.parse("""
                schemaVersion: 1
                namespace: com.acme
                recipes:
                  - name: billing
                    summary: s
                """, Path.of("/opt/app/vendor-defaults.yaml"), false);
        assertEquals(VendorDefaults.Status.REJECTED, badRecipe.status());
    }

    @Test
    void vendorDefaultsFile_namespaceAlone_isFine() {
        VendorDefaults defaults = VendorDefaultsFile.parse("schemaVersion: 1\nnamespace: com.acme\n",
                Path.of("/opt/app/vendor-defaults.yaml"), false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status());
        assertTrue(defaults.recipes().isEmpty());
        assertFalse(defaults.errors().iterator().hasNext());
    }

    /** Export carries the started-from file's recipes over (doc/specs/recipes.md "Settled during implementation"). */
    @Test
    void exportedVendorFile_writesItsRecipes_andReadsThemBackUnchanged() {
        Path path = Path.of("/opt/app/vendor-defaults.yaml");
        VendorDefaults started = VendorDefaultsFile.parse("""
                schemaVersion: 1
                namespace: com.acme
                recipes:
                  - name: billing
                    summary: "Trace invoices: every step"
                    description: |
                      First paragraph, with a # that is text.

                      Second paragraph.
                    loggers:
                      - name: com.acme.billing
                        level: TRACE
                        reason: invoice steps
                    handlers:
                      - name: CONSOLE
                        level: AUTO
                    rules:
                      - action: trim
                        logger: com.acme.billing
                        throwable: java.net.ConnectException
                        frames: 2
                      - id: poll-noise
                        action: drop
                        logger: com.acme.billing.poll
                        messageContains: "poll ok"
                  - name: oneliner
                    summary: s
                    description: just one line
                    loggers:
                      - name: com.acme.x
                        level: DEBUG
                  - name: indented
                    summary: s
                    description: "  starts with spaces\\nsecond line"
                    loggers:
                      - name: com.acme.y
                        level: DEBUG
                """, path, false);
        assertEquals(List.of(), started.errors());

        String text = VendorDefaultsFile.write(new VendorDefaultsExport(List.of("exported"), List.of(), List.of(),
                null, null, List.of(), List.of(), java.util.Map.of(), List.of(), started.recipes()));
        VendorDefaults reread = VendorDefaultsFile.parse(text, path, false);

        assertEquals(List.of(), reread.errors(), text);
        assertEquals(started.recipes().size(), reread.recipes().size());
        for (int i = 0; i < started.recipes().size(); i++) {
            assertTrue(started.recipes().get(i).sameContent(reread.recipes().get(i)),
                    "recipe " + i + " reads back unchanged:\n" + text);
        }
        assertTrue(text.contains("    description: |\n      First paragraph, with a # that is text.\n\n"), text);
    }

    /** Code review of PR #118: an unnamed rule's label must not repeat an explicit id, or export can't read its own file. */
    @Test
    void unnamedRuleLabel_skipsExplicitIds_andExportStillReadsBack() {
        Path path = Path.of("/opt/app/vendor-defaults.yaml");
        VendorDefaults started = VendorDefaultsFile.parse("""
                schemaVersion: 1
                namespace: com.acme
                recipes:
                  - name: billing
                    summary: s
                    rules:
                      - action: trim
                        logger: com.acme.a
                      - id: rule-1
                        action: trim
                        logger: com.acme.b
                      - id: rule-3
                        action: trim
                        logger: com.acme.c
                      - action: trim
                        logger: com.acme.d
                      - action: trim
                        logger: com.acme.e
                """, path, false);

        assertEquals(List.of(), started.errors());
        assertEquals(List.of("rule-2", "rule-1", "rule-3", "rule-4", "rule-5"),
                started.recipes().get(0).rules().stream().map(VendorDefaults.RuleDefault::id).toList());

        String text = VendorDefaultsFile.write(new VendorDefaultsExport(List.of(), List.of(), List.of(), null, null,
                List.of(), List.of(), java.util.Map.of(), List.of(), started.recipes()));
        VendorDefaults reread = VendorDefaultsFile.parse(text, path, false);
        assertEquals(List.of(), reread.errors(), text);
        assertTrue(started.recipes().get(0).sameContent(reread.recipes().get(0)), text);
    }

    static String recipeFile(String namespace) {
        return "schemaVersion: 1\nnamespace: " + namespace + "\nrecipes:\n  - name: a\n    summary: s\n"
                + "    loggers:\n      - name: x\n        level: DEBUG\n";
    }

    /** {@code fragment} starts an error, or follows its {@code line N: } prefix. */
    static void assertContains(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(error -> error.startsWith(fragment)
                        || error.replaceFirst("^line \\d+: ", "").startsWith(fragment)),
                "expected an error starting with \"" + fragment + "\" in " + errors);
    }
}
