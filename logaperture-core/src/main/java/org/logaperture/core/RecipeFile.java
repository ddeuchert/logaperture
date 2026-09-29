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

import org.logaperture.core.VendorYaml.MapNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads one recipe file -- a library's {@code META-INF/logaperture/recipes.yaml} or a {@code
 * *.yaml} in the recipes folder -- doc/specs/recipes.md "The recipe file". Same YAML subset and
 * entry shapes as the vendor defaults file ({@link VendorDefaultsFile}); only {@code
 * schemaVersion}, {@code namespace} and {@code recipes} are read, and any other top-level key is
 * ignored with a note (epic #15). All or nothing per file (#11). Never throws.
 */
public final class RecipeFile {

    /** Where a library carries its recipes, on its class path (doc/specs/recipes.md #3). */
    public static final String RESOURCE = "META-INF/logaperture/recipes.yaml";

    /** Recipe files are small; anything bigger is refused rather than read into memory. */
    static final int MAX_BYTES = 1024 * 1024;

    private static final Set<String> READ_KEYS = Set.of("schemaVersion", "namespace", "recipes");

    private RecipeFile() {
    }

    /** Reads a recipes-folder file. */
    public static RecipeFileResult load(Path path, RecipeSource source) {
        byte[] bytes;
        try {
            if (Files.size(path) > MAX_BYTES) {
                return RecipeFileResult.failed(source, "the file is larger than " + MAX_BYTES / 1024 + " KB");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException | SecurityException e) {
            return RecipeFileResult.failed(source, "cannot read the file: " + e.getMessage());
        }
        return parse(bytes, source);
    }

    /**
     * Reads a library's file through its class-path URL ({@code jar:}, {@code file:}, WildFly's
     * {@code vfs:}). Caching is off, so reading never holds a jar open for the JVM's lifetime.
     */
    public static RecipeFileResult load(URL url, RecipeSource source) {
        try {
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    return RecipeFileResult.failed(source, "the file is larger than " + MAX_BYTES / 1024 + " KB");
                }
                return parse(bytes, source);
            }
        } catch (IOException | RuntimeException e) {
            return RecipeFileResult.failed(source, "cannot read the file: " + e);
        }
    }

    static RecipeFileResult parse(byte[] bytes, RecipeSource source) {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return RecipeFileResult.failed(source, "the file is not valid UTF-8");
        }
        return parse(content, source);
    }

    static RecipeFileResult parse(String content, RecipeSource source) {
        MapNode root;
        try {
            root = VendorYaml.parse(content);
        } catch (VendorYaml.SyntaxException e) {
            return RecipeFileResult.failed(source, "line " + e.line() + ": " + e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        VendorDefaultsFile.Validation v = new VendorDefaultsFile.Validation(errors, ProtectedCategories.none(), false);
        for (String key : root.entries().keySet()) {
            if (!READ_KEYS.contains(key)) {
                notes.add("line " + root.keyLines().get(key) + ": '" + key + "' is ignored -- a recipe file is "
                        + "read only for schemaVersion, namespace and recipes");
            }
        }
        v.schemaVersion(root);
        String namespace = v.namespace(root, true,
                "namespace is missing -- recipe ids are <namespace>:<name>, e.g. 'namespace: io.undertow'");
        List<Recipe> recipes = List.of();
        if (!root.entries().containsKey("recipes")) {
            v.error(root.line(), "'recipes' is missing -- a recipe file lists its recipes under 'recipes:'");
        } else {
            recipes = v.recipes(root, namespace, source);
        }
        return errors.isEmpty()
                ? new RecipeFileResult(source, recipes, List.of(), notes)
                : new RecipeFileResult(source, List.of(), errors, notes);
    }
}
