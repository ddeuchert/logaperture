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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level safety checks for {@link WildFlyHandlerNameResolver}. The real
 * management-model path needs a running WildFly and lives in
 * {@code logaperture-it}'s {@code WildFlyContainerIT}
 * (verified against WildFly 26.1.3.Final); here we only pin the
 * degrade-gracefully contract, which is what the JUL adapter relies on.
 */
class WildFlyHandlerNameResolverTest {

    private final WildFlyHandlerNameResolver resolver = new WildFlyHandlerNameResolver();

    @Test
    void offAWildFlyServer_resolvesNothingAndNeverThrows() {
        // This JVM has no org.jboss.modules on the system class path, so the
        // resolver can't reach a ServiceContainer -- it must return an empty
        // map, not blow up.
        List<Handler> handlers = List.of(new ConsoleHandler(), new ConsoleHandler());
        Map<Handler, String> resolved = resolver.resolve(handlers);
        assertTrue(resolved.isEmpty(), "no WildFly here -> nothing resolves");
    }

    @Test
    void beforeModulePathIsSet_neverAsksForTheBootModuleLoader() {
        // Issue #87: asking JBoss Modules for its boot module loader before Main has set
        // module.path (from -mp) creates it with no module roots, and Main then aborts with
        // ModuleNotFoundException: org.jboss.as.standalone.
        AtomicInteger lookups = new AtomicInteger();
        Supplier<Object> countingLookup = () -> {
            lookups.incrementAndGet();
            return null;
        };
        WildFlyHandlerNameResolver guarded = new WildFlyHandlerNameResolver(countingLookup);

        withModulePath(null, () -> assertEquals(Map.of(), guarded.resolve(List.of(new ConsoleHandler()))));
        assertEquals(0, lookups.get(), "no boot module loader lookup while module.path is unset");

        withModulePath("/opt/wildfly/modules", () -> guarded.resolve(List.of(new ConsoleHandler())));
        assertEquals(1, lookups.get(), "once module.path is set, resolution proceeds to the lookup");
    }

    @Test
    void emptyInput_resolvesToEmpty() {
        assertEquals(Map.of(), resolver.resolve(List.of()));
    }

    @Test
    void bind_soleConsoleAndSoleFile_bindOneToOne() {
        Handler console = new ConsoleHandler();
        Handler file = new FakeFileHandler("/var/log/server.log");
        Map<String, String> model = new LinkedHashMap<>();
        model.put("CONSOLE", "console-handler");
        model.put("FILE", "periodic-rotating-file-handler");

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(
                List.of(console, file), model, Map.of("FILE", "server.log"));

        assertEquals("CONSOLE", bound.get(console));
        assertEquals("FILE", bound.get(file));
    }

    @Test
    void bind_modelHasMoreFileHandlersThanAreLive_doesNotMisnameTheLiveOne() {
        // Issue #14 review: `file-handler=AUDIT` is declared but not attached
        // to any logger; only the periodic-rotating `FILE` handler is live.
        // The 1<->1 shortcut must NOT fire (2 model file names, 1 instance) --
        // path matching binds FILE, AUDIT resolves to nothing.
        Handler file = new FakeFileHandler("/var/log/server.log");
        Map<String, String> model = new LinkedHashMap<>();
        model.put("AUDIT", "file-handler");                       // first in iteration order
        model.put("FILE", "periodic-rotating-file-handler");
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("AUDIT", "audit.log");
        paths.put("FILE", "server.log");

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(file), model, paths);

