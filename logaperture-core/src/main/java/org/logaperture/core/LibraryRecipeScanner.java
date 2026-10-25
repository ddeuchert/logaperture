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
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Finds the libraries' recipe files on demand -- doc/specs/recipes.md "Where recipes come from"
 * (epic #16): walks the loaded classes, collects their distinct class loaders, and asks each for
 * {@link RecipeFile#RESOURCE}. Nothing runs on the class-loading path, so a library's recipes
 * appear once the library has loaded. On WildFly a deployment's own loader answers for its
 * {@code WEB-INF/classes} and {@code WEB-INF/lib} jars (checked by {@code WildFlyContainerIT}).
 */
public final class LibraryRecipeScanner {

    private final Supplier<Collection<ClassLoader>> classLoaders;

    /** @param loadedClasses {@code Instrumentation::getAllLoadedClasses} in production: every loader they came from is scanned */
    public LibraryRecipeScanner(Supplier<Class<?>[]> loadedClasses) {
        Objects.requireNonNull(loadedClasses, "loadedClasses");
        this.classLoaders = () -> loadersOf(loadedClasses.get());
    }

    private LibraryRecipeScanner(List<ClassLoader> fixedLoaders) {
        this.classLoaders = () -> fixedLoaders;
    }

    /**
     * Scans one class loader (and, by parent delegation, its parents) -- the Quarkus extension's
     * application class loader, which sees every application jar (doc/specs/quarkus-extension.md #6).
     */
    public static LibraryRecipeScanner forClassLoader(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return new LibraryRecipeScanner(List.of(loader));
    }

    /**
     * Scans each of {@code loaders} (and their parents); a recipe file several of them see is found
     * once. The Quarkus extension passes its own loader and the application's: in dev mode the
     * application's own classes are only in the second (quarkus-extension.md #6).
     */
    public static LibraryRecipeScanner forClassLoaders(List<ClassLoader> loaders) {
        return new LibraryRecipeScanner(List.copyOf(loaders));
    }

    /** A scanner that finds nothing -- no {@code Instrumentation} to walk (tests, or a bootstrap failure). */
    public static LibraryRecipeScanner none() {
        return new LibraryRecipeScanner(() -> new Class<?>[0]);
    }

    private static Collection<ClassLoader> loadersOf(Class<?>[] types) {
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> type : types) {
            ClassLoader loader = type.getClassLoader();
            if (loader != null) {
                loaders.add(loader);
            }
        }
        return loaders;
    }

    /**
     * Every distinct recipe resource visible to a loaded class's loader, de-duplicated by URL --
     * parent delegation shows one jar's file through every child loader. A loader that fails to
     * answer is skipped: one misbehaving loader must not hide every other library's recipes.
     */
    public List<URL> scan() {
        Collection<ClassLoader> loaders = classLoaders.get();
        Map<String, URL> found = new LinkedHashMap<>();
        for (ClassLoader loader : loaders) {
            try {
                for (URL url : Collections.list(loader.getResources(RecipeFile.RESOURCE))) {
                    found.putIfAbsent(url.toExternalForm(), url);
                }
            } catch (IOException | RuntimeException | LinkageError e) {
                // skipped, see the method doc
            }
        }
        return new ArrayList<>(found.values());
    }

    /**
     * The short {@code SOURCE} label for a library's recipe file -- doc/specs/recipes.md "logctl
     * list recipes": the jar's file name, or {@code <war>!/WEB-INF/lib/<jar>} (or {@code
     * <war>!/WEB-INF/classes}) for one inside a deployment. Works on {@code jar:}, {@code file:}
     * and WildFly's {@code vfs:} URLs alike: the path up to the resource, from its first {@code
     * .war}/{@code .ear}/{@code .jar} segment on; else the last path segment.
     */
    static String label(URL url) {
        String path = url.toExternalForm();
        int resource = path.lastIndexOf("/" + RecipeFile.RESOURCE);
        if (resource >= 0) {
            path = path.substring(0, resource);
        }
        if (path.endsWith("!")) {
            path = path.substring(0, path.length() - 1);
        }
        path = path.replace("!/", "/");
        int scheme;
        while ((scheme = path.indexOf(':')) >= 0 && scheme < path.indexOf('/') ) {
            path = path.substring(scheme + 1); // jar:file:/..., vfs:/...
        }
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.endsWith(".war") || segment.endsWith(".ear") || segment.endsWith(".jar")) {
                if (i == segments.length - 1) {
                    return segment;
                }
                return segment + "!/" + String.join("/", List.of(segments).subList(i + 1, segments.length));
            }
        }
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isEmpty()) {
                return segments[i];
            }
        }
        return url.toExternalForm();
    }
}
