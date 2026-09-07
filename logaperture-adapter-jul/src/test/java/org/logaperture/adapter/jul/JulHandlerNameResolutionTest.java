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
package org.logaperture.adapter.jul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.core.spi.UnknownHandlerException;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link HandlerNameResolver} wiring — doc/specs/handler-floor-control.md
 * "WildFly handler name resolution" (issue #14). Exercised against the JVM's
 * own {@code java.util.logging.LogManager} with a fake resolver; the real
 * WildFly management-model path is {@code logaperture-it}'s
 * {@code WildFlyContainerIT}. The WildFly-only pieces of the contract
 * (identity-token reals stay out of {@code knownHandlers()}, the
 * blocking-handler warning collapses only while a blocker is a token) need a
 * JBoss {@code LogManager} and live there too.
 *
 * <p>Each test isolates its handler on a dedicated child logger with {@code
 * setUseParentHandlers(false)} and asserts through {@code
 * handlerFloorsBelow(thatLogger, …)}, so the JVM's ambient root {@code
 * ConsoleHandler} never confuses the assertions.
 */
class JulHandlerNameResolutionTest {

    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "it14." + UUID.randomUUID().toString().replace("-", "") + ".";
    }

    @AfterEach
    void tearDown() {
        // each test removes its own handler in a finally block
    }

    private String name(String suffix) {
        return prefix + suffix;
    }

    /** A logger with exactly one handler on its resolved path, at INFO, isolated from root. */
    private Logger isolatedLoggerWith(Handler handler) {
        Logger logger = Logger.getLogger(name("L" + System.nanoTime()));
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return logger;
    }

    private static ConsoleHandler consoleAtInfo() {
        ConsoleHandler h = new ConsoleHandler();
        h.setLevel(java.util.logging.Level.INFO);
        return h;
    }

    /** The single blocking handler's ref on {@code logger}'s own path, for a TRACE target. */
    private HandlerRef soleFloorRef(JulLoggingAdapter adapter, Logger logger) {
        return adapter.handlerFloorsBelow(logger.getName(), Level.TRACE).stream()
                .map(f -> f.handlerRef())
                .filter(r -> !HandlerRef.ALL_HANDLERS.equals(r))
                .findFirst().orElseThrow();
    }

    @Test
    void resolver_isNotConsultedUntilAnAddressableSurfaceMethodIsCalled() {
        FakeResolver resolver = new FakeResolver();
        JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);
        assertEquals(0, resolver.calls.get(), "constructing the adapter must not touch the resolver");

        adapter.realHandlers();
        assertTrue(resolver.calls.get() >= 1, "realHandlers() should have triggered a resolution attempt");
    }

    @Test
    void resolvedName_becomesTheHandlerRef_andIsAddressable() {
        ConsoleHandler console = consoleAtInfo();
        Logger logger = isolatedLoggerWith(console);
        try {
            FakeResolver resolver = new FakeResolver();
            resolver.names.put(console, "MY-FILE");
            JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);

            assertEquals(new HandlerRef("MY-FILE"), soleFloorRef(adapter, logger),
                    "the configured name should be the handler's ref");
            assertEquals(Level.INFO,
                    adapter.setHandlerLevel(new HandlerRef("MY-FILE"), Level.DEBUG).orElseThrow(),
                    "a friendly ref must resolve cold, straight to setHandlerLevel");
        } finally {
            logger.removeHandler(console);
        }
    }

    @Test
    void anUnresolvedHandler_keepsItsIdentityToken() {
        ConsoleHandler anonymous = consoleAtInfo();
        Logger logger = isolatedLoggerWith(anonymous);
        try {
            FakeResolver resolver = new FakeResolver(); // knows nothing about this handler
            JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);

            assertTrue(soleFloorRef(adapter, logger).value().startsWith("ConsoleHandler@"),
                    "a handler the resolver doesn't name keeps its identity token");
        } finally {
            logger.removeHandler(anonymous);
        }
    }

    @Test
    void resolution_retriesWhileTheResolverReturnsEmpty_thenSucceeds() {
        ConsoleHandler console = consoleAtInfo();
        Logger logger = isolatedLoggerWith(console);
        try {
            FakeResolver resolver = new FakeResolver();
            resolver.emptyFirst = 2; // first two attempts come back empty
            resolver.names.put(console, "LATE-FILE");
            JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);

            assertTrue(soleFloorRef(adapter, logger).value().startsWith("ConsoleHandler@"), "attempt 1: empty");
            assertTrue(soleFloorRef(adapter, logger).value().startsWith("ConsoleHandler@"), "attempt 2: empty");
            assertEquals(new HandlerRef("LATE-FILE"), soleFloorRef(adapter, logger), "attempt 3: resolved");
        } finally {
            logger.removeHandler(console);
        }
    }

    @Test
    void resolution_givesUpAfterMaxAttempts_andStopsCallingTheResolver() {
        ConsoleHandler console = consoleAtInfo();
        Logger logger = isolatedLoggerWith(console);
        try {
            FakeResolver resolver = new FakeResolver(); // never returns a name
            JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);

            for (int i = 0; i < 40; i++) {
                adapter.realHandlers();
            }
            assertEquals(20, resolver.calls.get(),
                    "after MAX_RESOLVE_ATTEMPTS empty passes the adapter must stop consulting the resolver");
        } finally {
            logger.removeHandler(console);
        }
    }

    @Test
    void aLateResolution_upgradesAnAlreadyMintedTokenRef() {
        ConsoleHandler console = consoleAtInfo();
        Logger logger = isolatedLoggerWith(console);
        try {
            FakeResolver resolver = new FakeResolver();
            resolver.emptyFirst = 1; // token minted before the name is known
            resolver.names.put(console, "FILE");
            JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);

            HandlerRef token = soleFloorRef(adapter, logger);
            assertTrue(token.value().startsWith("ConsoleHandler@"));

            assertEquals(new HandlerRef("FILE"), soleFloorRef(adapter, logger), "the ref upgrades to the name");
            assertThrows(UnknownHandlerException.class, () -> adapter.setHandlerLevel(token, Level.DEBUG),
                    "the pre-upgrade token must no longer resolve");
            assertEquals(Level.INFO, adapter.setHandlerLevel(new HandlerRef("FILE"), Level.DEBUG).orElseThrow());
        } finally {
            logger.removeHandler(console);
        }
    }

    @Test
    void invalidateNameCache_rearmsResolutionForANewlyAttachedHandler() {
        ConsoleHandler first = consoleAtInfo();
        ConsoleHandler second = consoleAtInfo();
        Logger logger = isolatedLoggerWith(first);
        try {
            FakeResolver resolver = new FakeResolver();
            resolver.names.put(first, "FIRST");
            JulLoggingAdapter adapter = new JulLoggingAdapter(resolver);
            assertTrue(adapter.realHandlers().contains(new HandlerRef("FIRST")));

            logger.addHandler(second);
            resolver.names.put(second, "SECOND");
            assertFalse(adapter.realHandlers().contains(new HandlerRef("SECOND")),
                    "resolution already succeeded once -- the new handler stays a token until re-armed");

            adapter.invalidateNameCache();
            assertTrue(adapter.realHandlers().contains(new HandlerRef("SECOND")),
                    "after invalidateNameCache() the new handler resolves");
        } finally {
            logger.removeHandler(first);
            logger.removeHandler(second);
        }
    }

    @Test
    void noneResolver_behavesExactlyLikeTheNoArgAdapter() {
        ConsoleHandler console = consoleAtInfo();
        Logger logger = isolatedLoggerWith(console);
        try {
            HandlerRef ref = soleFloorRef(new JulLoggingAdapter(HandlerNameResolver.NONE), logger);
            assertTrue(ref.value().startsWith("ConsoleHandler@"),
                    "NONE resolver -> identity tokens, same as before this feature");
        } finally {
            logger.removeHandler(console);
        }
    }

    /** A resolver that answers from a fixed identity map, countable, optionally empty for its first N calls. */
    private static final class FakeResolver implements HandlerNameResolver {
        final AtomicInteger calls = new AtomicInteger();
        final Map<Handler, String> names = new IdentityHashMap<>();
        volatile int emptyFirst = 0;

        @Override
        public Map<Handler, String> resolve(List<Handler> handlers) {
            int call = calls.incrementAndGet();
            if (call <= emptyFirst) {
                return Map.of();
            }
            Map<Handler, String> out = new IdentityHashMap<>();
            for (Handler h : handlers) {
                String n = names.get(h);
                if (n != null) {
                    out.put(h, n);
                }
            }
            return out;
        }
    }
}
