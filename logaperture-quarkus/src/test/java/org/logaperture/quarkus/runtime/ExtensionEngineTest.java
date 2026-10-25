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
package org.logaperture.quarkus.runtime;

import io.quarkus.runtime.ShutdownContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.control.jmx.JmxRegistrar;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/quarkus-extension.md #11: the engine starts through the shared start-up code with no
 * agent on the class path (this module never depends on it), and #4: it stops on shutdown and starts
 * again in the same JVM, as on each dev-mode restart.
 */
class ExtensionEngineTest {

    private static final List<String> PROPERTIES = List.of("logaperture.version", "logaperture.delivery",
            "logaperture.home", "logaperture.instanceId", "logaperture.diagnostics.level");

    @TempDir
    Path home;

    private final List<Runnable> shutdownTasks = new ArrayList<>();
    private final ShutdownContext shutdown = new ShutdownContext() {
        @Override
        public void addShutdownTask(Runnable task) {
            shutdownTasks.add(task);
        }

        @Override
        public void addLastShutdownTask(Runnable task) {
            shutdownTasks.add(task);
        }
    };

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        runShutdown();
        if (ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME)) {
            JmxRegistrar.unregister();
        }
        PROPERTIES.forEach(System::clearProperty);
    }

    private void runShutdown() {
        List<Runnable> tasks = new ArrayList<>(shutdownTasks);
        shutdownTasks.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void agentIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.logaperture.agent.LogApertureAgent"));
    }

    @Test
    void startsInProcess_stopsOnShutdown_andStartsAgain() {
        ExtensionEngine.start(config(), shutdown);

        assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME));
        assertEquals("extension", System.getProperty("logaperture.delivery"));
        assertTrue(System.getProperty("logaperture.version") != null, "the discovery marker is published");
        assertEquals(home.toString(), System.getProperty("logaperture.home"), "#8: config became the property");

        runShutdown();

        assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME));
        assertNull(System.getProperty("logaperture.version"));
        assertNull(System.getProperty("logaperture.delivery"));

        ExtensionEngine.start(config(), shutdown);

        assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME),
                "a second start in the same JVM -- a dev-mode restart -- publishes again");
    }

    @Test
    void anotherRouteOwningTheJvm_startsNothing() {
        System.setProperty("logaperture.delivery", "agent");

        ExtensionEngine.start(config(), shutdown);

        assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME));
        assertTrue(shutdownTasks.isEmpty());
    }

    @Test
    void aPropertyOnTheCommandLineWinsOverConfig() {
        System.setProperty("logaperture.instanceId", "from-command-line");

        List<String> set = ExtensionEngine.applyAsSystemProperties(config());

        assertEquals("from-command-line", System.getProperty("logaperture.instanceId"));
        assertEquals("ERROR", System.getProperty("logaperture.diagnostics.level"));
        assertFalse(set.contains("logaperture.instanceId"), "a command-line property is not the extension's to clear");
    }

    /** A setting changed between two starts (a dev-mode reload) takes effect: the first start's value is cleared. */
    @Test
    void aChangedSettingAppliesAtTheNextStart() {
        ExtensionEngine.start(config(), shutdown);
        assertEquals("ERROR", System.getProperty("logaperture.diagnostics.level"));

        runShutdown();
        assertNull(System.getProperty("logaperture.diagnostics.level"), "stop clears what start set");

        ExtensionEngine.start(config("WARN", false), shutdown);
        assertEquals("WARN", System.getProperty("logaperture.diagnostics.level"));
    }

    /** A start that fails after claiming the JVM releases the claim, so the next start is not refused. */
    @Test
    void aFailedStart_leavesNothingBehind() {
        ExtensionEngine.start(config("ERROR", true), shutdown);

        assertNull(System.getProperty("logaperture.delivery"), "the claim was released");
        assertNull(System.getProperty("logaperture.home"));
        assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME));

        ExtensionEngine.start(config(), shutdown);
        assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(JmxRegistrar.OBJECT_NAME));
    }

    /** #3: an agent from before the extension sets no claim, but its marker shows it is there. */
    @Test
    void anOlderAgentIsRecognisedByItsMarker() {
        assertNull(LogApertureRecorder.owner());

        System.setProperty("logaperture.version", "1.0.0-beta.3");

        assertEquals("agent", LogApertureRecorder.owner());
    }

    private LogApertureConfig config() {
        return config("ERROR", false);
    }

    /** @param failing {@code vendorDefaults()} throws, so start fails after the claim */
    private LogApertureConfig config(String diagnosticsLevel, boolean failing) {
        Path stateHome = home;
        return new LogApertureConfig() {
            @Override public boolean enabled() { return true; }
            @Override public Optional<Path> vendorDefaults() {
                if (failing) {
                    throw new IllegalStateException("a start that fails after the claim");
                }
                return Optional.empty();
            }
            @Override public boolean stormDetection() { return false; }
            @Override public Optional<String> home() { return Optional.of(stateHome.toString()); }
            @Override public Optional<String> instanceId() { return Optional.of("extension-engine-test"); }
            @Override public Optional<String> recipes() { return Optional.empty(); }
            @Override public Audit audit() { return Optional::empty; }
            @Override public Diagnostics diagnostics() { return () -> Optional.of(diagnosticsLevel); }
            @Override public Drop drop() { return Optional::empty; }
            @Override public Optional<Boolean> followHandlers() { return Optional.empty(); }
        };
    }
}
