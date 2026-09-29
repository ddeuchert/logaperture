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

import org.logaperture.core.RecipeApplication;
import org.logaperture.core.RecipeListing;
import org.logaperture.core.RecipeSource;

import java.util.List;
import java.beans.ConstructorProperties;

/**
 * One row of {@code logctl list recipes} -- MXBean-friendly mirror of {@code core}'s {@code
 * RecipeListing} plus what of it is applied (doc/specs/recipes.md B6, B7), a plain class for the
 * same reason as {@link EnvironmentReportData}.
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
    private final boolean offered;
    private final int appliedCount;
    private final int entryCount;
    private final String appliedTier;
    private final String appliedExpiresAt;

    @ConstructorProperties({"id", "summary", "sourceKind", "sourceLabel", "sourceLocation", "otherSourceLabels", "ambiguous", "shadowed", "offered", "appliedCount", "entryCount", "appliedTier", "appliedExpiresAt"})
    public RecipeData(String id, String summary, String sourceKind, String sourceLabel, String sourceLocation, List<String> otherSourceLabels, boolean ambiguous, boolean shadowed, boolean offered, int appliedCount, int entryCount, String appliedTier, String appliedExpiresAt) {
        this.id = id;
        this.summary = summary;
        this.sourceKind = sourceKind;
        this.sourceLabel = sourceLabel;
        this.sourceLocation = sourceLocation;
        this.otherSourceLabels = otherSourceLabels;
        this.ambiguous = ambiguous;
        this.shadowed = shadowed;
        this.offered = offered;
        this.appliedCount = appliedCount;
        this.entryCount = entryCount;
        this.appliedTier = appliedTier;
        this.appliedExpiresAt = appliedExpiresAt;
    }

    /** @param applied the live changes carrying its id, or {@code null} when none do */
    public static RecipeData from(RecipeListing listing, RecipeApplication applied) {
        RecipeSource source = listing.recipe().source();
        return new RecipeData(listing.id(), listing.recipe().summary(), source.kind().name(), source.label(),
                source.location(), listing.sources().stream().skip(1).map(RecipeSource::label).toList(),
                listing.ambiguous(), listing.shadowed(), true,
                applied == null ? 0 : applied.countOf(listing.recipe()), RecipeApplication.entriesOf(listing.recipe()),
                applied == null ? null : applied.tier().name(),
                applied == null || applied.expiresAt() == null ? null : applied.expiresAt().toString());
    }

    /** B7: a recipe id live changes carry that no source offers any more. */
    public static RecipeData noLongerOffered(RecipeApplication applied) {
        return new RecipeData(applied.recipeId(), null, null, null, null, List.of(), false, false, false,
                applied.count(), 0, applied.tier().name(),
                applied.expiresAt() == null ? null : applied.expiresAt().toString());
    }

    /** {@code <namespace>:<name>} */
    public String getId() {
        return id;
    }

    /** The recipe's summary; {@code null} for one no longer offered. */
    public String getSummary() {
        return summary;
    }

    /** {@code LIBRARY}, {@code VENDOR_DEFAULTS} or {@code FOLDER}; {@code null} for one no longer offered. */
    public String getSourceKind() {
        return sourceKind;
    }

    /** The short source name {@code --from} accepts; {@code null} for one no longer offered. */
    public String getSourceLabel() {
        return sourceLabel;
    }

    /** The full path or URL; {@code null} for one no longer offered. */
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

    /** {@code false} for a recipe live changes still carry that no source offers any more (B7). */
    public boolean isOffered() {
        return offered;
    }

    /** How many of its entries still carry its id -- or, when not offered, how many changes do (B6). */
    public int getAppliedCount() {
        return appliedCount;
    }

    /** How many entries it has; {@code 0} when not offered. */
    public int getEntryCount() {
        return entryCount;
    }

    /** The tier of the applied change that ends soonest, or {@code null} when none is applied. */
    public String getAppliedTier() {
        return appliedTier;
    }

    /** That change's expiry (ISO-8601) when its tier is {@code FOR}, else {@code null}. */
    public String getAppliedExpiresAt() {
        return appliedExpiresAt;
    }
}