        assertEquals("FILE", bound.get(file), "the live handler must not be mislabelled AUDIT");
        assertEquals(1, bound.size());
    }

    @Test
    void bind_noPathToMatchOn_leavesTheFileHandlerUnresolved() {
        Handler file = new FakeFileHandler("/var/log/server.log");
        Map<String, String> model = new LinkedHashMap<>();
        model.put("AUDIT", "file-handler");
        model.put("FILE", "periodic-rotating-file-handler");

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(file), model, Map.of());

        assertNull(bound.get(file), "no configured path -> keep the token, don't guess");
        assertTrue(bound.isEmpty());
    }

    // --- binding by attachment (issue #188) -------------------------------------------------------

    @Test
    void bind_severalConsoles_bindByTheLoggersTheyAreAttachedTo() {
        // The reported layout: CONSOLE on root, and a console of its own on each of two
        // application loggers. No file to match on, and more than one console, so only
        // attachment can tell them apart.
        Handler console = new ConsoleHandler();
        Handler apiConsole = new ConsoleHandler();
        Handler agentConsole = new ConsoleHandler();
        Map<String, String> model = new LinkedHashMap<>();
        model.put("CONSOLE", "console-handler");
        model.put("CONSOLE-API", "console-handler");
        model.put("CONSOLE-AGENT", "console-handler");
        Map<String, Set<String>> attachments = Map.of(
                "CONSOLE", Set.of(""),
                "CONSOLE-API", Set.of("app.api"),
                "CONSOLE-AGENT", Set.of("app.agent"));
        Function<String, List<Handler>> live = attached(Map.of(
                "", List.of(console),
                "app.api", List.of(apiConsole),
                "app.agent", List.of(agentConsole)));

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(
                List.of(console, apiConsole, agentConsole), model, Map.of(), attachments, live);

        assertEquals("CONSOLE", bound.get(console));
        assertEquals("CONSOLE-API", bound.get(apiConsole));
        assertEquals("CONSOLE-AGENT", bound.get(agentConsole));
    }

    @Test
    void bind_twoConsoleNamesOnTheSameLoggers_bindsNeither() {
        Handler first = new ConsoleHandler();
        Handler second = new ConsoleHandler();
        Map<String, String> model = new LinkedHashMap<>();
        model.put("CONSOLE", "console-handler");
        model.put("CONSOLE-2", "console-handler");
        Map<String, Set<String>> attachments = Map.of("CONSOLE", Set.of(""), "CONSOLE-2", Set.of(""));

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(first, second), model, Map.of(),
                attachments, attached(Map.of("", List.of(first, second))));

        assertTrue(bound.isEmpty(), "same attachments -> ambiguous -> keep the tokens");
    }

    @Test
    void bind_liveAttachmentsDifferFromTheModel_leavesTheConsoleUnresolved() {
        Handler console = new ConsoleHandler();
        Handler other = new ConsoleHandler();
        Map<String, String> model = new LinkedHashMap<>();
        model.put("CONSOLE", "console-handler");
        model.put("CONSOLE-API", "console-handler");
        Map<String, Set<String>> attachments = Map.of("CONSOLE", Set.of(""), "CONSOLE-API", Set.of("app.api"));
        // Both live consoles are on root: neither is attached only to app.api.
        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(console, other), model, Map.of(),
                attachments, attached(Map.of("", List.of(console, other))));

        assertNull(bound.get(console));
        assertNull(bound.get(other));
    }

    @Test
    void bind_aFileHandlerWithNoPathToMatch_bindsByAttachment() {
        Handler serverFile = new FakeFileHandler("/var/log/server.log");
        Handler apiFile = new FakeFileHandler("/var/log/api.log");
        Map<String, String> model = new LinkedHashMap<>();
        model.put("FILE", "periodic-rotating-file-handler");
        model.put("API", "periodic-rotating-file-handler");
        Map<String, Set<String>> attachments = Map.of("FILE", Set.of(""), "API", Set.of("app.api"));

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(serverFile, apiFile), model,
                Map.of(), attachments, attached(Map.of("", List.of(serverFile), "app.api", List.of(apiFile))));

        assertEquals("FILE", bound.get(serverFile));
        assertEquals("API", bound.get(apiFile));
    }

    @Test
    void bind_aNameBoundByItsFile_doesNotBlockAnotherOnTheSameLoggers() {
        // FILE is bound by its path; API, a custom handler with no path, is the only other
        // handler on root, so it is unambiguous.
        Handler serverFile = new FakeFileHandler("/var/log/server.log");
        Handler api = new PlainHandler();
        Map<String, String> model = new LinkedHashMap<>();
        model.put("FILE", "periodic-rotating-file-handler");
        model.put("API", "custom-handler");
        Map<String, Set<String>> attachments = Map.of("FILE", Set.of(""), "API", Set.of(""));

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(serverFile, api), model,
                Map.of("FILE", "server.log"), attachments, attached(Map.of("", List.of(serverFile, api))));

        assertEquals("FILE", bound.get(serverFile));
        assertEquals("API", bound.get(api));
    }

    @Test
    void bind_anAsyncHandlerOnTheSameLoggers_keepsAFileNameUnbound() {
        // ASYNC (an async-handler, not a type this resolver names) and FILE are both on root, and
        // only the async handler's instance is live: it must not be labelled FILE.
        Handler async = new PlainHandler();
        Map<String, String> model = new LinkedHashMap<>();
        model.put("FILE", "custom-handler");
        model.put("OTHER", "custom-handler"); // so the one-file shortcut doesn't apply
        Map<String, Set<String>> attachments = Map.of(
                "FILE", Set.of(""), "ASYNC", Set.of(""), "OTHER", Set.of("app.other"));

        Map<Handler, String> bound = WildFlyHandlerNameResolver.bind(List.of(async), model, Map.of(),
                attachments, attached(Map.of("", List.of(async))));

        assertTrue(bound.isEmpty(), "ASYNC competes for root, so FILE stays unbound: " + bound);
    }

    private static Function<String, List<Handler>> attached(Map<String, List<Handler>> byCategory) {
        return category -> byCategory.getOrDefault(category, List.of());
    }

    private static void withModulePath(String value, Runnable body) {
        String saved = System.getProperty(WildFlyHandlerNameResolver.MODULE_PATH_PROPERTY);
        try {
            if (value == null) {
                System.clearProperty(WildFlyHandlerNameResolver.MODULE_PATH_PROPERTY);
            } else {
                System.setProperty(WildFlyHandlerNameResolver.MODULE_PATH_PROPERTY, value);
            }
            body.run();
        } finally {
            if (saved == null) {
                System.clearProperty(WildFlyHandlerNameResolver.MODULE_PATH_PROPERTY);
            } else {
                System.setProperty(WildFlyHandlerNameResolver.MODULE_PATH_PROPERTY, saved);
            }
        }
    }

    /** A non-console handler with no file. */
    private static final class PlainHandler extends Handler {
        @Override public void publish(LogRecord record) { }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static final class FakeFileHandler extends Handler {
        private final File file;

        FakeFileHandler(String path) {
            this.file = new File(path);
        }

        @SuppressWarnings("unused") // reflected by WildFlyHandlerNameResolver.fileNameOf
        public File getFile() {
            return file;
        }

        @Override public void publish(LogRecord record) { }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
