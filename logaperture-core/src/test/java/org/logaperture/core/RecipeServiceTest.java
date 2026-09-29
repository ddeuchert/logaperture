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
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.HandlerInfo;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.Severity;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md "logctl show recipe", #4, #11 and epic #20's {@code --from}. */
class RecipeServiceTest {

    @TempDir
    Path tmp;

    private final Map<String, Level> liveLevels = Map.of(
            "io.undertow.server.session", Level.INFO,
            "io.undertow.request", Level.DEBUG);

    @Test
    void show_comparesEachChangeWithTheLiveLevel() throws IOException {
        RecipeService service = service(folder("""
                schemaVersion: 1
                namespace: com.acme
                recipes:
                  - name: billing
                    summary: Trace invoice generation end to end
                    description: |
                      Every invoice step.
                    loggers:
                      - name: io.undertow.server.session
                        level: DEBUG
                        reason: session lifecycle events
                      - name: com.acme.not.created.yet
                        level: TRACE
                    handlers:
                      - name: CONSOLE
                        level: AUTO
                    rules:
                      - action: drop
                        logger: com.acme.billing.poll
                        messageContains: "poll ok"
                """));

        RecipeDetail detail = service.showRecipe("com.acme:billing", null);

        assertEquals("Every invoice step.", detail.listing().recipe().description());
        assertEquals(List.of(
                new RecipeDetail.Change("logger", "io.undertow.server.session", "INFO", "DEBUG",
                        "session lifecycle events", null),
                new RecipeDetail.Change("logger", "com.acme.not.created.yet", null, "TRACE", null, null),
                new RecipeDetail.Change("handler", "CONSOLE", "INFO", "AUTO", null, null),
                new RecipeDetail.Change("rule drop", "com.acme.billing.poll", null, null,
                        "--message-contains \"poll ok\" --below ERROR --sample-full 5m", null)),
                detail.changes());
    }

    @Test
    void libraryEntry_thatWouldLowerTheLiveLevel_isShownSkipped() {
        Recipe library = new Recipe("io.undertow", "quiet", "s", null, List.of(
                new VendorDefaults.LoggerDefault("io.undertow.request", Level.ERROR, null),
                new VendorDefaults.LoggerDefault("io.undertow.server.session", Level.DEBUG, null)),
                List.of(), List.of(), RecipeCatalogTest.library("undertow.jar"));
        RecipeService service = service(new RecipeCatalog(List.of(library), Optional.empty(), LibraryRecipeScanner.none()));

        List<RecipeDetail.Change> changes = service.showRecipe("io.undertow:quiet", null).changes();

        assertEquals("skipped: would lower DEBUG -> ERROR (library recipes only raise levels)", changes.get(0).note());
        assertNull(changes.get(1).note(), "INFO -> DEBUG is a raise");
    }

    @Test
    void operatorRecipe_mayLowerALevel() {
        Recipe vendor = new Recipe("com.acme", "quiet", "s", null,
                List.of(new VendorDefaults.LoggerDefault("io.undertow.request", Level.ERROR, null)), List.of(), List.of(),
                new RecipeSource(RecipeSource.Kind.VENDOR_DEFAULTS, "vendor defaults", "/v.yaml"));
        RecipeService service = service(new RecipeCatalog(List.of(vendor), Optional.empty(), LibraryRecipeScanner.none()));

        assertNull(service.showRecipe("com.acme:quiet", null).changes().get(0).note());
    }

    @Test
    void protectedCategory_isShownRefused() {
        Recipe vendor = new Recipe("com.acme", "audit", "s", null,
                List.of(new VendorDefaults.LoggerDefault("com.acme.security", Level.TRACE, null)), List.of(), List.of(),
                new RecipeSource(RecipeSource.Kind.VENDOR_DEFAULTS, "vendor defaults", "/v.yaml"));
        RecipeService service = new RecipeService(CapabilityPolicy.allowAll(),
                new RecipeCatalog(List.of(vendor), Optional.empty(), LibraryRecipeScanner.none()), loggers(), handlers(),
                rules(), name -> name.startsWith("com.acme.security"));

        assertEquals("refused: com.acme.security is a protected category",
                service.showRecipe("com.acme:audit", null).changes().get(0).note());
    }

