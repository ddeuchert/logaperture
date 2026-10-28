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
package org.logaperture.container.quarkus;

import io.quarkus.bootstrap.logging.QuarkusDelayedHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkusHandlerNameResolverTest {

    /** Stand-ins matched by simple class name, as TypeHandlerNameResolver matches the real ones. */
    static class ConsoleHandler extends Handler {
        @Override public void publish(LogRecord record) { }
        @Override public void flush() { }
        @Override public void close() { }
    }

    static class FileHandler extends ConsoleHandler { }

    private final Map<String, String> config = new HashMap<>();
    private final Map<String, Logger> loggers = new HashMap<>();
    private boolean ready = true;
    private boolean readable = true;
    private QuarkusDelayedHandler delayed;
    private ConsoleHandler defaultConsole;
    private FileHandler defaultFile;
    private QuarkusHandlerNameResolver resolver;

    @BeforeEach
    void setUp() {
        Logger root = Logger.getAnonymousLogger();
        delayed = new QuarkusDelayedHandler();
        defaultConsole = new ConsoleHandler();
        defaultFile = new FileHandler();
        delayed.addHandler(defaultConsole);
        delayed.addHandler(defaultFile);
        root.addHandler(delayed);
        loggers.put("", root);
        BooleanSupplier isReady = () -> ready;
        resolver = new QuarkusHandlerNameResolver(
                () -> readable ? Optional.of(config) : Optional.empty(), isReady, loggers::get);
    }

    private Logger category(String name, Handler... handlers) {
        Logger logger = Logger.getAnonymousLogger();
        for (Handler handler : handlers) {
            logger.addHandler(handler);
        }
        loggers.put(name, logger);
        return logger;
    }

    private List<Handler> all(Handler... more) {
        List<Handler> out = new ArrayList<>(List.of(defaultConsole, defaultFile));
        out.addAll(List.of(more));
        return out;
    }

    private void file(String name) {
        config.put("quarkus.log.handler.file.\"" + name + "\".enable", "true");
    }

    private void console(String name) {
        config.put("quarkus.log.handler.console.\"" + name + "\".format", "x");
    }

    @Test
    void categoryHandlersTakeTheirConfiguredNamesInListOrder() {
        FileHandler zeta = new FileHandler();
        FileHandler alpha = new FileHandler();
        ConsoleHandler side = new ConsoleHandler();
        file("ZETA");
        file("ALPHA");
        console("SIDE");
        config.put("quarkus.log.category.\"org.example\".handlers", "ZETA,ALPHA,SIDE");
        category("org.example", zeta, alpha, side);

        Map<Handler, String> names = resolver.resolve(all(zeta, alpha, side));

        assertEquals("ZETA", names.get(zeta));
        assertEquals("ALPHA", names.get(alpha));
        assertEquals("SIDE", names.get(side));
        assertEquals("console", names.get(defaultConsole));
        assertEquals("file", names.get(defaultFile));
    }

    @Test
    void aHandlerSharedByTwoCategoriesKeepsOneName() {
        FileHandler alpha = new FileHandler();
        file("ALPHA");
        config.put("quarkus.log.category.\"a\".handlers", "ALPHA");
        config.put("quarkus.log.category.\"b\".handlers", "ALPHA");
        category("a", alpha);
        category("b", alpha);

        assertEquals("ALPHA", resolver.resolve(all(alpha)).get(alpha));
    }

    @Test
    void rootHandlersAreTheLastChildrenOfTheDelayedHandler() {
        ConsoleHandler rootC = new ConsoleHandler();
        FileHandler rootF = new FileHandler();
        delayed.addHandler(rootC);
        delayed.addHandler(rootF);
        console("ROOTC");
        file("ROOTF");
        config.put("quarkus.log.handlers", "ROOTC,ROOTF");

        Map<Handler, String> names = resolver.resolve(all(rootC, rootF));

        assertEquals("ROOTC", names.get(rootC));
        assertEquals("ROOTF", names.get(rootF));
        assertEquals("console", names.get(defaultConsole));
        assertEquals("file", names.get(defaultFile));
    }

    @Test
    void aListWhoseTypesDoNotMatchIsSkippedAndKeepsTypeNames() {
        FileHandler wrong = new FileHandler();
        console("SIDE");
        config.put("quarkus.log.category.\"x\".handlers", "SIDE");
        category("x", wrong);

        Map<Handler, String> names = resolver.resolve(all(wrong));

        assertEquals("file-2", names.get(wrong));
    }

    @Test
    void aListWhoseLengthDoesNotMatchIsSkipped() {
        ConsoleHandler only = new ConsoleHandler();
        console("A");
        console("B");
        config.put("quarkus.log.category.\"x\".handlers", "A,B");
        category("x", only);

        assertEquals("console-2", resolver.resolve(all(only)).get(only));
    }

    @Test
    void aListedNameQuarkusDoesNotBuildIsLeftOutOfTheAlignment() {
        ConsoleHandler side = new ConsoleHandler();
        config.put("quarkus.log.handler.file.\"NOT_ENABLED\".path", "a.log"); // no enable=true: not built
        console("SIDE");
        config.put("quarkus.log.category.\"x\".handlers", "NOT_ENABLED,SIDE");
        category("x", side);

        assertEquals("SIDE", resolver.resolve(all(side)).get(side));
    }

    @Test
    void aConfiguredNameThatIsATypeNameWinsAndTheDefaultTakesTheNextNumber() {
        ConsoleHandler named = new ConsoleHandler();
        console("console");
        config.put("quarkus.log.category.\"x\".handlers", "console");
        category("x", named);

        Map<Handler, String> names = resolver.resolve(all(named));

        assertEquals("console", names.get(named));
        assertEquals("console-2", names.get(defaultConsole));
    }

    @Test
    void nothingIsNamedBeforeQuarkusHasAttachedItsHandlers() {
        ready = false;

        assertTrue(resolver.resolve(all()).isEmpty());
    }

    @Test
    void anUnreadableConfigurationFallsBackToTypeNamesAndIsReported() {
        readable = false;

        Map<Handler, String> names = resolver.resolve(all());

        assertEquals("console", names.get(defaultConsole));
        assertEquals("file", names.get(defaultFile));
        assertTrue(resolver.configUnreadable());
    }

    @Test
    void aReadableConfigurationIsNotReportedUnreadable() {
        resolver.resolve(all());

        assertFalse(resolver.configUnreadable());
    }

    @Test
    void aMissingCategoryLoggerIsSkipped() {
        file("ALPHA");
        config.put("quarkus.log.category.\"missing\".handlers", "ALPHA");

        Map<Handler, String> names = resolver.resolve(all());

        assertEquals("console", names.get(defaultConsole));
    }
}
