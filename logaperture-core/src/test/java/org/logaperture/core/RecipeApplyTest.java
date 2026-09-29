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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.RuleChange;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.spi.ContextHandle;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/recipes.md slice (b): {@code apply recipe} and {@code reset recipe} against real services. */
class RecipeApplyTest {

    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");

    /** A folder recipe: two loggers, a handler and two rules. */
    private static final String BILLING = """
            schemaVersion: 1
            namespace: com.acme
            recipes:
              - name: billing
                summary: Trace invoice generation end to end
                loggers:
                  - name: com.acme.billing
                    level: TRACE
                    reason: invoice steps
                  - name: com.acme.pricing
                    level: DEBUG
                handlers:
                  - name: CONSOLE
                    level: DEBUG
                rules:
                  - action: trim
                    logger: com.acme.billing
                    throwable: java.net.ConnectException
                  - action: drop
                    logger: com.acme.billing.poll
                    messageContains: "poll ok"
            """;

    @TempDir
    Path tmp;

    private final InMemoryAuditLog auditLog = new InMemoryAuditLog();
    private final InMemoryStateStore stateStore = new InMemoryStateStore();
    private AggregateLevelControl aggregate;
    private Path folder;

    @BeforeEach
    void setUp() throws IOException {
        folder = Files.createDirectory(tmp.resolve("recipes"));
        Files.writeString(folder.resolve("acme.yaml"), BILLING);
        aggregate = aggregate(CapabilityPolicy.allowAll());
    }

