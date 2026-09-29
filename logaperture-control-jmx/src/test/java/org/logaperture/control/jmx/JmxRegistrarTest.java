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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.Severity;
import org.logaperture.api.PersistenceTier;
import org.logaperture.core.Recipe;
import org.logaperture.core.RecipeApplyResult;
import org.logaperture.core.RecipeResetResult;
import org.logaperture.core.RecipeDetail;
import org.logaperture.core.RecipeFileResult;
import org.logaperture.core.RecipeList;
import org.logaperture.core.RecipeListing;
import org.logaperture.core.RecipeOperations;
import org.logaperture.core.RecipeSource;
import org.logaperture.core.VendorDefaults;

import javax.management.JMX;
import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live registration against the real platform MBean server -- no mocking of the JMX layer itself. */
class JmxRegistrarTest {

    @AfterEach
    void cleanUp() throws Exception {
        JmxRegistrar.unregister();
    }

    @Test
    void register_makesTheMBeanReachableByName() throws Exception {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.loggersToReturn = List.of(new LoggerInfo("com.acme.Worker", null, Level.INFO, false, null, null, null, null));

        JmxRegistrar.register(fake, fake, fake, fake, fake, fake, fake, fake);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        assertTrue(server.isRegistered(JmxRegistrar.OBJECT_NAME));

        LevelControlMXBean proxy = JMX.newMXBeanProxy(server, JmxRegistrar.OBJECT_NAME, LevelControlMXBean.class);
        List<LoggerInfoData> result = proxy.listLoggers(null);

        assertEquals(1, result.size());
        assertEquals("com.acme.Worker", result.get(0).getName());
    }

    /** doc/specs/recipes.md #12: the recipe types survive the MXBean open-type mapping, nested lists included. */
    @Test
    void recipes_roundTripThroughTheMBeanServer() throws Exception {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        RecipeSource jar = new RecipeSource(RecipeSource.Kind.LIBRARY, "a.war!/WEB-INF/lib/undertow.jar",
                "jar:file:/a/undertow.jar!/META-INF/logaperture/recipes.yaml");
        RecipeSource other = new RecipeSource(RecipeSource.Kind.LIBRARY, "b.war!/WEB-INF/lib/undertow.jar",
                "jar:file:/b/undertow.jar!/META-INF/logaperture/recipes.yaml");
        Recipe recipe = new Recipe("io.undertow", "sessions", "Watch HTTP sessions", "line one\nline two",
                List.of(new VendorDefaults.LoggerDefault("io.undertow.server.session", Level.DEBUG, null)), List.of(),
                List.of(), jar);
        RecipeListing listing = new RecipeListing(recipe, List.of(jar, other), false, false);
        List<Object[]> applyCalls = new java.util.ArrayList<>();
        RecipeFileResult broken = new RecipeFileResult(new RecipeSource(RecipeSource.Kind.FOLDER, "recipes/bad.yaml",
                "/r/bad.yaml"), List.of(), List.of("line 1: 'recipes' is missing"), List.of());
        RecipeOperations recipes = new RecipeOperations() {
            @Override
            public RecipeList listRecipes() {
                return new RecipeList(List.of(listing), List.of(broken));
            }

            @Override
            public RecipeDetail showRecipe(String id, String from) {
                return new RecipeDetail(listing, List.of(new RecipeDetail.Change("logger", "io.undertow.server.session",
                        "INFO", "DEBUG", null, null)), "0a1b2c3d4e5f6a7b");
            }

            @Override
            public RecipeApplyResult applyRecipe(String id, String from, String fingerprint, String reason,
                    PersistenceTier tier, Duration expiresIn) {
                applyCalls.add(new Object[] {id, from, fingerprint, reason, tier, expiresIn});
                return new RecipeApplyResult(listing, tier, Instant.parse("2026-09-28T12:30:00Z"),
                        List.of(new RecipeDetail.Change("logger", "io.undertow.server.session", "INFO", "DEBUG", null,
                                null)), List.of(), List.of("r7"));
            }

            @Override
            public RecipeResetResult resetRecipe(String id, boolean includeSticky) {
                return new RecipeResetResult(id, List.of("io.undertow.server.session"), List.of(), List.of("r7"),
                        List.of("handler CONSOLE"));
            }

            @Override
            public List<DoctorFinding> recipeFindings() {
                return List.of(new DoctorFinding("recipe-files", Severity.INFO, "recipes/bad.yaml", "can't be read",
                        null, null));
            }
        };

        JmxRegistrar.register(fake, fake, fake, fake, fake, fake, fake, fake, recipes);
        LevelControlMXBean proxy = JMX.newMXBeanProxy(ManagementFactory.getPlatformMBeanServer(),
                JmxRegistrar.OBJECT_NAME, LevelControlMXBean.class);

        RecipeApplyResultData applied = proxy.applyRecipe("io.undertow:sessions", null, "0a1b2c3d4e5f6a7b", "INC-1",
                "for", 1800);
        assertEquals("FOR", applied.getTier());
        assertEquals("2026-09-28T12:30:00Z", applied.getExpiresAt());
        assertEquals(List.of("r7"), applied.getRuleIds());
        assertEquals(PersistenceTier.FOR, applyCalls.get(0)[4]);
        assertEquals(Duration.ofSeconds(1800), applyCalls.get(0)[5]);
        RecipeResetResultData reset = proxy.resetRecipe("io.undertow:sessions", false);
        assertEquals(List.of("handler CONSOLE"), reset.getKeptSticky());

        RecipeListData list = proxy.listRecipes();
        RecipeData row = list.getRecipes().get(0);
        assertEquals("io.undertow:sessions", row.getId());
        assertEquals("LIBRARY", row.getSourceKind());
        assertEquals(List.of("b.war!/WEB-INF/lib/undertow.jar"), row.getOtherSourceLabels());
        assertEquals(List.of("line 1: 'recipes' is missing"), list.getBrokenFiles().get(0).getErrors());

        RecipeDetailData detail = proxy.showRecipe("io.undertow:sessions", null);
        assertEquals("line one\nline two", detail.getDescription());
        assertEquals("0a1b2c3d4e5f6a7b", detail.getFingerprint());
        assertEquals("DEBUG", detail.getChanges().get(0).getNewLevel());

        assertTrue(proxy.diagnose().stream().anyMatch(finding -> finding.getCheck().equals("recipe-files")),
                "doctor includes the recipe-files check");
    }

    @Test
    void unregister_whenNotRegistered_isSafeNoOp() throws Exception {
        JmxRegistrar.unregister(); // must not throw even though nothing is registered
    }
}
