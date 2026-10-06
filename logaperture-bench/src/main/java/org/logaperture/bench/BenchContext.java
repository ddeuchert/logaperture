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
package org.logaperture.bench;

import org.jboss.logmanager.formatters.PatternFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The logger tree every scenario shares — doc/specs/overhead-benchmarks.md
 * "Harness". Each benchmark thread logs through its own child of one
 * category logger, {@value #CATEGORY}.
 */
final class BenchContext {

    static final String CATEGORY = "org.acme.orders";

    /** Short and fixed, per "Measurement method": a small baseline keeps its error from swamping the difference. */
    static final String PATTERN = "%d{HH:mm:ss,SSS} %-5p [%c] %s%e%n";

    /** Digits and a hex run, so the storm normalizer does the work a real message makes it do. */
    static final String MESSAGE = "Processed order 48213 for customer 7f3a9c21 in 12 ms";

    static final int THROWABLE_FRAMES = 20;

    private BenchContext() {
    }

    /** Fails the trial rather than measure the JDK's LogManager by accident (Decision #2). */
    static void requireJBossLogManager() {
        String actual = LogManager.getLogManager().getClass().getName();
        if (!actual.equals("org.jboss.logmanager.LogManager")) {
            throw new IllegalStateException("expected JBoss LogManager, got " + actual
                    + "; run with -Djava.util.logging.manager=org.jboss.logmanager.LogManager");
        }
    }

    /**
     * The logger tree for {@code threads} benchmark threads: one child logger
     * of {@value #CATEGORY} per thread, and either one handler on the category
     * that every child logs through ({@code perWorker} false), or one handler
     * on each child (the #24 shape: independent handlers JUL never
     * serialized). The returned loggers hold the tree alive for the trial.
     */
    static Tree attachHandlers(int threads, boolean perWorker) {
        Logger category = Logger.getLogger(CATEGORY);
        clearHandlers(category);
        category.setLevel(Level.INFO);
        category.setUseParentHandlers(false);
        List<Logger> workers = new ArrayList<>(threads);
        List<DiscardingFileHandler> handlers = new ArrayList<>();
        if (!perWorker) {
            handlers.add(newHandler());
            category.addHandler(handlers.get(0));
        }
        for (int i = 0; i < threads; i++) {
            Logger worker = Logger.getLogger(CATEGORY + ".worker" + i);
            clearHandlers(worker);
            if (perWorker) {
                DiscardingFileHandler handler = newHandler();
                worker.addHandler(handler);
                worker.setUseParentHandlers(false);
                handlers.add(handler);
            } else {
                worker.setUseParentHandlers(true);
            }
            workers.add(worker);
        }
        return new Tree(List.copyOf(workers), List.copyOf(handlers));
    }

    /** What {@link #attachHandlers} built: one logger per benchmark thread, and the handlers they write to. */
    record Tree(List<Logger> workers, List<DiscardingFileHandler> handlers) {

        /** Throws if any handler wrote nothing: the benchmark would have measured no logging. */
        void requireWritten() {
            for (DiscardingFileHandler handler : handlers) {
                if (handler.bytesWritten() == 0) {
                    throw new IllegalStateException("a handler wrote nothing; the benchmark measured no logging");
                }
            }
        }
    }

    private static DiscardingFileHandler newHandler() {
        DiscardingFileHandler handler = new DiscardingFileHandler(new PatternFormatter(PATTERN));
        handler.setLevel(Level.ALL);
        return handler;
    }

    private static void clearHandlers(Logger logger) {
        for (var existing : logger.getHandlers()) {
            logger.removeHandler(existing);
        }
    }

    /**
     * The message one call logs (Decision #12): the shared template, or a new
     * {@code String} with {@code sequence} in it. Built in every scenario,
     * baseline included, so building it subtracts out.
     */
    static String message(boolean concatenated, int sequence) {
        return concatenated
                ? "Processed order " + sequence + " for customer 7f3a9c21 in 12 ms"
                : MESSAGE;
    }

    /** A fixed-depth exception, so every scenario formats the same trace. */
    static Throwable throwable() {
        IllegalStateException e = new IllegalStateException("Connection refused: db-primary:5432");
        StackTraceElement[] frames = new StackTraceElement[THROWABLE_FRAMES];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("org.acme.orders.OrderService$Stage" + i, "process",
                    "OrderService.java", 100 + i);
        }
        e.setStackTrace(frames);
        return e;
    }
}
