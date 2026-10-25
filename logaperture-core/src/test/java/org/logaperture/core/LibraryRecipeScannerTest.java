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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryRecipeScannerTest {

    /** doc/specs/quarkus-extension.md #6: one class loader, scanned with its parents, finds each library's file once. */
    @Test
    void forClassLoader_findsTheRecipeFilesThatLoaderSees(@TempDir Path dir) throws Exception {
        Path library = dir.resolve("library");
        Files.createDirectories(library.resolve("META-INF/logaperture"));
        Files.writeString(library.resolve(RecipeFile.RESOURCE), "schemaVersion: 1\n");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {library.toUri().toURL()}, null)) {
            List<URL> found = LibraryRecipeScanner.forClassLoader(loader).scan();

            assertEquals(1, found.size());
            assertTrue(found.get(0).toExternalForm().endsWith("library/" + RecipeFile.RESOURCE), found.toString());
        }
    }

    /** #6: the extension's loader and the application's are both scanned; a file both see is found once. */
    @Test
    void forClassLoaders_scansEachLoader_andFindsASharedFileOnce(@TempDir Path dir) throws Exception {
        Path shared = dir.resolve("shared");
        Path appOnly = dir.resolve("app");
        for (Path root : List.of(shared, appOnly)) {
            Files.createDirectories(root.resolve("META-INF/logaperture"));
            Files.writeString(root.resolve(RecipeFile.RESOURCE), "schemaVersion: 1\n");
        }

        try (URLClassLoader extension = new URLClassLoader(new URL[] {shared.toUri().toURL()}, null);
             URLClassLoader application = new URLClassLoader(new URL[] {appOnly.toUri().toURL()}, extension)) {
            List<URL> found = LibraryRecipeScanner.forClassLoaders(List.of(extension, application)).scan();

            assertEquals(2, found.size(), found.toString());
        }
    }
}