    @Test
    void unknownId_orAmbiguousId_explainsItself_andFromPicksOne() {
        Recipe a = RecipeCatalogTest.recipe("io.undertow", "sessions", Level.DEBUG, RecipeCatalogTest.library("a.jar"));
        Recipe b = RecipeCatalogTest.recipe("io.undertow", "sessions", Level.TRACE, RecipeCatalogTest.library("b.jar"));
        RecipeService service = service(new RecipeCatalog(List.of(a, b), Optional.empty(), LibraryRecipeScanner.none()));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> service.showRecipe("io.undertow:nope", null));
        assertTrue(unknown.getMessage().startsWith("no recipe named 'io.undertow:nope'"), unknown.getMessage());

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class,
                () -> service.showRecipe("io.undertow:sessions", null));
        assertEquals("2 different recipes are named 'io.undertow:sessions' (from a.jar, b.jar) -- pick one with "
                + "--from <source>", ambiguous.getMessage());

        assertEquals(b, service.showRecipe("io.undertow:sessions", "b.jar").listing().recipe());
        assertEquals(a, service.showRecipe("io.undertow:sessions", a.source().location()).listing().recipe());

        IllegalArgumentException wrongFrom = assertThrows(IllegalArgumentException.class,
                () -> service.showRecipe("io.undertow:sessions", "c.jar"));
        assertEquals("no recipe 'io.undertow:sessions' from 'c.jar' -- it's offered by: a.jar, b.jar",
                wrongFrom.getMessage());
    }

    @Test
    void doctor_reportsBrokenFiles_okWhenClean_nothingWithoutFiles() throws IOException {
        assertEquals(List.of(), service(new RecipeCatalog(List.of(), Optional.empty(), LibraryRecipeScanner.none()))
                .recipeFindings());

        Path folder = Files.createDirectory(tmp.resolve("recipes"));
        Files.writeString(folder.resolve("good.yaml"), RecipeFileTest.recipeFile("com.acme"));
        RecipeService service = service(new RecipeCatalog(List.of(), Optional.of(folder), LibraryRecipeScanner.none()));
        List<DoctorFinding> clean = service.recipeFindings();
        assertEquals(1, clean.size());
        assertEquals(Severity.OK, clean.get(0).severity());

        Files.writeString(folder.resolve("bad.yaml"), "schemaVersion: 1\n");
        List<DoctorFinding> findings = service.recipeFindings();
        assertEquals(1, findings.size());
        DoctorFinding finding = findings.get(0);
        assertEquals(RecipeService.CHECK, finding.check());
        assertEquals(Severity.INFO, finding.severity());
        assertEquals("recipes/bad.yaml", finding.subject());
        assertTrue(finding.detail().contains("'recipes' is missing"), finding.detail());
        assertTrue(finding.suggestedFix().startsWith("Fix the file"), finding.suggestedFix());
    }

    @Test
    void everyOperation_needsView() {
        RecipeService denied = new RecipeService(CapabilityPolicy.denyAll(),
                new RecipeCatalog(List.of(), Optional.empty(), LibraryRecipeScanner.none()), loggers(), handlers(), rules());

        assertThrows(CapabilityDeniedException.class, denied::listRecipes);
        assertThrows(CapabilityDeniedException.class, () -> denied.showRecipe("a:b", null));
        assertThrows(CapabilityDeniedException.class, denied::recipeFindings);
    }

    private RecipeCatalog folder(String content) throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("recipes"));
        Files.writeString(folder.resolve("acme.yaml"), content);
        return new RecipeCatalog(List.of(), Optional.of(folder), LibraryRecipeScanner.none());
    }

    private RecipeService service(RecipeCatalog catalog) {
        return new RecipeService(CapabilityPolicy.allowAll(), catalog, loggers(), handlers(), rules());
    }

    /** Only {@code listLoggers} is called: each known logger's live level, prefix-filtered as the real one is. */
    private LevelControlOperations loggers() {
        return (LevelControlOperations) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {LevelControlOperations.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("listLoggers")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String filter = (String) args[0];
                    return liveLevels.entrySet().stream()
                            .filter(entry -> filter == null || entry.getKey().startsWith(filter))
                            .map(entry -> new LoggerInfo(entry.getKey(), null, entry.getValue(), false, null, null, null,
                                    null))
                            .toList();
                });
    }

    /** Only {@code listRules} is called, by {@code list recipes}: no rules. */
    private RuleOperations rules() {
        return (RuleOperations) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RuleOperations.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("listRules")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return List.of();
                });
    }

    private HandlerLevelControlOperations handlers() {
        return (HandlerLevelControlOperations) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HandlerLevelControlOperations.class}, (proxy, method, args) -> {
                    if (method.getName().equals("listHandlerOverrides")) {
                        return List.of();
                    }
                    if (!method.getName().equals("listHandlers")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return List.of(new HandlerInfo("CONSOLE", Level.INFO, false, null, null, false, null, null, null,
                            null, null));
                });
    }
}
