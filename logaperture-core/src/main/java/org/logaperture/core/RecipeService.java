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
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RecipeTag;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.RuleExpression;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.api.Severity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link RecipeOperations} over a {@link RecipeCatalog} -- doc/specs/recipes.md. The live levels
 * {@code show recipe} compares against, and the changes {@code apply recipe} makes, go through the
 * same logger, handler and rule operations {@code logctl list}, {@code set}, {@code add rule} and
 * {@code reset} use; a recipe's changes carry its id ({@link RecipeTag}), which is how {@code reset
 * recipe} and {@code list recipes} find them again.
 */
public final class RecipeService implements RecipeOperations {

    static final String CHECK = "recipe-files";

    private final CapabilityPolicy policy;
    private final RecipeCatalog catalog;
    private final LevelControlOperations loggers;
    private final HandlerLevelControlOperations handlers;
    private final RuleOperations rules;
    private final ProtectedCategories protectedCategories;

    public RecipeService(CapabilityPolicy policy, RecipeCatalog catalog, LevelControlOperations loggers,
            HandlerLevelControlOperations handlers, RuleOperations rules) {
        this(policy, catalog, loggers, handlers, rules, ProtectedCategories.none());
    }

    RecipeService(CapabilityPolicy policy, RecipeCatalog catalog, LevelControlOperations loggers,
            HandlerLevelControlOperations handlers, RuleOperations rules, ProtectedCategories protectedCategories) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.loggers = Objects.requireNonNull(loggers, "loggers");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.protectedCategories = Objects.requireNonNull(protectedCategories, "protectedCategories");
    }

    // ---- list and show ----

    @Override
    public RecipeList listRecipes() {
        requireCapability(Capability.VIEW);
        RecipeList offered = catalog.read();
        return new RecipeList(offered.recipes(), offered.brokenFiles(), applied());
    }

    @Override
    public RecipeDetail showRecipe(String id, String from) {
        requireCapability(Capability.VIEW);
        RecipeListing listing = resolve(catalog.read(), id, from);
        List<RecipeDetail.Change> changes = plan(listing.recipe(), null).stream().map(Planned::change).toList();
        return new RecipeDetail(listing, changes, fingerprint(listing.recipe()));
    }

    // ---- apply ----

    @Override
    public RecipeApplyResult applyRecipe(String id, String from, String fingerprint, String reason,
            PersistenceTier tier, Duration expiresIn) {
        requireCapability(Capability.VIEW);
        Objects.requireNonNull(tier, "tier");
        if (tier == PersistenceTier.FOR && (expiresIn == null || expiresIn.isZero() || expiresIn.isNegative())) {
            throw new IllegalArgumentException("a 'for' tier needs a positive duration");
        }
        RecipeListing listing = resolve(catalog.read(), id, from);
        Recipe recipe = listing.recipe();
        if (fingerprint != null && !fingerprint.equals(fingerprint(recipe))) {
            throw new IllegalArgumentException("recipe " + recipe.id() + " changed since it was shown -- run the "
                    + "command again");
        }
        List<Planned> plan = plan(recipe, tier);
        List<Planned> refused = plan.stream().filter(Planned::refused).toList();
        if (!refused.isEmpty()) {
            // #5: all or nothing -- nothing is changed while any change would be refused.
            throw new IllegalArgumentException("recipe " + recipe.id() + " not applied -- " + refused.size()
                    + (refused.size() == 1 ? " change would be refused:" : " changes would be refused:")
                    + String.join("", refused.stream()
                            .map(planned -> "\n  " + planned.change().kind() + " " + planned.change().target() + ": "
                                    + planned.change().note())
                            .toList()));
        }

        RecipeTag tag = new RecipeTag(recipe.id(), recipe.source().location());
        Instant expiresAt = tier == PersistenceTier.FOR ? Instant.now().plus(expiresIn) : null;
        List<Planned> toApply = plan.stream().filter(planned -> !planned.skipped()).toList();
        List<RecipeDetail.Change> skipped = plan.stream().filter(Planned::skipped).map(Planned::change).toList();

        List<RecipeDetail.Change> applied = new ArrayList<>();
        List<String> ruleIds = new ArrayList<>();
        boolean oldRulesRemoved = false;
        for (Planned planned : toApply) {
            try {
                if (planned.rule() != null && !oldRulesRemoved) {
                    // B3: re-applying replaces the recipe's rules rather than adding them a second time --
                    // removed only now, once its loggers and handlers are in, so an earlier failure can't
                    // leave the recipe with its rules gone and nothing to reset.
                    for (RuleView rule : taggedRules(recipe.id())) {
                        rules.resetRule(rule.rule().id(), true);
                    }
                    oldRulesRemoved = true;
                }
                String ruleId = apply(planned, reasonFor(planned, reason, recipe), tier, expiresIn, tag);
                if (ruleId != null) {
                    ruleIds.add(ruleId);
                }
            } catch (RuntimeException e) {
                // B2: stop at the first failure; what applied stays, and carries the id reset recipe needs.
                throw new IllegalStateException("Applied " + applied.size() + " of " + toApply.size()
                        + " changes before this failed: " + planned.change().kind() + " " + planned.change().target()
                        + ": " + e.getMessage()
                        + (applied.isEmpty() ? "" : " -- 'logctl reset recipe " + recipe.id()
                                + "' undoes the ones that applied"), e);
            }
            applied.add(planned.change());
        }
        return new RecipeApplyResult(listing, tier, expiresAt, applied, skipped, ruleIds);
    }

    /** Makes one change; returns the new rule's id for a rule, else {@code null}. */
    private String apply(Planned planned, String reason, PersistenceTier tier, Duration expiresIn, RecipeTag tag) {
        Duration forTier = tier == PersistenceTier.FOR ? expiresIn : null;
        if (planned.logger() != null) {
            loggers.setLogger(planned.logger().name(), planned.logger().level(),
                    new SetLevelOptions(reason, forTier, tier, true, tag));
            return null;
        }
        if (planned.handler() != null) {
            VendorDefaults.HandlerDefault handler = planned.handler();
            SetHandlerLevelOptions options = new SetHandlerLevelOptions(reason, forTier, tier, tag);
            if (handler.mode() == HandlerLevelMode.AUTO) {
                handlers.setHandlerAuto(handler.ref(), options);
            } else {
                handlers.setHandlerLevel(handler.ref(), handler.level(), options);
            }
            return null;
        }
        VendorDefaults.RuleDefault rule = planned.rule();
        RuleAttachOptions options = new RuleAttachOptions(reason, forTier, tier, tag);
        RuleView added = "drop".equals(rule.action())
                ? rules.addRuleDrop(rule.loggerName(), rule.matchers(), options, rule.sampleFull())
                : rules.addRuleTrim(rule.loggerName(), rule.matchers(), options, rule.frames(), rule.collapseCauses());
        return added.rule().id();
    }

    /** #8: the entry's own reason, else {@code --reason}, else {@code recipe <id>}. */
    private static String reasonFor(Planned planned, String reason, Recipe recipe) {
        String own = planned.logger() != null ? planned.logger().reason()
                : planned.handler() != null ? planned.handler().reason() : planned.rule().reason();
        if (own != null) {
            return own;
        }
        return reason != null ? reason : "recipe " + recipe.id();
    }

    // ---- reset ----

    @Override
    public RecipeResetResult resetRecipe(String id, boolean includeSticky) {
        requireCapability(Capability.VIEW);
        Objects.requireNonNull(id, "id");
        List<String> resetLoggers = new ArrayList<>();
        List<String> resetHandlers = new ArrayList<>();
        List<String> removedRules = new ArrayList<>();
        List<String> kept = new ArrayList<>();

        for (Map.Entry<String, PersistenceTier> logger : taggedLoggers(id).entrySet()) {
            if (logger.getValue() == PersistenceTier.STICKY && !includeSticky) {
                kept.add("logger " + logger.getKey());
            } else if (!loggers.resetLogger(logger.getKey(), includeSticky).revertedLoggerNames().isEmpty()) {
                resetLoggers.add(logger.getKey());
            }
        }
        for (HandlerLevelOverride handler : taggedHandlers(id)) {
            String name = handler.handlerRef().value();
            if (handler.tier() == PersistenceTier.STICKY && !includeSticky) {
                kept.add("handler " + name);
            } else if (!handlers.resetHandler(handler.handlerRef(), includeSticky).revertedHandlerRefs().isEmpty()) {
                resetHandlers.add(name);
            }
        }
        for (RuleView rule : taggedRules(id)) {
            if (rule.rule().tier() == PersistenceTier.STICKY && !includeSticky) {
                kept.add("rule " + rule.rule().id());
            } else if (rules.resetRule(rule.rule().id(), includeSticky).isPresent()) {
                removedRules.add(rule.rule().id());
            }
        }
        return new RecipeResetResult(id, resetLoggers, resetHandlers, removedRules, kept);
    }

    // ---- what's live ----

    /** B6/B7: by recipe id, the live changes still carrying it. */
    private Map<String, RecipeApplication> applied() {
        Map<String, Set<String>> loggerNames = new LinkedHashMap<>();
        Map<String, Set<String>> handlerNames = new LinkedHashMap<>();
        Map<String, Integer> ruleCounts = new LinkedHashMap<>();
        Map<String, Soonest> soonest = new LinkedHashMap<>();
        for (LoggerInfo row : loggers.listLoggers(null)) {
            if (row.overrideActive() && row.overrideRecipe() != null
                    && loggerNames.computeIfAbsent(row.overrideRecipe(), k -> new LinkedHashSet<>()).add(row.name())) {
                soonest.merge(row.overrideRecipe(), new Soonest(row.overrideTier(), row.overrideExpiresAt()),
                        Soonest::earlier);
            }
        }
        for (HandlerLevelOverride handler : handlers.listHandlerOverrides()) {
            if (handler.recipe() != null && handlerNames.computeIfAbsent(handler.recipe(), k -> new LinkedHashSet<>())
                    .add(handler.handlerRef().value())) {
                soonest.merge(handler.recipe(), new Soonest(handler.tier(), handler.expiresAt()), Soonest::earlier);
            }
        }
        for (RuleView rule : rules.listRules()) {
            if (rule.recipe() != null) {
                ruleCounts.merge(rule.recipe(), 1, Integer::sum);
                soonest.merge(rule.recipe(), new Soonest(rule.rule().tier(), rule.rule().expiresAt()), Soonest::earlier);
            }
        }
        Map<String, RecipeApplication> applied = new LinkedHashMap<>();
        for (Map.Entry<String, Soonest> entry : soonest.entrySet()) {
            String id = entry.getKey();
            applied.put(id, new RecipeApplication(id, loggerNames.getOrDefault(id, Set.of()),
                    handlerNames.getOrDefault(id, Set.of()), ruleCounts.getOrDefault(id, 0), entry.getValue().tier(),
                    entry.getValue().expiresAt()));
        }
        return applied;
    }

    /** The change that ends soonest: {@code FOR} (earliest expiry) before {@code SESSION} before {@code STICKY}. */
    private record Soonest(PersistenceTier tier, Instant expiresAt) {
        Soonest earlier(Soonest other) {
            if (rank() != other.rank()) {
                return rank() < other.rank() ? this : other;
            }
            return tier == PersistenceTier.FOR && other.expiresAt().isBefore(expiresAt) ? other : this;
        }

        private int rank() {
            return switch (tier) {
                case FOR -> 0;
                case SESSION -> 1;
                case STICKY -> 2;
            };
        }
    }

    /** Logger names with an override carrying {@code id}, with that override's tier (one per name). */
    private Map<String, PersistenceTier> taggedLoggers(String id) {
        Map<String, PersistenceTier> tagged = new LinkedHashMap<>();
        for (LoggerInfo row : loggers.listLoggers(null)) {
            if (row.overrideActive() && id.equals(row.overrideRecipe())) {
                tagged.putIfAbsent(row.name(), row.overrideTier());
            }
        }
        return tagged;
    }

    private List<HandlerLevelOverride> taggedHandlers(String id) {
        Map<String, HandlerLevelOverride> tagged = new LinkedHashMap<>();
        for (HandlerLevelOverride handler : handlers.listHandlerOverrides()) {
            if (id.equals(handler.recipe())) {
                tagged.putIfAbsent(handler.handlerRef().value(), handler);
            }
        }
        return List.copyOf(tagged.values());
    }

    private List<RuleView> taggedRules(String id) {
        return rules.listRules().stream().filter(rule -> id.equals(rule.recipe())).toList();
    }

    // ---- planning ----

    /**
     * One entry of the recipe, with what applying it would do. Exactly one of {@code logger},
     * {@code handler}, {@code rule} is set. A skipped entry is left out (#4, B8); a refused one stops
     * the whole apply (#5).
     */
    private record Planned(RecipeDetail.Change change, boolean skipped, boolean refused,
            VendorDefaults.LoggerDefault logger, VendorDefaults.HandlerDefault handler, VendorDefaults.RuleDefault rule) {
    }

    /**
     * Each entry against the live state. {@code tier} is {@code null} for {@code show}, which has
     * none: the {@code PERSIST} check then waits for {@code apply}.
     */
    private List<Planned> plan(Recipe recipe, PersistenceTier tier) {
        boolean library = recipe.source().kind() == RecipeSource.Kind.LIBRARY;
        boolean persists = tier != null && tier != PersistenceTier.SESSION;
        List<Planned> plan = new ArrayList<>();
        for (VendorDefaults.LoggerDefault logger : recipe.loggers()) {
            List<Level> current = liveLevels(logger.name());
            String shown = current.isEmpty() ? null : current.get(0).name();
            String note = null;
            boolean skipped = false;
            Level lowered = current.stream().filter(level -> logger.level().ordinal() > level.ordinal())
                    .findFirst().orElse(null);
            if (protectedCategories.isProtected(logger.name())) {
                note = "refused: " + logger.name() + " is a protected category";
            } else if (library && current.isEmpty()) {
                // #4: with no level to compare against, a library entry can't be shown to be a raise.
                note = "skipped: its current level can't be read yet (library recipes only raise levels)";
                skipped = true;
            } else if (library && lowered != null) {
                // #4, B8: skipped if it would lower the level in any context.
                note = "skipped: would lower " + lowered + " -> " + logger.level() + " (library recipes only raise levels)";
                skipped = true;
            } else {
                note = missing(loggerCapabilities(logger.level(), current), persists);
            }
            plan.add(new Planned(new RecipeDetail.Change("logger", logger.name(), shown, logger.level().name(),
                    logger.reason(), note), skipped, note != null && !skipped, logger, null, null));
        }
        if (!recipe.handlers().isEmpty()) {
            List<HandlerInfo> known = handlers.listHandlers();
            for (VendorDefaults.HandlerDefault handler : recipe.handlers()) {
                HandlerInfo row = known.stream().filter(info -> info.ref().equals(handler.ref().value()))
                        .findFirst().orElse(null);
                boolean auto = handler.mode() == HandlerLevelMode.AUTO;
                String note = row == null
                        ? "refused: handler " + handler.ref().value() + " is not known yet" // B9
                        : missing(List.of(handlerCapability(handler, row.level())), persists);
                String current = row == null || row.level() == null ? null : row.level().name();
                plan.add(new Planned(new RecipeDetail.Change("handler", handler.ref().value(), current,
                        auto ? "AUTO" : handler.level().name(), handler.reason(), note), false, note != null, null,
                        handler, null));
            }
        }
        for (VendorDefaults.RuleDefault rule : recipe.rules()) {
            boolean drop = "drop".equals(rule.action());
            String expression = RuleExpression.of(rule.matchers(), drop ? rule.sampleFull() : null,
                    drop ? null : rule.frames(), drop ? null : rule.collapseCauses());
            String note = protectedCategories.isProtected(rule.loggerName())
                    ? "refused: " + rule.loggerName() + " is a protected category"
                    : missing(List.of(Capability.RULES_AUTHOR, Capability.SUPPRESS), persists);
            plan.add(new Planned(new RecipeDetail.Change("rule " + rule.action(), rule.loggerName(), null, null,
                    expression, note), false, note != null, null, null, rule));
        }
        return plan;
    }

    /**
     * {@code name}'s effective level in each context -- for a logger not created yet, the level it
     * would inherit from its nearest existing parent (#4 compares against it). Empty when no logger on
     * its path is known.
     */
    private List<Level> liveLevels(String name) {
        for (String candidate = name; !candidate.isEmpty();
                candidate = candidate.contains(".") ? candidate.substring(0, candidate.lastIndexOf('.')) : "") {
            String exact = candidate;
            List<Level> levels = loggers.listLoggers(exact).stream()
                    .filter(row -> row.name().equals(exact))
                    .map(LoggerInfo::effectiveLevel)
                    .toList();
            if (!levels.isEmpty()) {
                return levels;
            }
        }
        return List.of();
    }

    /** What {@code set logger} would check: a raise and/or a lower, against each context's level. */
    private static List<Capability> loggerCapabilities(Level level, List<Level> current) {
        if (current.isEmpty()) {
            return List.of(Capability.LEVEL_RAISE); // not known yet: treated as a raise, the stricter reading
        }
        List<Capability> needed = new ArrayList<>();
        if (current.stream().anyMatch(level::isMoreVerboseThan)) {
            needed.add(Capability.LEVEL_RAISE);
        }
        if (current.stream().anyMatch(existing -> existing.isMoreVerboseThan(level))) {
            needed.add(Capability.LEVEL_LOWER);
        }
        return needed;
    }

    /** What {@code set handler} would check: letting more through lowers the handler's floor. */
    private static Capability handlerCapability(VendorDefaults.HandlerDefault handler, Level current) {
        if (handler.mode() == HandlerLevelMode.AUTO || current == null || handler.level().isMoreVerboseThan(current)) {
            return Capability.HANDLER_LOWER;
        }
        return Capability.HANDLER_RAISE;
    }

    /** A refusal note naming the first capability not granted, or {@code null} when all are. */
    private String missing(List<Capability> needed, boolean persists) {
        List<Capability> all = new ArrayList<>(needed);
        if (persists) {
            all.add(Capability.PERSIST);
        }
        return all.stream().filter(capability -> !policy.isGranted(capability)).findFirst()
                .map(capability -> "refused: needs the " + capability + " capability").orElse(null);
    }

    /** B1: the recipe's content, hashed -- the same recipe from anywhere gives the same fingerprint. */
    static String fingerprint(Recipe recipe) {
        String content = String.join("\u0000", recipe.namespace(), recipe.name(), recipe.summary(),
                String.valueOf(recipe.description()), recipe.loggers().toString(), recipe.handlers().toString(),
                recipe.rules().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
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

    // ---- doctor ----

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
