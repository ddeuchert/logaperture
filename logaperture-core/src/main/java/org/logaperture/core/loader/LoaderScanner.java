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
package org.logaperture.core.loader;

import org.logaperture.bridge.Diagnostics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Finds logging frameworks that live outside the system class path: a Spring Boot fat jar, a
 * Tomcat webapp, a WildFly deployment (doc/specs/class-loader-detector.md, "Scope" 1).
 *
 * <p>One daemon thread, one {@code Instrumentation.getAllLoadedClasses()} per tick (C5), every
 * {@link FrameworkWatch} checked against the result by marker-class <em>name</em>. It loads no
 * class and calls into no framework (the readiness constraint, "Scope" 7; spike I-4). The first
 * time a loader is seen for a framework, the {@link Listener} is told; a loader already handled is
 * not reported again, tracked by identity ({@link LoaderIdentitySet}, WF5).
 */
public final class LoaderScanner {

    /** Told when a framework's marker class is found in a loader not seen before. */
    @FunctionalInterface
    public interface Listener {
        void found(FrameworkWatch watch, ClassLoader owner);
    }

    /**
     * The polling cadence (C4): {@code fastEvery} for the first {@code fastFor} after start, then
     * {@code slowEvery}, and never give up -- a webapp can be deployed at any time.
     */
    public record Cadence(Duration fastEvery, Duration fastFor, Duration slowEvery) {
        /** 100 ms for 60 s, then 1 s -- the numbers already measured for the JUL detector. */
        public static Cadence standard() {
            return new Cadence(Duration.ofMillis(100), Duration.ofSeconds(60), Duration.ofSeconds(1));
        }
    }

    private final Supplier<Class<?>[]> loadedClasses;
    private final Map<String, List<FrameworkWatch>> watchesByMarker = new HashMap<>();
    private final Map<String, LoaderIdentitySet> seen = new ConcurrentHashMap<>();
    private final Listener listener;
    private final Cadence cadence;
    private volatile boolean stopped;
    private Thread thread;

    public LoaderScanner(Supplier<Class<?>[]> loadedClasses, List<FrameworkWatch> watches, Listener listener,
                         Cadence cadence) {
        this.loadedClasses = Objects.requireNonNull(loadedClasses, "loadedClasses");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.cadence = Objects.requireNonNull(cadence, "cadence");
        for (FrameworkWatch watch : watches) {
            watchesByMarker.computeIfAbsent(watch.markerClass(), k -> new ArrayList<>()).add(watch);
            seen.putIfAbsent(watch.framework(), new LoaderIdentitySet());
        }
    }

    /** Starts the daemon scan thread. Calling it twice has no effect. */
    public synchronized void start() {
        if (thread != null || watchesByMarker.isEmpty()) {
            return;
        }
        thread = new Thread(this::run, "logaperture-loader-scan");
        thread.setDaemon(true);
        // A container attributes a thread to the loader that created it; the system loader is the
        // agent's and is never stopped (doc/specs/class-loader-detector.md, "Scope" 5, TC7).
        thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
        thread.start();
    }

    public void stop() {
        stopped = true;
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Makes {@code owner} eligible to be found again for {@code framework}, once its instance has
     * let go but the loader itself lives on.
     */
    public void forget(String framework, ClassLoader owner) {
        LoaderIdentitySet set = seen.get(framework);
        if (set != null) {
            set.remove(owner);
        }
    }

    /** One scan tick. Public for the tests; the thread calls it on the cadence. */
    public void scanOnce() {
        Class<?>[] classes = loadedClasses.get();
        for (Class<?> c : classes) {
            List<FrameworkWatch> watches = watchesByMarker.get(c.getName());
            if (watches == null) {
                continue;
            }
            ClassLoader owner = c.getClassLoader();
            if (owner == null) {
                continue; // bootstrap: not a place a bundled framework lives
            }
            for (FrameworkWatch watch : watches) {
                if (seen.get(watch.framework()).add(owner)) {
                    report(watch, owner);
                }
            }
        }
    }

    private void report(FrameworkWatch watch, ClassLoader owner) {
        try {
            listener.found(watch, owner);
        } catch (Throwable t) {
            // A bind that throws must not end the scan: later loaders still need finding.
            Diagnostics.error("LogAperture could not bind " + watch.framework() + " found in "
                    + owner.getClass().getName(), t);
        }
    }

    private void run() {
        long startedAt = System.nanoTime();
        while (!stopped) {
            try {
                scanOnce();
            } catch (Throwable t) {
                Diagnostics.error("LogAperture loader scan failed", t);
            }
            Duration pause = Duration.ofNanos(System.nanoTime() - startedAt).compareTo(cadence.fastFor()) < 0
                    ? cadence.fastEvery() : cadence.slowEvery();
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
