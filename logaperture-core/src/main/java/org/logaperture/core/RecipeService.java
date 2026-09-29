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

import org.logaperture.api.DoctorFinding;
import org.logaperture.api.HandlerInfo;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.RuleExpression;
import org.logaperture.api.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link RecipeOperations} over a {@link RecipeCatalog} -- doc/specs/recipes.md, slice (a). The
 * live levels {@code show recipe} compares against come from the same logger and handler
 * operations {@code list loggers} and {@code list handlers} use.
 */
public final class RecipeService implements RecipeOperations {

    static final String CHECK = "recipe-files";

    private final CapabilityPolicy policy;
    private final RecipeCatalog catalog;
    private final LevelControlOperations loggers;
    private final HandlerLevelControlOperations handlers;
    private final ProtectedCategories protectedCategories;

    public RecipeService(CapabilityPolicy policy, RecipeCatalog catalog, LevelControlOperations loggers,
            HandlerLevelControlOperations handlers) {
        this(policy, catalog, loggers, handlers, ProtectedCategories.none());
    }

    RecipeService(CapabilityPolicy policy, RecipeCatalog catalog, LevelControlOperations loggers,
            HandlerLevelControlOperations handlers, ProtectedCategories protectedCategories) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.loggers = Objects.requireNonNull(loggers, "loggers");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.protectedCategories = Objects.requireNonNull(protectedCategories, "protectedCategories");
    }

    @Override
    public RecipeList listRecipes() {
        requireCapability(Capability.VIEW);
        return catalog.read();
    }

    @Override
    public RecipeDetail showRecipe(String id, String from) {
        requireCapability(Capability.VIEW);
        RecipeListing listing = resolve(catalog.read(), id, from);
        Recipe recipe = listing.recipe();
        boolean library = recipe.source().kind() == RecipeSource.Kind.LIBRARY;
        List<RecipeDetail.Change> changes = new ArrayList<>();
        for (VendorDefaults.LoggerDefault logger : recipe.loggers()) {
            Optional<Level> current = loggers.listLoggers(logger.name()).stream()
                    .filter(row -> row.name().equals(logger.name()))
                    .map(LoggerInfo::effectiveLevel)
                    .findFirst();
            changes.add(new RecipeDetail.Change("logger", logger.name(), current.map(Level::name).orElse(null),
                    logger.level().name(), logger.reason(), loggerNote(logger, current, library)));
        }
        if (!recipe.handlers().isEmpty()) {
            List<HandlerInfo> known = handlers.listHandlers();
            for (VendorDefaults.HandlerDefault handler : recipe.handlers()) {
                String current = known.stream()
                        .filter(row -> row.ref().equals(handler.ref().value()))
                        .map(row -> row.level() == null ? null : row.level().name())
                        .findFirst().orElse(null);
                String level = handler.mode() == HandlerLevelMode.AUTO ? "AUTO" : handler.level().name();
                changes.add(new RecipeDetail.Change("handler", handler.ref().value(), current, level,
                        handler.reason(), null));
            }
        }
        for (VendorDefaults.RuleDefault rule : recipe.rules()) {
            boolean drop = "drop".equals(rule.action());
            String expression = RuleExpression.of(rule.matchers(), drop ? rule.sampleFull() : null,
                    drop ? null : rule.frames(), drop ? null : rule.collapseCauses());
            String note = protectedCategories.isProtected(rule.loggerName())
                    ? "refused: " + rule.loggerName() + " is a protected category" : null;
            changes.add(new RecipeDetail.Change("rule " + rule.action(), rule.loggerName(), null, null, expression,
                    note));
        }
        return new RecipeDetail(listing, changes);
    }

    /**
     * #4: a library recipe only raises verbosity -- an entry that would lower the live level is
     * skipped. A protected category refuses a change from any source (epic #19).
     */
    private String loggerNote(VendorDefaults.LoggerDefault logger, Optional<Level> current, boolean library) {
        if (protectedCategories.isProtected(logger.name())) {
            return "refused: " + logger.name() + " is a protected category";
        }
        if (library && current.isPresent() && logger.level().ordinal() > current.get().ordinal()) {
            return "skipped: would lower " + current.get() + " -> " + logger.level()
                    + " (library recipes only raise levels)";
        }
        return null;
    }

    /** Picks the one listing {@code id} (and {@code from}) names -- epic #20. */
    static RecipeListing resolve(RecipeList list, String id, String from) {
        List<RecipeListing> candidates = list.recipes().stream().filter(listing -> listing.id().equals(id)).toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no recipe named '" + id + "' -- 'logctl list recipes' shows what's "
                    + "on offer; a library's recipes appear once it has loaded");
        }
        if (from != null) {
            return candidates.stream().filter(listing -> listing.offeredBy(from)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no recipe '" + id + "' from '" + from
                            + "' -- it's offered by: " + labels(candidates)));
        }
        List<RecipeListing> visible = candidates.stream().filter(listing -> !listing.shadowed()).toList();
        if (visible.size() == 1) {
            return visible.get(0);
        }
        throw new IllegalArgumentException(visible.size() + " different recipes are named '" + id + "' (from "
                + labels(visible) + ") -- pick one with --from <source>");
    }

    private static String labels(List<RecipeListing> listings) {
        return String.join(", ", listings.stream().map(listing -> listing.recipe().source().label()).toList());
    }

    @Override
    public List<DoctorFinding> recipeFindings() {
        requireCapability(Capability.VIEW);
        RecipeList list = catalog.read();
        if (list.recipes().isEmpty() && list.brokenFiles().isEmpty()) {
            return List.of(); // nothing to inspect: no row at all (doc/specs/doctor.md)
        }
        if (list.brokenFiles().isEmpty()) {
            return List.of(new DoctorFinding(CHECK, Severity.OK, "recipes", "every recipe file reads cleanly",
                    null, null));
        }
        List<DoctorFinding> findings = new ArrayList<>();
        for (RecipeFileResult file : list.brokenFiles()) {
            boolean library = file.source().kind() == RecipeSource.Kind.LIBRARY;
            findings.add(new DoctorFinding(CHECK, Severity.INFO, file.source().label(),
                    "recipe file can't be read -- its recipes are not offered",
                    file.source().location() + ": " + String.join("; ", file.errors()),
                    library ? "Report it to the library's maintainers; 'logctl list recipes --verbose' shows each error."
                            : "Fix the file; 'logctl list recipes --verbose' shows each error."));
        }
        return findings;
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
