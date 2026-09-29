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
package org.logaperture.control.jmx;

import org.logaperture.core.RecipeFileResult;

import java.beans.ConstructorProperties;
import java.util.List;

/** A recipe file that couldn't be read, so contributes no recipes -- doc/specs/recipes.md #11. */
public final class RecipeFileProblemData {

    private final String sourceKind;
    private final String sourceLabel;
    private final String sourceLocation;
    private final List<String> errors;

    @ConstructorProperties({"sourceKind", "sourceLabel", "sourceLocation", "errors"})
    public RecipeFileProblemData(String sourceKind, String sourceLabel, String sourceLocation, List<String> errors) {
        this.sourceKind = sourceKind;
        this.sourceLabel = sourceLabel;
        this.sourceLocation = sourceLocation;
        this.errors = errors;
    }

    public static RecipeFileProblemData from(RecipeFileResult file) {
        return new RecipeFileProblemData(file.source().kind().name(), file.source().label(),
                file.source().location(), file.errors());
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public String getSourceLocation() {
        return sourceLocation;
    }

    /** Each problem, with its line number where there is one. */
    public List<String> getErrors() {
        return errors;
    }
}
