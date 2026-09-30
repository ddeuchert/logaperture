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

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The logger tree every scenario shares — doc/specs/overhead-benchmarks.md
 * "Harness". One category logger, {@value #CATEGORY}, carries the one
 * handler; each benchmark thread logs through its own child of it.
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

    static DiscardingFileHandler attachHandler() {
        Logger category = Logger.getLogger(CATEGORY);
        for (var existing : category.getHandlers()) {
            category.removeHandler(existing);
        }
        category.setLevel(Level.INFO);
        category.setUseParentHandlers(false);
        DiscardingFileHandler handler = new DiscardingFileHandler(new PatternFormatter(PATTERN));
        handler.setLevel(Level.ALL);
        category.addHandler(handler);
        return handler;
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
