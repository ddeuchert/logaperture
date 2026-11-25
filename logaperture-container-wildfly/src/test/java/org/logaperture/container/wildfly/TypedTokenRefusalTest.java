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
package org.logaperture.container.wildfly;

import org.jboss.logmanager.handlers.ConsoleHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.adapter.jul.HandlerNameResolver;
import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.DefaultHandlerGroupRegistry;
import org.logaperture.core.HandlerBaselineRegistry;
import org.logaperture.core.HandlerLevelControlService;
import org.logaperture.core.HandlerOverrideRegistry;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.spi.StateStore;
import org.logaperture.core.spi.UnknownHandlerException;

import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #417: on JBoss LogManager, a handler the name resolver can't name keeps its identity
 * token, which changes on every restart, so a user can't set it by typing that token. Before the
 * fix the adapter refused only a token nothing had cached yet, so the same command succeeded on a
 * retry, or on the first try once any walk had run. The test JVM runs JBoss LogManager (see the
 * pom); the resolver names {@code CONSOLE} the way WildFly would and leaves the application's own
 * handler unnamed.
 */
class TypedTokenRefusalTest {

    private final Logger root = Logger.getLogger("");
    private Handler[] originalRootHandlers;

    @BeforeEach
    void detachRootHandlers() {
        originalRootHandlers = root.getHandlers();
        for (Handler handler : originalRootHandlers) {
            root.removeHandler(handler);
        }
    }

    @AfterEach
    void restoreRootHandlers() {
        for (Handler handler : originalRootHandlers) {
            root.addHandler(handler);
        }
    }

    @Test
    void aTypedTokenIsRefusedEveryTime_andAResolvedNameStillWorks() {
        ConsoleHandler console = new ConsoleHandler();
        ConsoleHandler appHandler = new ConsoleHandler(); // attached by the application, not in the model
        console.setLevel(java.util.logging.Level.INFO);
        appHandler.setLevel(java.util.logging.Level.INFO);
        HandlerNameResolver namesOnlyTheConsole = handlers -> handlers.contains(console)
                ? Map.of(console, "CONSOLE") : Map.of();
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(namesOnlyTheConsole);
        HandlerLevelControlService service = new HandlerLevelControlService(
                adapter, new HandlerBaselineRegistry(), new HandlerOverrideRegistry(),
                new DefaultHandlerGroupRegistry(), CapabilityPolicy.allowAll(), new InMemoryAuditLog(),
                StateStore.noOp(), "alice", "jmx");
        root.addHandler(console);
        root.addHandler(appHandler);
        try {
            HandlerRef token = HandlerRef.anonymous(appHandler);
            assertTrue(adapter.realHandlers().contains(token), "a real handler, still on its token");
            assertFalse(adapter.knownHandlers().contains(token), "not advertised to users");
            assertTrue(adapter.isUnstableRef(token));
            assertFalse(adapter.isUnstableRef(new HandlerRef("CONSOLE")), "a resolved name is stable");

            for (int attempt = 1; attempt <= 2; attempt++) {
                assertThrows(UnknownHandlerException.class,
                        () -> service.setHandlerLevel(token, Level.TRACE, SetHandlerLevelOptions.defaults()),
                        "attempt " + attempt);
            }
            assertEquals(java.util.logging.Level.INFO, appHandler.getLevel(), "the token's handler is unchanged");
            assertThrows(UnknownHandlerException.class, () -> service.setDefaultHandlerMembers(List.of(token)),
                    "nor can it be made a default handler, which persists");

            service.setHandlerLevel(new HandlerRef("CONSOLE"), Level.DEBUG, SetHandlerLevelOptions.defaults());
            assertEquals(java.util.logging.Level.FINE, console.getLevel(), "a resolved name still sets");
        } finally {
            root.removeHandler(console);
            root.removeHandler(appHandler);
        }
    }

    @Test
    void withNoNameResolver_aTokenIsTheHandlersOnlyName_andStaysAddressable() {
        ConsoleHandler handler = new ConsoleHandler();
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(HandlerNameResolver.NONE);
        root.addHandler(handler);
        try {
            assertFalse(adapter.isUnstableRef(HandlerRef.anonymous(handler)));
        } finally {
            root.removeHandler(handler);
        }
    }
}
