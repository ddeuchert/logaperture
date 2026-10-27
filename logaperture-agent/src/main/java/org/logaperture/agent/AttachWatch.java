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

import org.logaperture.bridge.Diagnostics;
import org.logaperture.startup.EngineStartup;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * After an attach into a plain JVM, says why LogAperture is not up yet (doc/specs/logctl-attach.md
 * #6). The {@code none} integration waits, without limit, for the application to start logging
 * through Logback or {@code java.util.logging}; with no {@code -javaagent} line and no start-up
 * banner, a silent wait looks the same as an agent that did nothing. This watches that wait and
 * writes at most two lines: one if it is still going after a second, and one at a minute naming
 * what was looked for. It changes nothing about the wait itself, and a {@code -javaagent} start
 * never runs it.
 */
final class AttachWatch {

    static final long QUIET_MILLIS = 1_000;
    static final long GIVE_UP_MILLIS = 60_000;
    private static final long POLL_MILLIS = 100;

    static final String WAITING = "LogAperture attached; waiting for the application to start logging through "
            + "Logback or java.util.logging.";
    static final String STILL_WAITING = "LogAperture is still waiting: this application has not logged through "
            + "Logback or java.util.logging, the only backends supported in a plain JVM. If it uses another, "
            + "such as Log4j 2, LogAperture cannot control it.";

    private AttachWatch() {
    }

    /** Starts the watch for the {@code none} integration; any other container has its own readiness. */
    static void start(String containerId) {
        if (!"none".equals(containerId)) {
            return;
        }
        BooleanSupplier active = () -> System.getProperty(EngineStartup.VERSION_PROPERTY) != null;
        Runnable watch = () -> run(active, System::currentTimeMillis, AttachWatch::sleep, Diagnostics::notice,
                Diagnostics::warn);
        Thread thread = new Thread(watch, "logaperture-attach-watch");
        thread.setDaemon(true);
        thread.start();
    }

    /** The watch with its clock and sinks passed in, for the test. Returns when it has said all it will say. */
    static void run(BooleanSupplier active, LongSupplier nowMillis, LongConsumer sleepMillis, Consumer<String> notice,
            Consumer<String> warn) {
        long start = nowMillis.getAsLong();
        boolean said = false;
        while (!active.getAsBoolean()) {
            long elapsed = nowMillis.getAsLong() - start;
            if (elapsed >= GIVE_UP_MILLIS) {
                warn.accept(STILL_WAITING);
                return;
            }
            if (!said && elapsed >= QUIET_MILLIS) {
                notice.accept(WAITING);
                said = true;
            }
            sleepMillis.accept(POLL_MILLIS);
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
