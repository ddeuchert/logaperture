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

import java.util.Objects;

/**
 * Where a recipe was read from -- doc/specs/recipes.md "Where recipes come from".
 *
 * @param kind     which of the three sources
 * @param label    the short name {@code logctl list recipes} shows in its {@code SOURCE} column,
 *                 and {@code --from} accepts: {@code vendor defaults}, {@code recipes/<file>}, or a
 *                 jar's file name ({@code <war>!/WEB-INF/lib/<jar>} for one inside a deployment)
 * @param location the full path or URL, shown by {@code show recipe} and {@code list recipes
 *                 --verbose}
 */
public record RecipeSource(Kind kind, String label, String location) {

    public enum Kind {
        /** {@code META-INF/logaperture/recipes.yaml} on a loaded class path: logger levels only (epic #17). */
        LIBRARY,
        /** The vendor defaults file's own {@code recipes:} section. */
        VENDOR_DEFAULTS,
        /** A {@code *.yaml} file in the recipes folder. */
        FOLDER
    }

    /** The label of the vendor defaults file as a source. */
    public static final String VENDOR_DEFAULTS_LABEL = "vendor defaults";

    public RecipeSource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(location, "location");
    }

    /**
     * Whether the operator or vendor put this source there deliberately -- the vendor defaults
     * file or the recipes folder -- rather than a library: its recipes may carry handler levels and
     * rules (epic #17), and shadow a library recipe with the same id (epic #20).
     */
    public boolean operatorPlaced() {
        return kind != Kind.LIBRARY;
    }
}