    private AggregateLevelControl aggregate(CapabilityPolicy policy) {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        OverrideRegistry overrides = new OverrideRegistry();
        LevelControlService loggers = new LevelControlService(adapter, new BaselineRegistry(), overrides, policy,
                auditLog, stateStore, "alice", "jmx");
        ActiveLoggerFloor floor = () -> List.copyOf(overrides.all().values());
        HandlerLevelControlService handlers = new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(),
                new HandlerOverrideRegistry(), new DefaultHandlerGroupRegistry(), policy, auditLog, stateStore,
                "alice", "jmx", floor);
        RuleService rules = new RuleService(adapter, policy, auditLog, stateStore, "system", "alice", "jmx");
        rules.registerDropSupport();
        rules.registerTrimSupport();
        AggregateLevelControl control = new AggregateLevelControl();
        control.register(new AggregateLevelControl.ContextControl(ContextHandle.of("system", "system", adapter),
                loggers, handlers, new DoctorService(adapter, policy), new TopService(adapter, policy),
                new StormService(adapter, policy), rules, new EnvironmentReportService(adapter, policy)));
        return control;
    }

    private RecipeService service(List<Recipe> extra, CapabilityPolicy policy) {
        return new RecipeService(policy, new RecipeCatalog(extra, Optional.of(folder), LibraryRecipeScanner.none()),
                aggregate, aggregate, aggregate);
    }

    private RecipeService service() {
        return service(List.of(), CapabilityPolicy.allowAll());
    }

    private Optional<LevelOverride> override(String logger) {
        return aggregate.listLoggers(logger).stream().filter(row -> row.name().equals(logger) && row.overrideActive())
                .findFirst().map(row -> new LevelOverride(row.name(), row.effectiveLevel(), row.overrideReason(),
                        Instant.now(), "x", row.overrideTier(), row.overrideExpiresAt(), null, row.overrideRecipe()));
    }

    // ---- apply ----

    @Test
    void apply_makesEveryChange_taggedWithTheRecipe_andAuditedWithItsOrigin() {
        RecipeApplyResult result = service().applyRecipe("com.acme:billing", null, null, "INC-42",
                PersistenceTier.FOR, Duration.ofMinutes(30));

        assertEquals(5, result.applied().size());
        assertEquals(List.of(), result.skipped());
        assertEquals(PersistenceTier.FOR, result.tier());
        assertEquals(2, result.ruleIds().size());
        assertEquals(Level.TRACE, override("com.acme.billing").orElseThrow().level());
        assertEquals("com.acme:billing", override("com.acme.billing").orElseThrow().recipe());
        assertEquals("invoice steps", override("com.acme.billing").orElseThrow().reason(), "#8: the entry's own reason");
        assertEquals("INC-42", override("com.acme.pricing").orElseThrow().reason(), "#8: else --reason");
        assertEquals("com.acme:billing", aggregate.listHandlerOverrides().get(0).recipe());
        assertTrue(aggregate.listRules().stream().allMatch(rule -> "com.acme:billing".equals(rule.recipe())));

        AuditRecord audit = auditLog.records().stream().filter(r -> "com.acme.pricing".equals(r.loggerName()))
                .findFirst().orElseThrow();
        assertEquals("recipe", audit.source());
        assertEquals("recipe com.acme:billing from " + folder.resolve("acme.yaml"), audit.origin());
    }

    @Test
    void apply_withoutAReason_usesTheRecipeId() {
        service().applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null);

        assertEquals("recipe com.acme:billing", override("com.acme.pricing").orElseThrow().reason());
    }

    @Test
    void list_showsHowMuchIsApplied_andTheTierEndingSoonest() {
        RecipeService service = service();
        assertTrue(service.listRecipes().applied().isEmpty());

        service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.FOR, Duration.ofMinutes(30));
        aggregate.setLogger("com.acme.pricing", Level.WARN, SetLevelOptions.defaults()); // by hand: drops the tag

        RecipeList list = service.listRecipes();
        RecipeApplication applied = list.applied().get("com.acme:billing");
        Recipe recipe = list.recipes().get(0).recipe();
        assertEquals(4, applied.countOf(recipe), "B6: 4 of 5 still carry the id");
        assertEquals(5, RecipeApplication.entriesOf(recipe));
        assertEquals(PersistenceTier.FOR, applied.tier());
        assertTrue(applied.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void reapply_replacesTheRecipesRules_neverDoublesThem() {
        RecipeService service = service();
        List<String> first = service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null)
                .ruleIds();

        List<String> second = service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.STICKY, null)
                .ruleIds();

        assertEquals(2, aggregate.listRules().size(), "B3");
        assertFalse(second.stream().anyMatch(first::contains), "new ids");
        assertEquals(PersistenceTier.STICKY, override("com.acme.billing").orElseThrow().tier());
    }

    @Test
    void libraryRecipe_skipsAnEntryThatWouldLower_andAppliesTheRest() {
        aggregate.setLogger("io.undertow.request", Level.DEBUG, SetLevelOptions.defaults());
        Recipe library = new Recipe("io.undertow", "quiet", "s", null, List.of(
                new VendorDefaults.LoggerDefault("io.undertow.request", Level.ERROR, null),
                new VendorDefaults.LoggerDefault("io.undertow.server.session", Level.DEBUG, null)),
                List.of(), List.of(), RecipeCatalogTest.library("undertow.jar"));

        RecipeApplyResult result = service(List.of(library), CapabilityPolicy.allowAll())
                .applyRecipe("io.undertow:quiet", null, null, null, PersistenceTier.SESSION, null);

        assertEquals(List.of("io.undertow.server.session"), result.applied().stream().map(RecipeDetail.Change::target)
                .toList());
        assertEquals("skipped: would lower DEBUG -> ERROR (library recipes only raise levels)",
                result.skipped().get(0).note());
        assertNull(override("io.undertow.request").orElseThrow().recipe(), "left as it was");
    }

    // ---- all or nothing, and failures ----

    @Test
    void anUnknownHandler_refusesTheWholeRecipe_andNothingChanges() throws IOException {
        Files.writeString(folder.resolve("acme.yaml"), BILLING.replace("name: CONSOLE", "name: FILE"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service().applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null));

        assertEquals("recipe com.acme:billing not applied -- 1 change would be refused:\n"
                + "  handler FILE: refused: handler FILE is not known yet", refused.getMessage());
        assertTrue(override("com.acme.billing").isEmpty());
        assertTrue(aggregate.listRules().isEmpty());
    }

    @Test
    void aMissingCapability_refusesTheWholeRecipe() {
        CapabilityPolicy noPersist = capability -> capability != Capability.PERSIST;
        aggregate = aggregate(noPersist);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service(List.of(), noPersist).applyRecipe("com.acme:billing", null, null, null,
                        PersistenceTier.STICKY, null));

        assertTrue(refused.getMessage().startsWith("recipe com.acme:billing not applied -- 5 changes would be refused"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("logger com.acme.billing: refused: needs the PERSIST capability"),
                refused.getMessage());
        assertTrue(override("com.acme.billing").isEmpty());
    }

    @Test
    void aChangedRecipe_appliesNothing() throws IOException {
        RecipeService service = service();
        String shown = service.showRecipe("com.acme:billing", null).fingerprint();
        Files.writeString(folder.resolve("acme.yaml"), BILLING.replace("level: TRACE", "level: DEBUG"));

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.applyRecipe("com.acme:billing", null, shown, null, PersistenceTier.SESSION, null));

        assertEquals("recipe com.acme:billing changed since it was shown -- run the command again", changed.getMessage());
        assertTrue(override("com.acme.billing").isEmpty());
        service.applyRecipe("com.acme:billing", null, service.showRecipe("com.acme:billing", null).fingerprint(), null,
                PersistenceTier.SESSION, null);
        assertEquals(Level.DEBUG, override("com.acme.billing").orElseThrow().level());
    }

    @Test
    void aFailurePartWay_stops_keepsWhatApplied_andSaysSo() {
        RuleOperations failingAdds = (RuleOperations) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RuleOperations.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("addRule")) {
                        throw new IllegalStateException("adapter fault");
                    }
                    try {
                        return method.invoke(aggregate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RecipeService service = new RecipeService(CapabilityPolicy.allowAll(),
                new RecipeCatalog(List.of(), Optional.of(folder), LibraryRecipeScanner.none()), aggregate, aggregate,
                failingAdds);

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null));

        assertEquals("Applied 3 of 5 changes before this failed: rule trim com.acme.billing: adapter fault -- "
                + "'logctl reset recipe com.acme:billing' undoes the ones that applied", failed.getMessage());
        assertEquals("com.acme:billing", override("com.acme.billing").orElseThrow().recipe(), "B2: no rollback");
    }

    // ---- reset ----

    @Test
    void reset_putsBackWhatStillCarriesTheId_andLeavesHandChangesAlone() {
        RecipeService service = service();
        service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null);
        aggregate.setLogger("com.acme.pricing", Level.WARN, SetLevelOptions.defaults());
        String ruleId = aggregate.listRules().get(0).rule().id();
        aggregate.alterRule(ruleId, new RuleChange(null, false, null, null, null, null, null, null, null, "mine now"),
                null, null);

        RecipeResetResult reset = service.resetRecipe("com.acme:billing", false);

        assertEquals(List.of("com.acme.billing"), reset.loggers());
        assertEquals(List.of("CONSOLE"), reset.handlers());
        assertEquals(1, reset.rules().size(), "the altered rule is the operator's now (#7)");
        assertEquals(Level.WARN, override("com.acme.pricing").orElseThrow().level());
        assertTrue(override("com.acme.billing").isEmpty());
        assertTrue(aggregate.listRules().stream().anyMatch(rule -> rule.rule().id().equals(ruleId)));
        assertTrue(service.listRecipes().applied().isEmpty());
    }

    @Test
    void reset_keepsStickyChanges_unlessIncludeSticky() {
        RecipeService service = service();
        service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.STICKY, null);

        RecipeResetResult kept = service.resetRecipe("com.acme:billing", false);
        assertEquals(0, kept.count());
        assertEquals(5, kept.keptSticky().size());
        assertTrue(kept.keptSticky().contains("logger com.acme.billing"), kept.keptSticky().toString());

        RecipeResetResult reset = service.resetRecipe("com.acme:billing", true);
        assertEquals(5, reset.count());
        assertTrue(override("com.acme.billing").isEmpty());
    }

    @Test
    void aRecipeNoLongerOffered_isListed_andCanStillBeReset() throws IOException {
        RecipeService service = service();
        service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null);
        Files.delete(folder.resolve("acme.yaml"));

        RecipeList list = service.listRecipes();
        assertEquals(List.of("com.acme:billing"), list.noLongerOffered(), "B7");
        assertThrows(IllegalArgumentException.class,
                () -> service.applyRecipe("com.acme:billing", null, null, null, PersistenceTier.SESSION, null));

        assertEquals(5, service.resetRecipe("com.acme:billing", false).count());
    }

    @Test
    void nothingToReset_isAnEmptyResult() {
        RecipeResetResult reset = service().resetRecipe("com.acme:nothing", false);

        assertEquals(0, reset.count());
        assertTrue(reset.keptSticky().isEmpty());
    }

    @Test
    void recipeTag_survivesTheStateFile() {
        service().applyRecipe("com.acme:billing", null, null, null, PersistenceTier.STICKY, null);

        String written = StateFileFormat.write(stateStore.loadAll(), stateStore.loadAllHandlers(),
                List.of(), stateStore.loadAllRules());
        StateFileFormat.Parsed read = StateFileFormat.parse(written);

        assertTrue(written.startsWith("schemaVersion: 10\n"), written);
        assertTrue(read.overrides().stream().allMatch(o -> "com.acme:billing".equals(o.recipe())));
        assertTrue(read.handlerOverrides().stream().allMatch(o -> "com.acme:billing".equals(o.recipe())));
        assertEquals(2, read.rules().stream().filter(r -> "com.acme:billing".equals(r.recipe())).count());
        assertEquals(HandlerLevelMode.FIXED, read.handlerOverrides().get(0).mode());
    }

    @Test
    void aSchema9File_readsWithNoRecipe() {
        StateFileFormat.Parsed read = StateFileFormat.parse("""
                schemaVersion: 9
                overrides:
                  - loggerName: "com.acme"
                    level: DEBUG
                    reason: null
                    appliedAt: 2026-09-28T10:00:00Z
                    source: "jmx"
                    tier: STICKY
                    expiresAt: null
                handlerOverrides: []
                defaultHandlerMembers: []
                rules: []
                """);

        assertNull(read.overrides().get(0).recipe());
    }
}
