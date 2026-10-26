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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JulLoadDetectorTest {

    @Test
    void runsAtOnceWhenJulIsAlreadyInitialised() {
        AtomicBoolean ran = new AtomicBoolean();
        JulLoadDetector.await(() -> true, 10, 1_000, 10, () -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void waitsUntilTheSignalHolds() {
        AtomicInteger checks = new AtomicInteger();
        BooleanSupplier onThirdCheck = () -> checks.incrementAndGet() >= 3;
        AtomicBoolean ran = new AtomicBoolean();
        JulLoadDetector.await(onThirdCheck, 1, 1_000, 1, () -> ran.set(true));
        assertTrue(ran.get());
        assertEquals(3, checks.get());
    }

    @Test
    void keepsCheckingAfterTheFastWindow() {
        AtomicInteger checks = new AtomicInteger();
        BooleanSupplier late = () -> checks.incrementAndGet() >= 5;
        AtomicBoolean ran = new AtomicBoolean();
        JulLoadDetector.await(late, 1, 0, 1, () -> ran.set(true)); // no fast window at all: never gives up
        assertTrue(ran.get());
    }

    @Test
    void anInterruptedWaitDoesNotInstall() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean();
        Runnable neverReady = () -> JulLoadDetector.await(() -> false, 5, 1_000, 5, () -> ran.set(true));
        Thread waiter = new Thread(neverReady);
        waiter.start();
        waiter.interrupt();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertFalse(ran.get());
    }

    @Test
    void findsTheRootLoggerByName() throws ClassNotFoundException {
        Class<?> rootLogger = Class.forName(JulLoadDetector.ROOT_LOGGER_CLASS, false, null);
        assertTrue(JulLoadDetector.isLoaded(new Class<?>[] {String.class, rootLogger}, JulLoadDetector.ROOT_LOGGER_CLASS));
        assertFalse(JulLoadDetector.isLoaded(new Class<?>[] {String.class}, JulLoadDetector.ROOT_LOGGER_CLASS));
    }
}
