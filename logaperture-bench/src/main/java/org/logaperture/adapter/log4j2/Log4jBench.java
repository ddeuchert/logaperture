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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.async.AsyncLoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real Log4j 2 {@link LoggerContext} for the component benchmarks (doc/specs/overhead-benchmarks.md,
 * "Revision 2026-11-06: the Log4j 2 gate"): one logger, its level INFO, nothing else configured, so the
 * only thing the benchmarks add to a call is the gate under test. It lives in the adapter's package
 * because {@link GateFilter} is package-private.
 */
final class Log4jBench {

    /** The logger the 1.0 suite uses, so the gate sees the same name and the same parent. */
    static final String LOGGER = org.logaperture.core.IdleComponentsBenchmark.LOGGER;
    /** A parameterized message, as an application using {@code {}} writes it. */
    static final String PATTERN = "Processed order {} for customer {} in {} ms";

    private Log4jBench() {
    }

    /** A started context whose root level is INFO, named so two forks never share one. */
    static LoggerContext startedContext(String name) {
        LoggerContext context = new LoggerContext(name);
        context.start();
        Configuration configuration = context.getConfiguration();
        configuration.getRootLogger().setLevel(Level.INFO);
        context.updateLoggers();
        return context;
    }

    static Logger logger(LoggerContext context) {
        Logger logger = context.getLogger(LOGGER);
        if (!Level.INFO.equals(logger.getLevel())) {
            throw new IllegalStateException("expected INFO on " + LOGGER + ", got " + logger.getLevel());
        }
        return logger;
    }

    /** The three modes of the version matrix (Decision #26, #27). */
    static final String SYNC = "sync";
    static final String ASYNC_APPENDER = "async-appender";
    static final String ASYNC_LOGGERS = "async-loggers";

    /** Short and fixed, as the JUL suite's: a small baseline keeps its error from swamping the difference. */
    private static final String LAYOUT = "%d{HH:mm:ss,SSS} %-5level [%logger] %msg%n";

    /** Where the appender writes: nowhere, but it counts, so a scenario that logged nothing is caught. */
    static final class Sink extends OutputStream {
        final AtomicLong bytes = new AtomicLong();

        @Override
        public void write(int b) {
            bytes.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes.addAndGet(len);
        }
    }

    /** A context in {@code mode} with one text appender to {@code sink} on the root logger, at INFO. */
    static LoggerContext context(String name, String mode, Sink sink) {
        LoggerContext context = ASYNC_LOGGERS.equals(mode) ? new AsyncLoggerContext(name) : new LoggerContext(name);
        // start() (not start(Configuration)): an AsyncLoggerContext starts its Disruptor only there.
        context.start();
        Configuration configuration = context.getConfiguration();
        // The default configuration brings a console appender on the root: take it out, so the only output is the sink.
        for (String stale : new java.util.ArrayList<>(configuration.getRootLogger().getAppenders().keySet())) {
            configuration.getRootLogger().removeAppender(stale);
            configuration.getAppenders().remove(stale);
        }
        PatternLayout layout = PatternLayout.newBuilder().withPattern(LAYOUT).withConfiguration(configuration).build();
        OutputStreamAppender out = OutputStreamAppender.newBuilder().setName("Out").setLayout(layout)
                .setTarget(sink).setFollow(false).build();
        out.start();
        configuration.addAppender(out);
        org.apache.logging.log4j.core.Appender onRoot = out;
        if (ASYNC_APPENDER.equals(mode)) {
            AsyncAppender async = AsyncAppender.newBuilder().setName("Async").setConfiguration(configuration)
                    .setAppenderRefs(new AppenderRef[] {AppenderRef.createAppenderRef("Out", null, null)})
                    .setBlocking(true).setBufferSize(1 << 16).build();
            async.start();
            configuration.addAppender(async);
            onRoot = async;
        }
        configuration.getRootLogger().getAppenderRefs().clear();
        configuration.getRootLogger().addAppender(onRoot, null, null);
        // A slot is an AppenderRef (TrimSlots): a parsed configuration has one, an appender added in code does not.
        configuration.getRootLogger().getAppenderRefs().add(AppenderRef.createAppenderRef(onRoot.getName(), null, null));
        configuration.getRootLogger().setLevel(Level.INFO);
        context.updateLoggers();
        return context;
    }

    /**
     * Gives worker {@code index} its own appender (and, in the {@code async-appender} mode, its own queue) on its
     * own child logger, which does not add to the root: the {@code per-thread} handler shape of the JUL suite, the
     * shape where independent appenders must not slow each other down (#24). The slot is an {@code AppenderRef},
     * as for the root, so the trim wrapper finds it.
     */
    static Logger workerWithOwnAppender(LoggerContext context, String mode, String category, int index, Sink sink) {
        Configuration configuration = context.getConfiguration();
        String name = category + ".worker" + index;
        PatternLayout layout = PatternLayout.newBuilder().withPattern(LAYOUT).withConfiguration(configuration).build();
        OutputStreamAppender out = OutputStreamAppender.newBuilder().setName("Out" + index).setLayout(layout)
                .setTarget(sink).setFollow(false).build();
        out.start();
        configuration.addAppender(out);
        org.apache.logging.log4j.core.Appender onLogger = out;
        if (ASYNC_APPENDER.equals(mode)) {
            AsyncAppender async = AsyncAppender.newBuilder().setName("Async" + index).setConfiguration(configuration)
                    .setAppenderRefs(new AppenderRef[] {AppenderRef.createAppenderRef("Out" + index, null, null)})
                    .setBlocking(true).setBufferSize(1 << 16).build();
            async.start();
            configuration.addAppender(async);
            onLogger = async;
        }
        org.apache.logging.log4j.core.config.LoggerConfig loggerConfig =
                new org.apache.logging.log4j.core.config.LoggerConfig(name, Level.INFO, false);
        loggerConfig.addAppender(onLogger, null, null);
        loggerConfig.getAppenderRefs().add(AppenderRef.createAppenderRef(onLogger.getName(), null, null));
        configuration.addLogger(name, loggerConfig);
        context.updateLoggers();
        return context.getLogger(name);
    }

    /**
     * A throwable at least 20 frames deep whose frames are real, loadable classes. The JUL suite sets
     * synthetic frames ({@code org.acme.orders.OrderService$Stage0} ...), which is fine for a formatter that
     * only prints them; Log4j 2 resolves each frame's class when it builds a {@code ThrowableProxy}, and a
     * class that does not exist costs a failed lookup per frame, which a real application never pays.
     */
    static Throwable throwable() {
        return deeper(20);
    }

    private static Throwable deeper(int remaining) {
        if (remaining > 0) {
            return deeper(remaining - 1);
        }
        return new IllegalStateException("Connection refused: db-primary:5432");
    }
}
