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
import org.logaperture.api.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md "Where recipes come from": the three sources, discovery, caching and collisions (epic #20). */
class RecipeCatalogTest {

    @TempDir
    Path tmp;

    // ---- collisions (epic #20) ----

    @Test
    void identicalLibraryRecipes_mergeIntoOneListing() {
        Recipe a = recipe("io.undertow", "sessions", Level.DEBUG, library("a.war!/WEB-INF/lib/undertow.jar"));
        Recipe b = recipe("io.undertow", "sessions", Level.DEBUG, library("b.war!/WEB-INF/lib/undertow.jar"));

        List<RecipeListing> listings = RecipeCatalog.listings(List.of(a, b));

        assertEquals(1, listings.size());
        assertEquals(List.of(a.source(), b.source()), listings.get(0).sources());
        assertFalse(listings.get(0).ambiguous());
    }

    @Test
    void differingLibraryRecipes_areEachListed_andAmbiguous() {
        Recipe a = recipe("io.undertow", "sessions", Level.DEBUG, library("a.jar"));
        Recipe b = recipe("io.undertow", "sessions", Level.TRACE, library("b.jar"));

        List<RecipeListing> listings = RecipeCatalog.listings(List.of(b, a));

        assertEquals(List.of("a.jar", "b.jar"), listings.stream().map(l -> l.recipe().source().label()).toList());
        assertTrue(listings.stream().allMatch(RecipeListing::ambiguous));
    }

    @Test
    void operatorPlacedRecipe_shadowsTheLibrarys() {
        Recipe lib = recipe("io.undertow", "sessions", Level.DEBUG, library("undertow.jar"));
        Recipe folder = recipe("io.undertow", "sessions", Level.TRACE,
                new RecipeSource(RecipeSource.Kind.FOLDER, "recipes/undertow.yaml", "/r/undertow.yaml"));
        Recipe other = recipe("com.acme", "billing", Level.DEBUG, library("acme.jar"));

        List<RecipeListing> listings = RecipeCatalog.listings(List.of(lib, other, folder));

        assertEquals(3, listings.size());
        assertEquals("com.acme:billing", listings.get(0).id());
        RecipeListing winner = listings.get(1);
        assertEquals(folder, winner.recipe());
        assertFalse(winner.shadowed());
        assertFalse(winner.ambiguous(), "a shadowed recipe doesn't make the id ambiguous");
        RecipeListing shadowed = listings.get(2);
        assertEquals(lib, shadowed.recipe());
        assertTrue(shadowed.shadowed());
    }

    // ---- recipes folder ----

    @Test
    void folder_readsEveryYamlFile_andReportsBrokenOnes() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("recipes"));
        Files.writeString(folder.resolve("undertow.yaml"), RecipeFileTest.recipeFile("io.undertow"));
        Files.writeString(folder.resolve("broken.yaml"), "schemaVersion: 1\n");
        Files.writeString(folder.resolve("notes.txt"), "not a recipe file");

        RecipeList list = new RecipeCatalog(List.of(), Optional.of(folder), LibraryRecipeScanner.none()).read();

        assertEquals(List.of("io.undertow:a"), list.recipes().stream().map(RecipeListing::id).toList());
        RecipeSource source = list.recipes().get(0).recipe().source();
        assertEquals(RecipeSource.Kind.FOLDER, source.kind());
        assertEquals("recipes/undertow.yaml", source.label());
        assertEquals(folder.resolve("undertow.yaml").toString(), source.location());
        assertEquals(1, list.brokenFiles().size());
        assertEquals("recipes/broken.yaml", list.brokenFiles().get(0).source().label());
    }

    @Test
    void missingFolder_offersNothing() {
        RecipeList list = new RecipeCatalog(List.of(), Optional.of(tmp.resolve("absent")),
                LibraryRecipeScanner.none()).read();

        assertTrue(list.recipes().isEmpty());
        assertTrue(list.brokenFiles().isEmpty());
    }

    @Test
    void folderFile_droppedInLater_isPickedUpWithoutARestart() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("recipes"));
        RecipeCatalog catalog = new RecipeCatalog(List.of(), Optional.of(folder), LibraryRecipeScanner.none());
        assertTrue(catalog.read().recipes().isEmpty());

        Files.writeString(folder.resolve("acme.yaml"), RecipeFileTest.recipeFile("com.acme"));

        assertEquals(1, catalog.read().recipes().size());
    }

    // ---- libraries ----

    @Test
    void libraries_areFoundThroughLoadedClassesLoaders_inAJarAndADirectory() throws Exception {
        Path jar = jarWith(tmp.resolve("undertow-core-2.3.10.Final.jar"), RecipeFileTest.recipeFile("io.undertow"));
        Path classes = dirWith(tmp.resolve("classes"), RecipeFileTest.recipeFile("com.acme"));
        try (MarkerLoader jarLoader = new MarkerLoader(jar.toUri().toURL());
                MarkerLoader dirLoader = new MarkerLoader(classes.toUri().toURL())) {
            Class<?>[] loaded = {jarLoader.marker(), dirLoader.marker(), String.class};
            RecipeCatalog catalog = new RecipeCatalog(List.of(), Optional.empty(), new LibraryRecipeScanner(() -> loaded));

            RecipeList list = catalog.read();

            assertEquals(List.of("com.acme:a", "io.undertow:a"), list.recipes().stream().map(RecipeListing::id).toList());
            assertEquals("classes", list.recipes().get(0).recipe().source().label());
            assertEquals("undertow-core-2.3.10.Final.jar", list.recipes().get(1).recipe().source().label());
            assertEquals(RecipeSource.Kind.LIBRARY, list.recipes().get(1).recipe().source().kind());
        }
    }

    @Test
    void theSameResource_seenThroughTwoLoaders_isListedOnce() throws Exception {
        Path classes = dirWith(tmp.resolve("classes"), RecipeFileTest.recipeFile("com.acme"));
        try (MarkerLoader one = new MarkerLoader(classes.toUri().toURL());
                MarkerLoader two = new MarkerLoader(classes.toUri().toURL())) {
            Class<?>[] loaded = {one.marker(), two.marker()};

            List<URL> found = new LibraryRecipeScanner(() -> loaded).scan();

            assertEquals(1, found.size());
        }
    }

    @Test
    void libraryFile_isReReadWhenItChanges_andBrokenOnesAreReported() throws Exception {
        Path classes = dirWith(tmp.resolve("classes"), RecipeFileTest.recipeFile("com.acme"));
        Path file = classes.resolve(RecipeFile.RESOURCE);
        try (MarkerLoader loader = new MarkerLoader(classes.toUri().toURL())) {
            Class<?>[] loaded = {loader.marker()};
            RecipeCatalog catalog = new RecipeCatalog(List.of(), Optional.empty(), new LibraryRecipeScanner(() -> loaded));
            assertEquals(1, catalog.read().recipes().size());

            Files.writeString(file, "schemaVersion: 1\nnamespace: com.acme\n");
            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 60_000));
            RecipeList list = catalog.read();

            assertTrue(list.recipes().isEmpty());
            assertEquals("line 1: 'recipes' is missing -- a recipe file lists its recipes under 'recipes:'",
                    list.brokenFiles().get(0).errors().get(0));
        }
    }

    @Test
    void vendorRecipes_areListedWithTheRest() {
        Recipe vendor = recipe("com.acme", "billing", Level.TRACE, new RecipeSource(RecipeSource.Kind.VENDOR_DEFAULTS,
                RecipeSource.VENDOR_DEFAULTS_LABEL, "/opt/app/vendor-defaults.yaml"));

        RecipeList list = new RecipeCatalog(List.of(vendor), Optional.empty(), LibraryRecipeScanner.none()).read();

        assertEquals(List.of(vendor), list.recipes().stream().map(RecipeListing::recipe).toList());
    }

    // ---- labels ----

    @Test
    void label_namesTheJar_orTheDeploymentPath() throws Exception {
        assertEquals("undertow-core-2.3.10.Final.jar", LibraryRecipeScanner.label(new URL(
                "jar:file:/opt/wildfly/modules/system/layers/base/io/undertow/core/main/undertow-core-2.3.10.Final.jar!/"
                        + RecipeFile.RESOURCE)));
        assertEquals("app.war!/WEB-INF/lib/acme-billing.jar", LibraryRecipeScanner.label(new URL(
                "jar:file:/opt/wildfly/standalone/tmp/vfs/deployment/app.war/WEB-INF/lib/acme-billing.jar!/"
                        + RecipeFile.RESOURCE)));
        assertEquals("app.war!/WEB-INF/classes", LibraryRecipeScanner.label(new URL(
                "file:/opt/wildfly/standalone/deployments/app.war/WEB-INF/classes/" + RecipeFile.RESOURCE)));
        assertEquals("classes", LibraryRecipeScanner.label(new URL("file:/tmp/build/classes/" + RecipeFile.RESOURCE)));
    }

    // ---- helpers ----

    static Recipe recipe(String namespace, String name, Level level, RecipeSource source) {
        return new Recipe(namespace, name, "summary", null,
                List.of(new VendorDefaults.LoggerDefault(namespace, level, null)), List.of(), List.of(), source);
    }

    static RecipeSource library(String label) {
        return new RecipeSource(RecipeSource.Kind.LIBRARY, label, "jar:file:/x/" + label + "!/" + RecipeFile.RESOURCE);
    }

    private static Path jarWith(Path jar, String recipes) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(RecipeFile.RESOURCE));
            out.write(recipes.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static Path dirWith(Path dir, String recipes) throws IOException {
        Path file = dir.resolve(RecipeFile.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, recipes);
        return dir;
    }

    /** Something for a loader to have loaded, so the scanner finds that loader among the loaded classes. */
    public static final class Marker {
    }

    /** A loader over one URL, with no parent, that defines its own copy of {@link Marker}. */
    private static final class MarkerLoader extends URLClassLoader {
        MarkerLoader(URL url) {
            super(new URL[] {url}, null);
        }

        Class<?> marker() throws IOException {
            String resource = Marker.class.getName().replace('.', '/') + ".class";
            try (InputStream in = RecipeCatalogTest.class.getClassLoader().getResourceAsStream(resource)) {
                byte[] bytes = in.readAllBytes();
                return defineClass(Marker.class.getName(), bytes, 0, bytes.length);
            }
        }
    }
}
