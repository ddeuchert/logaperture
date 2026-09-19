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

import org.logaperture.bridge.Diagnostics;

import java.lang.instrument.Instrumentation;
import java.util.function.Supplier;

/**
 * The premain gotcha, handled (doc/specs/wildfly-support.md, "Detection and
 * the premain gotcha"; §15.6, "The premain gotcha that will cost you a
 * day"). WildFly relies on <em>nothing</em> touching {@code
 * java.util.logging} before JBoss LogManager is in place — the first JUL
 * call installs whatever {@code LogManager} it can load, and if that fails
 * it silently installs the JDK default, once per JVM and unrecoverably,
 * breaking WildFly's logging bootstrap.
 *
 * <p>{@code java.util.logging.LogManager}'s static initializer loads the
 * class named by {@code java.util.logging.manager} from the <em>system
 * classloader</em>, falling back to the <em>calling thread's context
 * classloader</em>. On WildFly {@code org.jboss.logmanager.LogManager} is
 * never visible to the system classloader — WildFly's own main thread
 * reaches it because jboss-modules sets a module classloader as that
 * thread's context classloader — so "loadable via the system classloader"
 * is not a usable readiness signal (waiting for it always times out).
 *
 * <p>So this class waits, without touching JUL, for the observable fact
 * that jboss-modules has <em>loaded</em> the class through some classloader
 * (found via {@link Instrumentation#getAllLoadedClasses()}), then makes the
 * first JUL call itself with the context classloader temporarily set to
 * that class's loader — exactly as WildFly does. Whichever thread wins the
 * race, JUL gets JBoss LogManager, so no fixed settle delay is needed.
 */
final class WildFlyLogManagerReadiness {

    static final String JBOSS_LOG_MANAGER = "org.jboss.logmanager.LogManager";
    private static final String MANAGER_PROPERTY = "java.util.logging.manager";

    private static final long POLL_INTERVAL_MS = 100;
    private static final long MAX_WAIT_MS = 60_000; // WildFly loads it within the first second or two

    private WildFlyLogManagerReadiness() {
    }

    /**
     * Blocks the calling thread until JBoss LogManager is the installed
     * {@code java.util.logging.LogManager}, then runs {@code onReady}. Give
     * this its own daemon thread. If the manager never appears (not really
     * WildFly after all, or a broken launch) it gives up with a diagnostic
     * and does not run {@code onReady}.
     */
    static void awaitJBossLogManagerThen(Instrumentation inst, Runnable onReady) {
        // The property check is cheap; only then pay for scanning every loaded class.
        Supplier<Class<?>> loadedManagerClass = () -> JBOSS_LOG_MANAGER.equals(System.getProperty(MANAGER_PROPERTY))
                ? findClassByName(inst.getAllLoadedClasses(), JBOSS_LOG_MANAGER)
                : null;
        awaitJBossLogManagerThen(loadedManagerClass, POLL_INTERVAL_MS, MAX_WAIT_MS, onReady);
    }

    /** Package-visible for {@code WildFlyLogManagerReadinessTest}. */
    static void awaitJBossLogManagerThen(
            Supplier<Class<?>> loadedManagerClass, long pollIntervalMs, long maxWaitMs, Runnable onReady) {
        long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
        Class<?> managerClass;
        while ((managerClass = loadedManagerClass.get()) == null) {
            if (System.nanoTime() - deadline >= 0) {
                Diagnostics.warn("LogAperture: " + JBOSS_LOG_MANAGER + " was not loaded within " + maxWaitMs
                        + "ms; not installing WildFly level control");
                return;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        ClassLoader managerLoader = managerClass.getClassLoader();
        Diagnostics.debug("LogAperture: " + JBOSS_LOG_MANAGER + " loaded by " + managerLoader);
        Supplier<String> firstJulCall = () -> java.util.logging.LogManager.getLogManager().getClass().getName();
        String installed = callWithContextClassLoader(managerLoader, firstJulCall);
        if (JBOSS_LOG_MANAGER.equals(installed)) {
            onReady.run();
            return;
        }
        Diagnostics.warn("LogAperture: " + JBOSS_LOG_MANAGER + " is loaded but the installed LogManager is "
                + installed + "; not installing WildFly level control");
    }

    /** The class with the given name, or {@code null} if it is not in {@code classes}. */
    static Class<?> findClassByName(Class<?>[] classes, String className) {
        for (Class<?> candidate : classes) {
            if (className.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Runs {@code call} with the current thread's context classloader set to
     * {@code loader} (left alone if {@code null}, i.e. the bootstrap loader),
     * restoring the original afterwards even if {@code call} throws.
     */
    static <T> T callWithContextClassLoader(ClassLoader loader, Supplier<T> call) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            if (loader != null) {
                thread.setContextClassLoader(loader);
            }
            return call.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}
