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
package org.logaperture.container.none;

import java.lang.instrument.Instrumentation;
import java.util.function.BooleanSupplier;

/**
 * The {@code none} integration's readiness gate for a plain JVM on {@code java.util.logging}
 * (doc/specs/plain-jvm-jul.md #2). Waits, on a daemon thread, until the application has
 * initialised JUL, signalled by {@code java.util.logging.LogManager$RootLogger} being loaded,
 * then runs the install.
 *
 * <p>Class presence only: nothing here touches {@code java.util.logging} before the signal, so the
 * agent is never the first JUL caller and can never pre-empt a {@code java.util.logging.manager}
 * the application sets in {@code main} (logaperture-spec.md §15.6). It polls {@link
 * Instrumentation#getAllLoadedClasses()} rather than watching class loads, so it also finds a
 * class that was loaded before it started (an attach, #273) and does not depend on load hooks
 * firing for JDK classes from the shared archive.
 *
 * <p>It never gives up: an application may make its first JUL call late. After the first minute it
 * checks once a second, which costs one scan of the loaded classes a second in an application that
 * never uses JUL.
 */
final class JulLoadDetector {

    static final String ROOT_LOGGER_CLASS = "java.util.logging.LogManager$RootLogger";

    private static final long FAST_POLL_MS = 100;
    private static final long FAST_WINDOW_MS = 60_000;
    private static final long SLOW_POLL_MS = 1_000;

    private JulLoadDetector() {
    }

    static void awaitJulThen(Instrumentation inst, Runnable onReady) {
        BooleanSupplier julInitialised = () -> isLoaded(inst.getAllLoadedClasses(), ROOT_LOGGER_CLASS);
        Runnable detect = () -> await(julInitialised, FAST_POLL_MS, FAST_WINDOW_MS, SLOW_POLL_MS, onReady);
        Thread detectorThread = new Thread(detect, "logaperture-detector");
        detectorThread.setDaemon(true);
        detectorThread.start();
    }

    /**
     * Blocks until {@code initialised} holds, checking once straight away, then every {@code
     * fastPollMs} for {@code fastWindowMs}, then every {@code slowPollMs}; then runs {@code onReady}.
     * Returns without running it only if the thread is interrupted. Package-visible for the test.
     */
    static void await(BooleanSupplier initialised, long fastPollMs, long fastWindowMs, long slowPollMs,
            Runnable onReady) {
        long fastUntil = System.nanoTime() + fastWindowMs * 1_000_000L;
        while (!initialised.getAsBoolean()) {
            long pause = System.nanoTime() - fastUntil < 0 ? fastPollMs : slowPollMs;
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        onReady.run();
    }

    static boolean isLoaded(Class<?>[] loaded, String className) {
        for (Class<?> candidate : loaded) {
            if (className.equals(candidate.getName())) {
                return true;
            }
        }
        return false;
    }
}
