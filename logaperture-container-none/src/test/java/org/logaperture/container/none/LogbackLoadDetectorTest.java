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

import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/logctl-attach.md #7: the readiness gate also finds a {@code LoggerContext} that is already loaded. */
class LogbackLoadDetectorTest {

    private static final String LOGGER_CONTEXT = "ch/qos/logback/classic/LoggerContext";

    /** An {@link Instrumentation} that records its transformers and reports a fixed set of loaded classes. */
    private static final class FakeInstrumentation {
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        final Instrumentation proxy;

        FakeInstrumentation(Class<?>... loaded) {
            this.proxy = (Instrumentation) Proxy.newProxyInstance(
                    Instrumentation.class.getClassLoader(), new Class<?>[] {Instrumentation.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "addTransformer" -> {
                            transformers.add((ClassFileTransformer) args[0]);
                            yield null;
                        }
                        case "removeTransformer" -> transformers.remove((ClassFileTransformer) args[0]);
                        case "getAllLoadedClasses" -> loaded;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        void load(String internalName) throws Exception {
            for (ClassFileTransformer t : List.copyOf(transformers)) {
                t.transform(null, internalName, null, null, new byte[0]);
            }
        }
    }

    @Test
    void startsDetectionAtOnceWhenTheClassIsAlreadyLoaded() throws Exception {
        // Not Logback itself: any loaded class whose name matches is enough, and the test classpath
        // has Logback, so use the real one.
        Class<?> loggerContext = Class.forName("ch.qos.logback.classic.LoggerContext");
        FakeInstrumentation inst = new FakeInstrumentation(String.class, loggerContext);
        CountDownLatch started = new CountDownLatch(1);

        LogbackLoadDetector.watch(inst.proxy, started::countDown);

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(inst.transformers.isEmpty(), "the observer is removed once the class is found");
    }

    @Test
    void waitsForTheLoadWhenTheClassIsNotLoadedYet() throws Exception {
        FakeInstrumentation inst = new FakeInstrumentation(String.class);
        CountDownLatch started = new CountDownLatch(1);

        LogbackLoadDetector.watch(inst.proxy, started::countDown);

        assertFalse(started.await(200, TimeUnit.MILLISECONDS));
        assertEquals(1, inst.transformers.size());
        inst.load("java/lang/Object");
        assertFalse(started.await(200, TimeUnit.MILLISECONDS), "an unrelated load does not start it");

        inst.load(LOGGER_CONTEXT);

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(inst.transformers.isEmpty());
    }

    @Test
    void startsOnlyOnceWhenTheLoadAndTheScanBothSeeTheClass() throws Exception {
        Class<?> loggerContext = Class.forName("ch.qos.logback.classic.LoggerContext");
        FakeInstrumentation inst = new FakeInstrumentation(loggerContext);
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Runnable count = () -> {
            starts.incrementAndGet();
            started.countDown();
        };

        LogbackLoadDetector.watch(inst.proxy, count);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        inst.load(LOGGER_CONTEXT); // a late event after the scan already started it

        Thread.sleep(200);
        assertEquals(1, starts.get());
    }
}
