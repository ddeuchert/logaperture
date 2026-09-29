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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every recipe this JVM offers, from the three sources -- doc/specs/recipes.md "Where recipes
 * come from". Read on demand: each {@link #read()} rescans the libraries and re-reads the recipes
 * folder (so a file dropped in is picked up without a restart); the vendor defaults file's recipes
 * were read with it at startup. A library's parsed file is cached by URL and last-modified time,
 * so a second read only parses what's new; entries no longer found are dropped.
 */
public final class RecipeCatalog {

    /** Names the recipes folder; default {@code ${logaperture.home}/recipes}. */
    public static final String FOLDER_PROPERTY = "logaperture.recipes";

    private record Cached(long lastModified, RecipeFileResult result) {
    }

    private final List<Recipe> vendorRecipes;
    private final Optional<Path> folder;
    private final LibraryRecipeScanner scanner;
    private final Map<String, Cached> libraryCache = new HashMap<>();

    /**
     * @param vendorRecipes the vendor defaults file's recipes ({@link VendorDefaults#recipes()})
     * @param folder        the recipes folder, if any; a missing folder offers nothing
     */
    public RecipeCatalog(List<Recipe> vendorRecipes, Optional<Path> folder, LibraryRecipeScanner scanner) {
        this.vendorRecipes = List.copyOf(vendorRecipes);
        this.folder = Objects.requireNonNull(folder, "folder");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    /**
     * The recipes folder this JVM uses: {@code -Dlogaperture.recipes=<dir>}, else {@code
     * ${logaperture.home}/recipes} (home as the state file resolves it).
     */
    public static Optional<Path> defaultFolder() {
        try {
            String explicit = System.getProperty(FOLDER_PROPERTY);
            if (explicit != null && !explicit.isBlank()) {
                return Optional.of(Path.of(explicit).toAbsolutePath());
            }
            return Optional.of(FileStateStore.resolveHome().resolve("recipes").toAbsolutePath());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Reads every source now; see the class doc. */
    public synchronized RecipeList read() {
        List<RecipeFileResult> files = new ArrayList<>();
        files.addAll(folderFiles());
        files.addAll(libraryFiles());

        List<Recipe> all = new ArrayList<>(vendorRecipes);
        List<RecipeFileResult> broken = new ArrayList<>();
        for (RecipeFileResult file : files) {
            if (file.broken()) {
                broken.add(file);
            }
            all.addAll(file.recipes());
        }
        return new RecipeList(listings(all), broken);
    }

    /**
     * Groups {@code all} into listings by id -- epic #20: an operator-placed recipe (vendor
     * defaults, folder) shadows every library recipe with its id; among the rest, identical recipes
     * merge into one listing, and differing ones are each listed and marked ambiguous.
     */
    static List<RecipeListing> listings(List<Recipe> all) {
        Map<String, List<Recipe>> byId = new LinkedHashMap<>();
        for (Recipe recipe : all) {
            byId.computeIfAbsent(recipe.id(), id -> new ArrayList<>()).add(recipe);
        }
        List<RecipeListing> listings = new ArrayList<>();
        for (List<Recipe> sameId : byId.values()) {
            boolean operatorPlaced = sameId.stream().anyMatch(recipe -> recipe.source().operatorPlaced());
            List<Recipe> winners = new ArrayList<>();
            List<Recipe> shadowed = new ArrayList<>();
            for (Recipe recipe : sameId) {
                (operatorPlaced && !recipe.source().operatorPlaced() ? shadowed : winners).add(recipe);
            }
            List<RecipeListing> merged = merge(winners, false);
            boolean ambiguous = merged.size() > 1;
            for (RecipeListing listing : merged) {
                listings.add(new RecipeListing(listing.recipe(), listing.sources(), ambiguous, false));
            }
            listings.addAll(merge(shadowed, true));
        }
        listings.sort(Comparator.comparing(RecipeListing::id)
                .thenComparing(RecipeListing::shadowed)
                .thenComparing(listing -> listing.recipe().source().label()));
        return listings;
    }

    /** Identical recipes (by {@link Recipe#sameContent}) become one listing carrying every source. */
    private static List<RecipeListing> merge(List<Recipe> recipes, boolean shadowed) {
        List<Recipe> distinct = new ArrayList<>();
        List<List<RecipeSource>> sources = new ArrayList<>();
        for (Recipe recipe : recipes) {
            int same = -1;
            for (int i = 0; i < distinct.size() && same < 0; i++) {
                if (distinct.get(i).sameContent(recipe)) {
                    same = i;
                }
            }
            if (same < 0) {
                distinct.add(recipe);
                sources.add(new ArrayList<>(List.of(recipe.source())));
            } else {
                sources.get(same).add(recipe.source());
            }
        }
        List<RecipeListing> result = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            result.add(new RecipeListing(distinct.get(i), sources.get(i), false, shadowed));
        }
        return result;
    }

    private List<RecipeFileResult> folderFiles() {
        if (folder.isEmpty() || !Files.isDirectory(folder.get())) {
            return List.of();
        }
        Path dir = folder.get();
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
            stream.forEach(paths::add);
        } catch (IOException | RuntimeException e) {
            return List.of(RecipeFileResult.failed(new RecipeSource(RecipeSource.Kind.FOLDER,
                    String.valueOf(dir.getFileName()), dir.toString()), "cannot list the recipes folder: " + e));
        }
        paths.sort(Comparator.comparing(Path::toString));
        List<RecipeFileResult> results = new ArrayList<>();
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            RecipeSource source = new RecipeSource(RecipeSource.Kind.FOLDER,
                    dir.getFileName() + "/" + path.getFileName(), path.toString());
            results.add(RecipeFile.load(path, source));
        }
        return results;
    }

    private List<RecipeFileResult> libraryFiles() {
        List<RecipeFileResult> results = new ArrayList<>();
        Map<String, Cached> seen = new HashMap<>();
        for (URL url : scanner.scan()) {
            String key = url.toExternalForm();
            long lastModified = lastModified(url);
            Cached cached = libraryCache.get(key);
            if (cached == null || lastModified == 0 || cached.lastModified() != lastModified) {
                RecipeSource source = new RecipeSource(RecipeSource.Kind.LIBRARY, LibraryRecipeScanner.label(url), key);
                cached = new Cached(lastModified, RecipeFile.load(url, source));
            }
            seen.put(key, cached);
            results.add(cached.result());
        }
        libraryCache.clear();
        libraryCache.putAll(seen); // a library no longer loaded (undeployed) is forgotten
        return results;
    }

    /**
     * The resource's last-modified time, or {@code 0} when unknown (then it's re-read every time).
     * For a {@code jar:} or {@code file:} URL it's the file's own time: asking a {@code jar:}
     * connection would open the jar and leave it open. Anything else ({@code vfs:}) asks the
     * connection, which reads no content.
     */
    static long lastModified(URL url) {
        try {
            String form = url.toExternalForm();
            if (form.startsWith("jar:")) {
                int separator = form.indexOf("!/");
                form = separator < 0 ? form.substring(4) : form.substring(4, separator);
            }
            if (form.startsWith("file:")) {
                return Files.getLastModifiedTime(Path.of(new URL(form).toURI())).toMillis();
            }
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getLastModified();
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return 0;
        }
    }
}
