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

/**
 * The premain gotcha, handled (doc/specs/wildfly-support.md, "Detection and
 * the premain gotcha"; §15.6, "The premain gotcha that will cost you a
 * day"). WildFly relies on <em>nothing</em> touching {@code
 * java.util.logging} before {@code java.util.logging.manager} takes effect
 * — the first JUL call installs whatever {@code LogManager} is current, and
 * if that happens too early it is the JDK default, not JBoss LogManager,
 * and WildFly's logging bootstrap breaks.
 *
 * <p>So this class polls two <em>side channels</em> — never a JUL method —
 * until both agree JBoss LogManager is really in place: the {@code
 * java.util.logging.manager} system property string, and (issue: an
 * intermittent "Could not load Logmanager" against real WildFly) whether
 * {@code org.jboss.logmanager.LogManager} is actually loadable yet. jboss-
 * modules sets the property and makes the class loadable in two separate,
 * non-atomic steps of its own bootstrap; polling only the property lost that
 * race under load — property already flipped, class not yet resolvable via
 * the system classloader — which drove {@code java.util.logging.LogManager}'s
 * own static initializer to fail loading the named class and silently fall
 * back to the JDK default manager instead. That happens exactly once per JVM
 * and cannot be undone once it happens, so a fixed-delay guess ("settle a
 * bit, then hope") is not good enough here — the class-loadable check is the
 * real precondition. Only once both checks pass, after a short settle, does
 * this touch {@code LogManager.getLogManager()} for the first time (which at
 * that point simply returns the already-installed JBoss one).
 */
final class WildFlyLogManagerReadiness {

    static final String JBOSS_LOG_MANAGER = "org.jboss.logmanager.LogManager";
    private static final String MANAGER_PROPERTY = "java.util.logging.manager";

    private static final long POLL_INTERVAL_MS = 100;
    private static final long SETTLE_MS = 500;
    private static final int MAX_POLL_ATTEMPTS = 600; // ~60s -- WildFly installs it within the first second

    private WildFlyLogManagerReadiness() {
    }

    /**
     * Blocks the calling thread until JBoss LogManager is the installed
     * {@code java.util.logging.LogManager}, then runs {@code onReady}. Give
     * this its own daemon thread. If the manager never appears (not really
     * WildFly after all, or a broken launch) it gives up with a diagnostic
     * and does not run {@code onReady}.
     */
    static void awaitJBossLogManagerThen(Runnable onReady) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            if (JBOSS_LOG_MANAGER.equals(System.getProperty(MANAGER_PROPERTY)) && jbossLogManagerClassIsLoadable()) {
                sleep(SETTLE_MS);
                // First JUL touch -- safe now: the property says JBoss AND
                // the class is confirmed loadable, so this returns the
                // already-installed JBoss LogManager rather than installing
                // the JDK default.
                String installed = java.util.logging.LogManager.getLogManager().getClass().getName();
                if (JBOSS_LOG_MANAGER.equals(installed)) {
                    onReady.run();
                    return;
                }
                Diagnostics.warn("LogAperture: java.util.logging.manager is set to JBoss LogManager but the "
                        + "installed LogManager is " + installed + "; not installing WildFly level control");
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        Diagnostics.warn("LogAperture: java.util.logging.manager never became " + JBOSS_LOG_MANAGER
                + " within " + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS) + "ms; not installing WildFly level control");
    }

    /**
     * True once {@code org.jboss.logmanager.LogManager} is actually
     * resolvable via the system classloader — the same lookup {@code
     * java.util.logging.LogManager}'s own static initializer performs
     * internally the moment something first touches it. Non-initializing
     * ({@code Class.forName(..., false, ...)}): no static block runs, no
     * {@code java.util.logging} class is touched, only class *presence* is
     * checked — same discipline as {@code WildFlyContainerIntegration
     * .isClassPresent}. Package-visible so a test can call it directly.
     */
    static boolean jbossLogManagerClassIsLoadable() {
        try {
            Class.forName(JBOSS_LOG_MANAGER, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Throwable notYetLoadable) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
