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
package org.logaperture.startup;

import org.logaperture.bridge.Diagnostics;
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.AuditRecord;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.LibraryRecipeScanner;
import org.logaperture.core.RecipeCatalog;
import org.logaperture.core.RecipeOperations;
import org.logaperture.core.RecipeService;
import org.logaperture.core.StormDetectionSwitch;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.VendorDefaultsFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The start-up work every delivery route shares (doc/specs/quarkus-extension.md #10): the agent
 * calls it after its readiness gate, the Quarkus extension directly from its recorder. Every
 * method is fail-open (doc/logaperture-spec.md §9): a failure is reported and leaves the
 * application's logging alone.
 */
public final class EngineStartup {

    /**
     * Set (to the engine's version) only once the first logging context has installed, so its
     * presence is a reliable "the control plane is up" marker -- what {@code logaperture-cli}'s
     * discovery filters candidate JVMs on (doc/specs/cli-transport.md "Discovery").
     */
    public static final String VERSION_PROPERTY = "logaperture.version";

    /**
     * Which delivery route owns this JVM (doc/specs/quarkus-extension.md #3, #7): {@code agent} or
     * {@code extension}. The agent claims it first thing in {@code premain}, which always runs before
     * {@code main}, so when both are present the agent wins every time and the extension stands
     * down. {@code logctl env} names the route from it.
     */
    public static final String DELIVERY_PROPERTY = "logaperture.delivery";

    /** How LogAperture was delivered into this JVM. */
    public enum Delivery {
        AGENT("agent", ""),
        EXTENSION("extension", " extension");

        private final String id;
        private final String bannerSuffix;

        Delivery(String id, String bannerSuffix) {
            this.id = id;
            this.bannerSuffix = bannerSuffix;
        }

        /** The {@link #DELIVERY_PROPERTY} value. */
        public String id() {
            return id;
        }
    }

    private EngineStartup() {
    }

    /**
     * Claims this JVM for {@code delivery}, atomically: {@code true} if no route had claimed it yet.
     * A {@code false} return means another route owns it; the caller starts nothing.
     */
    public static boolean claim(Delivery delivery) {
        return System.getProperties().putIfAbsent(DELIVERY_PROPERTY, delivery.id()) == null;
    }

    /** The route that claimed this JVM, if any. */
    public static Optional<String> claimant() {
        return Optional.ofNullable(System.getProperty(DELIVERY_PROPERTY));
    }

    /**
     * Reads and validates the vendor defaults file, if one is named. Never touches {@code
     * java.util.logging} (the agent calls it on the {@code premain} thread, logaperture-spec.md
     * §15.6). Never throws: a rejected file is reported and the engine carries on without it.
     */
    public static VendorDefaults loadVendorDefaults(Optional<Path> file) {
        try {
            if (file.isEmpty()) {
                return VendorDefaults.none();
            }
            VendorDefaults vendorDefaults = VendorDefaultsFile.load(file.get());
            reportVendorDefaults(vendorDefaults);
            return vendorDefaults;
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to read the vendor defaults file, continuing without it", e);
            return VendorDefaults.none();
        }
    }

    /**
     * Validates the vendor defaults file bundled in a Quarkus application (doc/specs/
     * bundled-vendor-defaults.md), already checked when the application was built (#3). Never throws:
     * a file rejected here after all is reported and the engine carries on without it.
     */
    public static VendorDefaults loadBundledVendorDefaults(String content) {
        try {
            VendorDefaults vendorDefaults = VendorDefaultsFile.parseBundled(content, VendorDefaultsFile.BUNDLED_RESOURCE);
            reportVendorDefaults(vendorDefaults);
            return vendorDefaults;
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to read the bundled vendor defaults file, continuing without it", e);
            return VendorDefaults.none();
        }
    }

    private static void reportVendorDefaults(VendorDefaults vendorDefaults) {
        String path = vendorDefaults.source().orElse("?");
        if (vendorDefaults.status() == VendorDefaults.Status.REJECTED) {
            Diagnostics.warn("vendor defaults file " + path + " was rejected -- none of its settings "
                    + "apply:\n  " + String.join("\n  ", vendorDefaults.errors()));
            return;
        }
        // Loaded: the startup banner names it (doc/specs/quieter-output.md Q3).
        if (vendorDefaults.writable()) {
            Diagnostics.warn("vendor defaults file " + path + " (or its directory) is writable by the "
                    + "account this JVM runs as -- anyone who can run code as that account can change the "
                    + "baseline logging configuration");
        }
    }

    /**
     * The callback a container integration runs once its first context is installed: audit the
     * vendor defaults load, then register the control surface and publish the marker.
     *
     * @param recipeScanner where library recipes are found -- the agent walks its loaded classes,
     *                      the extension the application's class loader (quarkus-extension.md #6)
     */
    public static Consumer<AggregateLevelControl> onFirstContextReady(String containerId, Delivery delivery,
            CapabilityPolicy policy, AuditLog auditLog, VendorDefaults vendorDefaults,
            LibraryRecipeScanner recipeScanner) {
        return operations -> {
            auditVendorDefaults(vendorDefaults, auditLog);
            publishControlSurface(containerId, delivery, operations,
                    recipes(recipeScanner, policy, vendorDefaults, operations), vendorDefaults);
        };
    }

    /**
     * {@code list recipes} / {@code show recipe} (doc/specs/recipes.md): the vendor defaults file's
     * recipes, the recipes folder, and the libraries {@code scanner} finds. Never throws -- a
     * failure here leaves the rest of the control surface working, with no recipes.
     */
    public static RecipeOperations recipes(LibraryRecipeScanner scanner, CapabilityPolicy policy,
            VendorDefaults vendorDefaults, AggregateLevelControl operations) {
        try {
            RecipeCatalog catalog = new RecipeCatalog(vendorDefaults.recipes(), RecipeCatalog.defaultFolder(), scanner);
            return new RecipeService(policy, catalog, operations, operations, operations);
        } catch (RuntimeException e) {
            Diagnostics.warn("recipes are unavailable", e);
            return RecipeOperations.none();
        }
    }

    /**
     * One audit record for a loaded vendor defaults file, naming its path and hash -- doc/specs/
     * quieter-output.md Q1 -- in place of one per entry. A rejected file writes none (its errors are
     * reported instead).
     */
    public static void auditVendorDefaults(VendorDefaults vendorDefaults, AuditLog auditLog) {
        if (vendorDefaults.status() != VendorDefaults.Status.LOADED) {
            return;
        }
        try {
            auditLog.record(new AuditRecord(Instant.now(), System.getProperty("user.name", "unknown"),
                    VendorDefaults.AUDIT_SOURCE, vendorDefaults.source().orElse("?"), null,
                    "loaded sha256=" + vendorDefaults.sha256().orElse("?") + " (" + vendorDefaults.summary() + ")",
                    null, AuditRecord.Action.MUTATION).withTarget(AuditRecord.Target.FILE));
        } catch (RuntimeException e) {
            Diagnostics.warn("failed to audit the vendor defaults load", e);
        }
    }

    /** The agent's startup line, with storm detection disabled. */
    public static String banner(String version, String containerId, VendorDefaults vendorDefaults, int restored) {
        return banner(version, containerId, Delivery.AGENT, vendorDefaults, restored, (String) null);
    }

    /**
     * The startup line, with the storm-detection part from the switch's position once any saved
     * setting is resumed (doc/specs/storm-detection-toggle.md "Restart"): {@code storm detection
     * on}, {@code ... on until <time>} or {@code ... on (sticky)}; nothing when disabled.
     */
    public static String banner(String version, String containerId, Delivery delivery, VendorDefaults vendorDefaults,
            int restored, StormDetectionSwitch.State stormDetection) {
        String part = null;
        if (stormDetection.enabled()) {
            part = switch (stormDetection.tier()) {
                case FOR -> "storm detection on until " + stormDetection.expiresAt();
                case STICKY -> "storm detection on (sticky)";
                case SESSION -> "storm detection on";
            };
        }
        return banner(version, containerId, delivery, vendorDefaults, restored, part);
    }

    /**
     * The one startup line -- doc/specs/quieter-output.md Q3: version, container (and {@code
     * extension} when delivered as one, quarkus-extension.md #7), the vendor defaults file and what
     * it set, how many saved settings were restored, and {@code storm detection on} when it starts
     * enabled (doc/specs/storm-detection-toggle.md "Starting state"; disabled, the default, says
     * nothing).
     */
    private static String banner(String version, String containerId, Delivery delivery, VendorDefaults vendorDefaults,
            int restored, String stormDetection) {
        StringBuilder line = new StringBuilder("LogAperture ").append(version).append(" active (")
                .append(switch (containerId) {
                    case "wildfly" -> "WildFly";
                    case "quarkus" -> "Quarkus";
                    default -> "JVM";
                }).append(delivery.bannerSuffix).append(')');
        List<String> parts = new ArrayList<>();
        if (vendorDefaults.status() == VendorDefaults.Status.LOADED) {
            parts.add("vendor defaults " + vendorDefaults.source().orElse("?") + " ("
                    + vendorDefaults.summary() + ")");
        }
        if (restored > 0) {
            parts.add(restored + (restored == 1 ? " sticky setting" : " sticky settings") + " restored");
        }
        if (stormDetection != null) {
            parts.add(stormDetection);
        }
        if (!parts.isEmpty()) {
            line.append(": ").append(String.join("; ", parts));
        }
        return line.toString();
    }

    private static void publishControlSurface(String containerId, Delivery delivery, AggregateLevelControl operations,
            RecipeOperations recipes, VendorDefaults vendorDefaults) {
        try {
            JmxRegistrar.register(operations, operations, operations, operations, operations, operations, operations,
                    operations, recipes); // AggregateLevelControl implements all eight operation interfaces
            System.setProperty(VERSION_PROPERTY, version());
            Diagnostics.notice(banner(version(), containerId, delivery, vendorDefaults, operations.restoredSettings(),
                    operations.stormDetectionSwitch().current()));
        } catch (Throwable t) {
            Diagnostics.error("LogAperture failed to register the JMX control surface", t);
        }
    }

    /**
     * The reverse of publishing (doc/specs/quarkus-extension.md #4): unregisters the control surface
     * and clears the marker and the claim, so the next start in this JVM -- a Quarkus dev-mode
     * restart -- publishes afresh. Never throws.
     */
    public static void withdrawControlSurface() {
        try {
            if (java.lang.management.ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME)) {
                JmxRegistrar.unregister();
            }
        } catch (Throwable t) {
            Diagnostics.warn("failed to unregister the JMX control surface", t);
        }
        System.clearProperty(VERSION_PROPERTY);
        System.clearProperty(DELIVERY_PROPERTY);
    }

    /** The engine's version: the shaded jar's {@code Implementation-Version}, or {@code dev} from a classes dir. */
    public static String version() {
        String version = EngineStartup.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
