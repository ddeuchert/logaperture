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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test JVM runs with {@code java.util.logging.manager} already set to
 * JBoss LogManager (see the pom), so this is the "already installed" happy
 * path — the readiness gate proceeds and runs the callback. That leaves the
 * property-vs-class-loadable race (the intermittent "Could not load
 * Logmanager" against real WildFly, fixed by checking both) uncovered here —
 * it needs two independently-timed bootstrap steps that only a real
 * jboss-modules launch produces, not something this in-process test can
 * fake — so {@link #jbossLogManagerClassIsLoadable_trueOnThisTestJvm} locks
 * in just the new precondition check in isolation instead.
 */
class WildFlyLogManagerReadinessTest {

    @Test
    void awaitJBossLogManagerThen_runsTheCallbackWhenTheManagerIsInstalled() {
        assertEquals("org.jboss.logmanager.LogManager",
                java.util.logging.LogManager.getLogManager().getClass().getName(),
                "the pom installs JBoss LogManager for this test JVM");

        AtomicBoolean ran = new AtomicBoolean(false);
        WildFlyLogManagerReadiness.awaitJBossLogManagerThen(() -> ran.set(true));

        assertTrue(ran.get());
    }

    @Test
    void jbossLogManagerClassIsLoadable_trueOnThisTestJvm() {
        // The pom puts jboss-logmanager on the test classpath, so it is
        // loadable here the same way it is on a real WildFly once
        // jboss-modules has finished its own bootstrap.
        assertTrue(WildFlyLogManagerReadiness.jbossLogManagerClassIsLoadable());
    }
}
