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
package org.logaperture.container.jbosslogmanager;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test JVM runs with {@code java.util.logging.manager} already set to
 * JBoss LogManager (see the pom), so the gate's "already installed" path is
 * exercised for real. What an in-process test cannot fake is jboss-modules'
 * actual bootstrap — the class visible only through a module classloader —
 * so that is left to the real-WildFly IT; the pieces of the gate are covered
 * individually here.
 */
class JBossLogManagerReadinessTest {

    private static final Class<?> JBOSS_MANAGER_CLASS = org.jboss.logmanager.LogManager.class;

    @Test
    void awaitJBossLogManagerThen_runsTheCallbackOnceTheClassIsLoaded() {
        assertEquals(JBossLogManagerReadiness.JBOSS_LOG_MANAGER,
                java.util.logging.LogManager.getLogManager().getClass().getName(),
                "the pom installs JBoss LogManager for this test JVM");

        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicInteger polls = new AtomicInteger();
        Supplier<Class<?>> appearsOnThirdPoll = () -> polls.incrementAndGet() < 3 ? null : JBOSS_MANAGER_CLASS;

        JBossLogManagerReadiness.awaitJBossLogManagerThen(appearsOnThirdPoll, 1, 5_000, () -> ran.set(true));

        assertTrue(ran.get());
        assertEquals(3, polls.get());
    }

    @Test
    void awaitJBossLogManagerThen_givesUpWithoutRunningTheCallbackWhenTheClassNeverLoads() {
        AtomicBoolean ran = new AtomicBoolean(false);

        JBossLogManagerReadiness.awaitJBossLogManagerThen(() -> null, 1, 50, () -> ran.set(true));

        assertFalse(ran.get());
    }

    @Test
    void findClassByName_findsAPresentClassAndReturnsNullForAnAbsentOne() {
        Class<?>[] loaded = {String.class, JBOSS_MANAGER_CLASS, Integer.class};

        assertSame(JBOSS_MANAGER_CLASS,
                JBossLogManagerReadiness.findClassByName(loaded, JBossLogManagerReadiness.JBOSS_LOG_MANAGER));
        assertNull(JBossLogManagerReadiness.findClassByName(loaded, "no.such.Manager"));
        assertNull(JBossLogManagerReadiness.findClassByName(new Class<?>[0], "java.lang.String"));
    }

    @Test
    void callWithContextClassLoader_setsTheLoaderForTheCallAndRestoresTheOriginalAfter() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleLoader = new URLClassLoader(new URL[0], null);

        Supplier<ClassLoader> readContextLoader = () -> Thread.currentThread().getContextClassLoader();
        ClassLoader seenDuringCall = JBossLogManagerReadiness.callWithContextClassLoader(moduleLoader, readContextLoader);

        assertSame(moduleLoader, seenDuringCall);
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void callWithContextClassLoader_restoresTheOriginalWhenTheCallThrows() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleLoader = new URLClassLoader(new URL[0], null);

        Supplier<String> failing = () -> {
            throw new IllegalStateException("boom");
        };
        assertThrows(IllegalStateException.class,
                () -> JBossLogManagerReadiness.callWithContextClassLoader(moduleLoader, failing));

        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void callWithContextClassLoader_leavesTheContextLoaderAloneForABootstrapLoadedClass() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();

        Supplier<ClassLoader> readContextLoader = () -> Thread.currentThread().getContextClassLoader();
        ClassLoader seenDuringCall = JBossLogManagerReadiness.callWithContextClassLoader(null, readContextLoader);

        assertSame(original, seenDuringCall);
    }

    @Test
    void noExtraCondition_holdsForAnyLoadedClasses() {
        assertTrue(JBossLogManagerReadiness.NO_EXTRA_CONDITION.holds(new Class<?>[0]));
        assertTrue(JBossLogManagerReadiness.NO_EXTRA_CONDITION.holds(new Class<?>[] {String.class}));
    }

    @Test
    void julInitialisationStarted_holdsOnlyOnceLogContextIsLoaded() {
        assertFalse(JBossLogManagerReadiness.JUL_INITIALISATION_STARTED.holds(
                new Class<?>[] {String.class, JBOSS_MANAGER_CLASS}));
        assertTrue(JBossLogManagerReadiness.JUL_INITIALISATION_STARTED.holds(
                new Class<?>[] {String.class, org.jboss.logmanager.LogContext.class}));
    }

    @Test
    void loadedManagerClass_waitsForTheExtraConditionAsWellAsTheManagerClass() {
        java.lang.instrument.Instrumentation inst = instrumentationReporting(
                new Class<?>[] {String.class, JBOSS_MANAGER_CLASS});

        assertSame(JBOSS_MANAGER_CLASS,
                JBossLogManagerReadiness.loadedManagerClass(inst, JBossLogManagerReadiness.NO_EXTRA_CONDITION).get());
        assertNull(JBossLogManagerReadiness.loadedManagerClass(inst, loaded -> false).get(),
                "manager class loaded but the container's extra condition not yet true");
    }

    @Test
    void loadedManagerClass_isNullWhileTheManagerClassItselfIsAbsent() {
        java.lang.instrument.Instrumentation inst = instrumentationReporting(new Class<?>[] {String.class});

        assertNull(JBossLogManagerReadiness.loadedManagerClass(inst, JBossLogManagerReadiness.NO_EXTRA_CONDITION).get());
    }

    private static java.lang.instrument.Instrumentation instrumentationReporting(Class<?>[] loaded) {
        return (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
                JBossLogManagerReadinessTest.class.getClassLoader(),
                new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAllLoadedClasses")) {
                        return loaded;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
