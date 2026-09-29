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

import org.logaperture.core.RecipeListing;
import org.logaperture.core.RecipeSource;

import java.beans.ConstructorProperties;
import java.util.List;

/**
 * One row of {@code logctl list recipes} -- MXBean-friendly mirror of {@code core}'s {@code
 * RecipeListing} (doc/specs/recipes.md), a plain class for the same reason as {@link
 * EnvironmentReportData}.
 */
public final class RecipeData {

    private final String id;
    private final String summary;
    private final String sourceKind;
    private final String sourceLabel;
    private final String sourceLocation;
    private final List<String> otherSourceLabels;
    private final boolean ambiguous;
    private final boolean shadowed;

    @ConstructorProperties({"id", "summary", "sourceKind", "sourceLabel", "sourceLocation", "otherSourceLabels", "ambiguous", "shadowed"})
    public RecipeData(String id, String summary, String sourceKind, String sourceLabel, String sourceLocation, List<String> otherSourceLabels, boolean ambiguous, boolean shadowed) {
        this.id = id;
        this.summary = summary;
        this.sourceKind = sourceKind;
        this.sourceLabel = sourceLabel;
        this.sourceLocation = sourceLocation;
        this.otherSourceLabels = otherSourceLabels;
        this.ambiguous = ambiguous;
        this.shadowed = shadowed;
    }

    public static RecipeData from(RecipeListing listing) {
        RecipeSource source = listing.recipe().source();
        return new RecipeData(listing.id(), listing.recipe().summary(), source.kind().name(), source.label(),
                source.location(), listing.sources().stream().skip(1).map(RecipeSource::label).toList(),
                listing.ambiguous(), listing.shadowed());
    }

    /** {@code <namespace>:<name>} */
    public String getId() {
        return id;
    }

    public String getSummary() {
        return summary;
    }

    /** {@code LIBRARY}, {@code VENDOR_DEFAULTS} or {@code FOLDER} */
    public String getSourceKind() {
        return sourceKind;
    }

    /** The short source name {@code --from} accepts. */
    public String getSourceLabel() {
        return sourceLabel;
    }

    /** The full path or URL. */
    public String getSourceLocation() {
        return sourceLocation;
    }

    /** Further sources offering this exact recipe (epic #20: identical recipes merge). */
    public List<String> getOtherSourceLabels() {
        return otherSourceLabels;
    }

    /** Another row has this id with different content: {@code show recipe} needs {@code --from}. */
    public boolean isAmbiguous() {
        return ambiguous;
    }

    /** A vendor defaults file or recipes folder recipe with this id wins over this one. */
    public boolean isShadowed() {
        return shadowed;
    }
}
