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
package org.logaperture.container.wildfly;

import org.junit.jupiter.api.Test;

import java.util.logging.LogManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in Mechanism 1's API assumption: {@code
 * org.jboss.logmanager.LogManager.addConfigurationListener(Runnable)} exists
 * and resolves reflectively. If a future JBoss LogManager renames or
 * re-signatures the method, {@code registerConfigurationListener} returns
 * false and this test fails at build time — rather than the agent silently
 * falling back to the 30s sweep with no signal. The test JVM runs with
 * JBoss LogManager installed (see the pom). Its <em>firing</em> semantics
 * are exercised end to end by {@code logaperture-it}.
 */
class ConfigurationListenerHookTest {

    @Test
    void addConfigurationListener_resolvesAgainstJBossLogManager() {
        assertEquals("org.jboss.logmanager.LogManager", LogManager.getLogManager().getClass().getName(),
                "the pom installs JBoss LogManager for this test JVM");

        assertTrue(
                WildFlyContainerIntegration.registerConfigurationListener(LogManager.getLogManager(), () -> { }),
                "addConfigurationListener(Runnable) must resolve against JBoss LogManager");
    }

    @Test
    void registerConfigurationListener_returnsFalseForANonJBossLogManager() {
        // anonymous subclass -> class name is not "org.jboss.logmanager.LogManager"
        assertFalse(WildFlyContainerIntegration.registerConfigurationListener(new LogManager() {
        }, () -> { }));
    }
}
