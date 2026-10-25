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

        ExtensionEngine.applyAsSystemProperties(config());

        assertEquals("from-command-line", System.getProperty("logaperture.instanceId"));
        assertEquals("ERROR", System.getProperty("logaperture.diagnostics.level"));
    }

    private LogApertureConfig config() {
        Path stateHome = home;
        return new LogApertureConfig() {
            @Override public boolean enabled() { return true; }
            @Override public Optional<Path> vendorDefaults() { return Optional.empty(); }
            @Override public boolean stormDetection() { return false; }
            @Override public Optional<String> home() { return Optional.of(stateHome.toString()); }
            @Override public Optional<String> instanceId() { return Optional.of("extension-engine-test"); }
            @Override public Optional<String> recipes() { return Optional.empty(); }
            @Override public Audit audit() { return Optional::empty; }
            @Override public Diagnostics diagnostics() { return () -> Optional.of("ERROR"); }
            @Override public Drop drop() { return Optional::empty; }
            @Override public Optional<Boolean> followHandlers() { return Optional.empty(); }
        };
    }
}
