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
package org.logaperture.sample;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sample's background logger. One daemon thread emits a
 * {@link WorkLog#emitAllLevels(String)} burst per period while running — so a
 * {@code logctl debug ... for 2m} override can be watched expiring and the
 * verification sweep re-applying it, without hitting an endpoint by hand.
 *
 * <p>Shared by {@link TimerServlet} (start / stop / status over HTTP) and
 * {@link SampleContextListener} (shutdown on undeploy) so the executor is
 * owned in exactly one place.
 */
enum SampleTimer {

    INSTANCE;

    static final long DEFAULT_PERIOD_MS = 3_000L;
    static final long MIN_PERIOD_MS = 500L;
    static final long MAX_PERIOD_MS = 60_000L;

    private final AtomicLong ticks = new AtomicLong();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;
    private long periodMs;

    /**
     * Start emitting bursts. If a timer is already running,
     * {@code restart == false} leaves it untouched; {@code restart == true}
     * cancels and replaces it with one at the new period.
     *
     * @return whether this call (re)started the timer
     */
    synchronized boolean start(long requestedPeriodMs, boolean restart) {
        if (task != null && !restart) {
            return false;
        }
        cancelTask();
        periodMs = Math.max(MIN_PERIOD_MS, Math.min(MAX_PERIOD_MS, requestedPeriodMs));
        if (executor == null) {
            ThreadFactory daemonThreads = runnable -> {
                Thread thread = new Thread(runnable, "logaperture-sample-timer");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newSingleThreadScheduledExecutor(daemonThreads);
        }
        task = executor.scheduleAtFixedRate(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        return true;
    }

    /** @return whether a timer was running (and has now been stopped) */
    synchronized boolean stop() {
        boolean wasRunning = task != null;
        cancelTask();
        return wasRunning;
    }

    /** Called on undeploy — kill the thread so a redeploy does not leak it. */
    synchronized void shutdown() {
        cancelTask();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    synchronized boolean running() {
        return task != null;
    }

    synchronized long periodMs() {
        return periodMs;
    }

    long tickCount() {
        return ticks.get();
    }

    private void tick() {
        WorkLog.emitAllLevels("timer tick #" + ticks.incrementAndGet());
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
