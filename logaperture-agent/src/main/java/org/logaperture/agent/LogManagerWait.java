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
import org.logaperture.container.none.JulLoadDetector;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code --wait-for-log-manager=on} (doc/specs/wait-for-log-manager.md): holds back publishing the control surface,
 * which initialises {@code java.util.logging}, until something else has initialised it. By then the {@code
 * LogManager} is chosen, so the agent cannot take that choice from the application. It never gives up and never
 * publishes early; after a minute it says once that it is still waiting.
 *
 * <p>While it waits, {@link #WAITING_PROPERTY} is set, so that {@code logctl} leaves the JVM alone: opening a
 * management connection would itself initialise {@code java.util.logging}.
 *
 * <p>Each publishing step handed to {@link #accept} runs at once if there is no wait or it is over, and when it ends
 * otherwise.
 */
final class LogManagerWait implements Consumer<Runnable> {

    /** Read by {@code logctl} before it connects. */
    static final String WAITING_PROPERTY = "logaperture.waiting";

    /** The one integration the argument applies to (#10): WildFly and Quarkus choose their own manager. */
    private static final String PLAIN_JVM = "none";

    private static final long SAY_WAITING_AFTER_MS = 60_000;

    private final List<Runnable> held = new ArrayList<>();
    private boolean over;

    LogManagerWait() {
    }

    /**
     * The wait for this start: one that is on, with the JVM marked, if the argument was given to a {@code -javaagent}
     * start on a plain JVM, and one already over otherwise. Called on the {@code premain} thread, so the property is
     * set before the application's {@code main}. {@link #watch} starts the looking.
     *
     * @param attached whether the agent was loaded into a running JVM, where the application may have used JUL or not
     *                 long ago and the argument is not offered
     */
    static LogManagerWait create(boolean asked, String containerId, boolean attached) {
        LogManagerWait wait = new LogManagerWait();
        if (asked && attached) {
            Diagnostics.warn("ignoring --wait-for-log-manager: it is for a -javaagent start, not an attach");
        } else if (asked && !PLAIN_JVM.equals(containerId)) {
            Diagnostics.warn("ignoring --wait-for-log-manager: it applies to a plain JVM only, not " + containerId);
        } else if (asked) {
            wait.begin();
            return wait;
        }
        wait.over = true;
        return wait;
    }

    /**
     * Looks for JUL being initialised, if the wait is on. Called after the container integration has started its own
     * wait for it, so that what it installs then is there before the surface is published.
     */
    void watch(Instrumentation inst) {
        if (isOver()) {
            return;
        }
        Runnable end = this::end;
        JulLoadDetector.awaitJulThen(inst, end);
        remindAfter(SAY_WAITING_AFTER_MS);
    }

    /** Marks the JVM as waiting. Package-visible for the test. */
    void begin() {
        System.setProperty(WAITING_PROPERTY, "java.util.logging");
    }

    @Override
    public void accept(Runnable publish) {
        synchronized (this) {
            if (!over) {
                held.add(publish);
                return;
            }
        }
        publish.run();
    }

    /**
     * Ends the wait: runs what was held, outside the lock, then clears the property. In that order, so that {@code
     * logctl} never sees a JVM that is neither waiting nor published. Package-visible for the test.
     */
    void end() {
        List<Runnable> due;
        synchronized (this) {
            over = true;
            due = List.copyOf(held);
            held.clear();
        }
        try {
            due.forEach(Runnable::run);
        } finally {
            System.clearProperty(WAITING_PROPERTY);
        }
    }

    synchronized boolean isOver() {
        return over;
    }

    /** One line if the wait is still on after {@code millis} (#6). Package-visible for the test. */
    Thread remindAfter(long millis) {
        Runnable sayStillWaiting = () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!isOver()) {
                Diagnostics.warn("still waiting for java.util.logging to be initialised (--wait-for-log-manager);"
                        + " logctl cannot see this JVM until then");
            }
        };
        Thread reminder = new Thread(sayStillWaiting, "logaperture-log-manager-wait");
        reminder.setDaemon(true);
        reminder.start();
        return reminder;
    }
}
