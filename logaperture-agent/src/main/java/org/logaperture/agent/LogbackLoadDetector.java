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
package org.logaperture.agent;

import org.logaperture.adapter.logback.AdapterInstallException;
import org.logaperture.adapter.logback.LogbackAdapterFactory;
import org.logaperture.bridge.Diagnostics;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

/**
 * Detects "Logback has loaded" via a one-shot {@link ClassFileTransformer}
 * registered at {@code premain} time — the reliable, class-load-time
 * pattern per the updated doc/logaperture-spec.md §4.1, not the M0 spike's
 * {@code Instrumentation.getAllLoadedClasses()} polling shortcut (spike
 * plumbing, never intended as a production pattern).
 *
 * <p>The transformer only <em>observes</em> the class-load event — it
 * returns {@code null} (unmodified bytecode) unconditionally, so it needs
 * no retransform capability (see this module's pom: {@code
 * Can-Retransform-Classes: false}).
 *
 * <p>Deliberately holds no compile-time reference to any {@code
 * ch.qos.logback} type (see this module's pom: Logback is test-scope
 * only, for the IT fixture app, never on the agent's own runtime
 * classpath). Readiness is confirmed by repeatedly attempting {@link
 * LogbackAdapterFactory#forCurrentContext()} as a probe — succeeding means
 * SLF4J is genuinely bound, not just that the class loaded (see the M0
 * Spring Boot spike's {@code SubstituteLoggerFactory} finding, doc/spikes/
 * m0-adapter-grid.md point 3 — the same distinction matters here even
 * though {@code none} never actually races against a framework reset).
 */
final class LogbackLoadDetector {

    private static final String TARGET_CLASS_INTERNAL_NAME = "ch/qos/logback/classic/LoggerContext";
    private static final long POLL_INTERVAL_MS = 50;
    private static final int MAX_POLL_ATTEMPTS = 40; // ~2s total

    private LogbackLoadDetector() {
    }

    static void awaitLogbackAndThen(Instrumentation inst, Runnable onReady) {
        ClassFileTransformer[] holder = new ClassFileTransformer[1];
        holder[0] = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    java.security.ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                if (TARGET_CLASS_INTERNAL_NAME.equals(className)) {
                    inst.removeTransformer(holder[0]); // one-shot: none has no reset event to re-detect
                    Thread detectorThread = new Thread(() -> confirmBoundThenRun(onReady), "logaperture-detector");
                    detectorThread.setDaemon(true);
                    detectorThread.start();
                }
                return null; // unmodified -- a signal, not an instrumentation of behavior
            }
        };
        inst.addTransformer(holder[0]); // 1-arg form: observes new loads only, no retransform needed
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
