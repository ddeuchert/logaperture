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

import org.logaperture.adapter.logback.AdapterInstallException;
import org.logaperture.adapter.logback.LogbackAdapterFactory;
import org.logaperture.bridge.Diagnostics;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects "Logback has loaded" via a one-shot {@link ClassFileTransformer}
 * registered at {@code premain} time — the reliable, class-load-time
 * pattern per doc/logaperture-spec.md §4.1, not the M0 spike's {@code
 * Instrumentation.getAllLoadedClasses()} polling shortcut.
 *
 * <p>A class that is already loaded when the agent starts (a dynamic attach, doc/specs/
 * logctl-attach.md #7) is found by scanning {@link Instrumentation#getAllLoadedClasses()} once, after
 * the transformer is registered.
 *
 * <p>Lives in {@code logaperture-container-none} because it is the {@code
 * none} integration's readiness gate (doc/specs/wildfly-support.md, Slice 1:
 * "The Logback readiness poll moves behind the {@code none} integration").
 *
 * <p>The transformer only <em>observes</em> the class-load event — it
 * returns {@code null} (unmodified bytecode) unconditionally, so it needs
 * no retransform capability (see the agent module's pom: {@code
 * Can-Retransform-Classes: false}).
 *
 * <p>Deliberately holds no compile-time reference to any {@code
 * ch.qos.logback} type. Readiness is confirmed by repeatedly attempting
 * {@link LogbackAdapterFactory#forCurrentContext()} as a probe — succeeding
 * means SLF4J is genuinely bound, not just that the class loaded (see the
 * M0 Spring Boot spike's {@code SubstituteLoggerFactory} finding,
 * doc/spikes/m0-adapter-grid.md point 3).
 */
final class LogbackLoadDetector {

    private static final String TARGET_CLASS_INTERNAL_NAME = "ch/qos/logback/classic/LoggerContext";
    private static final String TARGET_CLASS_NAME = TARGET_CLASS_INTERNAL_NAME.replace('/', '.');
    private static final long POLL_INTERVAL_MS = 50;
    private static final int MAX_POLL_ATTEMPTS = 40; // ~2s total

    private LogbackLoadDetector() {
    }

    static void awaitLogbackAndThen(Instrumentation inst, Runnable onReady) {
        Runnable confirmBoundThenRun = () -> confirmBoundThenRun(onReady);
        watch(inst, confirmBoundThenRun);
    }

    /**
     * Runs {@code startDetection} on a daemon thread, once, as soon as {@code LoggerContext} is
     * loaded: either by a load seen from now on, or because it was loaded already (an attach,
     * doc/specs/logctl-attach.md #7 -- the class-load event has long passed, so the transformer
     * alone would wait forever). The transformer is registered before the scan, so a load that
     * lands between the two is seen by at least one of them; a flag makes it start once. A {@code
     * -javaagent} start never finds the class loaded, so for it this is the transformer path only.
     * Package-visible for the test.
     */
    static void watch(Instrumentation inst, Runnable startDetection) {
        AtomicBoolean started = new AtomicBoolean();
        Runnable startOnce = () -> {
            if (started.compareAndSet(false, true)) {
                Thread detectorThread = new Thread(startDetection, "logaperture-detector");
                detectorThread.setDaemon(true);
                detectorThread.start();
            }
        };
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    java.security.ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                if (TARGET_CLASS_INTERNAL_NAME.equals(className)) {
                    inst.removeTransformer(this); // one-shot: none has no reset event to re-detect
                    startOnce.run();
                }
                return null; // unmodified -- a signal, not an instrumentation of behavior
            }
        };
        inst.addTransformer(transformer); // 1-arg form: observes new loads only, no retransform needed
        if (JulLoadDetector.isLoaded(inst.getAllLoadedClasses(), TARGET_CLASS_NAME)) {
            inst.removeTransformer(transformer);
            startOnce.run();
        }
    }

    private static void confirmBoundThenRun(Runnable onReady) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                LogbackAdapterFactory.forCurrentContext(); // probe only; discard the adapter it returns
                onReady.run();
                return;
            } catch (AdapterInstallException notYetBound) {
                sleep(POLL_INTERVAL_MS);
            }
        }
        Diagnostics.warn("ch.qos.logback.classic.LoggerContext class loaded but SLF4J never bound to it within "
                + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS) + "ms; giving up on install");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
