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
import org.jboss.logmanager.ExtFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;
import org.logaperture.container.quarkus.QuarkusHandlerNameResolver.ListProblem;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Formatter;
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

    /** A file handler that tells its path, as JBoss LogManager's does. */
    public static class PathFileHandler extends FileHandler {
        private final File file;

        PathFileHandler(String path) {
            this.file = new File(path);
        }

        public File getFile() {
            return file;
        }
    }

    /** A pattern formatter as JBoss LogManager's: it tells its pattern. */
    public static class Pattern extends Formatter {
        private final String pattern;

        Pattern(String pattern) {
            this.pattern = pattern;
        }

        public String getPattern() {
            return pattern;
        }

        @Override
        public String format(LogRecord record) {
            return record.getMessage();
        }
    }

    /** Quarkus's banner formatter around a console's pattern. */
    static class Banner extends ExtFormatter.Delegating {
        Banner(Formatter delegate) {
            super(delegate);
        }
    }

    private static ConsoleHandler consoleWithPattern(Formatter formatter) {
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(formatter);
        return handler;
    }

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

    private void file(String name, String path) {
        file(name);
        config.put("quarkus.log.handler.file.\"" + name + "\".path", path);
    }

    private List<ListProblem.Kind> kinds() {
        return resolver.problems().stream().map(ListProblem::kind).toList();
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
    void aListWithMoreNamesThanHandlersIsSkippedAndReported() {
        ConsoleHandler only = new ConsoleHandler();
        console("A");
        console("B");
        config.put("quarkus.log.category.\"x\".handlers", "A,B");
        category("x", only);

        assertEquals("console-2", resolver.resolve(all(only)).get(only));
        assertEquals(List.of(ListProblem.Kind.SKIPPED), kinds());
        assertEquals("quarkus.log.category.\"x\".handlers", resolver.problems().get(0).list());
    }

    // --- #294: lists next to other handlers ----------------------------------------------------------

    @Test
    void aRootHandlerAddedAfterStartUpLeavesTheNamesWhereTheyWere() {
        PathFileHandler a = new PathFileHandler("/logs/a.log");
        PathFileHandler b = new PathFileHandler("/logs/b.log");
        delayed.addHandler(a);
        delayed.addHandler(b);
        file("ROOTA");
        file("ROOTB");
        config.put("quarkus.log.handlers", "ROOTA,ROOTB");
        resolver.resolve(all(a, b));

        FileHandler later = new FileHandler();
        delayed.addHandler(later);
        Map<Handler, String> names = resolver.resolve(all(a, b, later));

        assertEquals("ROOTA", names.get(a));
        assertEquals("ROOTB", names.get(b));
        assertEquals("file-2", names.get(later));
    }

    @Test
    void aReloadThatReplacesTheRootHandlersTakesTheSnapshotAgain() {
        FileHandler first = new FileHandler();
        delayed.addHandler(first);
        file("ROOTF");
        config.put("quarkus.log.handlers", "ROOTF");
        assertEquals("ROOTF", resolver.resolve(all(first)).get(first));

        FileHandler reloaded = new FileHandler();
        delayed.setHandlers(new Handler[] {defaultConsole, defaultFile, reloaded});

        assertEquals("ROOTF", resolver.resolve(all(reloaded)).get(reloaded));
    }

    @Test
    void aHandlerAddedToACategoryAfterItsListStillNamesTheList() {
        PathFileHandler audit = new PathFileHandler("/logs/audit.log");
        FileHandler extra = new FileHandler();
        file("AUDIT", "/logs/audit.log");
        config.put("quarkus.log.category.\"x\".handlers", "AUDIT");
        category("x", audit, extra);

        Map<Handler, String> names = resolver.resolve(all(audit, extra));

        assertEquals("AUDIT", names.get(audit));
        assertEquals("file-2", names.get(extra));
        assertTrue(resolver.problems().isEmpty(), "the path confirmed the pair: " + resolver.problems());
    }

    @Test
    void aFilePairWhosePathDoesNotMatchSkipsTheList() {
        PathFileHandler other = new PathFileHandler("/logs/other.log");
        file("AUDIT", "/logs/audit.log");
        config.put("quarkus.log.category.\"x\".handlers", "AUDIT");
        category("x", other);

        assertEquals("file-2", resolver.resolve(all(other)).get(other));
        assertEquals(List.of(ListProblem.Kind.SKIPPED), kinds());
        assertTrue(resolver.problems().get(0).detail().contains("audit.log"), resolver.problems().toString());
    }

    @Test
    void aRelativePathIsComparedAgainstTheWorkingDirectory() {
        PathFileHandler audit = new PathFileHandler(new File("logs/audit.log").getAbsolutePath());
        file("AUDIT", "logs/./audit.log");
        config.put("quarkus.log.category.\"x\".handlers", "AUDIT");
        category("x", audit);

        assertEquals("AUDIT", resolver.resolve(all(audit)).get(audit));
    }

    @Test
    void anAttributeTheConfigurationDoesNotStateIsNotChecked() {
        PathFileHandler audit = new PathFileHandler("/anywhere/at/all.log");
        file("AUDIT"); // no path: Quarkus's default applies, and that is not checked
        config.put("quarkus.log.category.\"x\".handlers", "AUDIT");
        category("x", audit);

        assertEquals("AUDIT", resolver.resolve(all(audit)).get(audit));
    }

    @Test
    void aConsolePairWithAConfiguredFormatIsCheckedThroughTheBannerFormatter() {
        ConsoleHandler side = consoleWithPattern(new Banner(new Pattern("%m%n")));
        config.put("quarkus.log.handler.console.\"SIDE\".format", "%m%n");
        config.put("quarkus.log.category.\"x\".handlers", "SIDE");
        category("x", side, new ConsoleHandler());

        assertEquals("SIDE", resolver.resolve(all(side)).get(side));
        assertTrue(resolver.problems().isEmpty(), resolver.problems().toString());
    }

    @Test
    void aConsolePairWhoseFormatDoesNotMatchSkipsTheList() {
        ConsoleHandler side = consoleWithPattern(new Pattern("%d %m%n"));
        config.put("quarkus.log.handler.console.\"SIDE\".format", "%m%n");
        config.put("quarkus.log.category.\"x\".handlers", "SIDE");
        category("x", side);

        assertEquals("console-2", resolver.resolve(all(side)).get(side));
        assertEquals(List.of(ListProblem.Kind.SKIPPED), kinds());
    }

    @Test
    void aConsoleWhoseFormatterIsNotAPatternIsMatchedByType() {
        ConsoleHandler json = consoleWithPattern(new java.util.logging.SimpleFormatter());
        config.put("quarkus.log.handler.console.\"SIDE\".format", "%m%n"); // ignored by a JSON console
        config.put("quarkus.log.category.\"x\".handlers", "SIDE");
        category("x", json);

        assertEquals("SIDE", resolver.resolve(all(json)).get(json));
        assertTrue(resolver.problems().isEmpty(), "no other handlers on the logger: " + resolver.problems());
    }

    @Test
    void aListMatchedByTypeAloneNextToOtherHandlersIsReported() {
        ConsoleHandler side = new ConsoleHandler();
        console("SIDE");
        config.put("quarkus.log.category.\"x\".handlers", "SIDE");
        category("x", side, new ConsoleHandler());

        assertEquals("SIDE", resolver.resolve(all(side)).get(side));
        assertEquals(List.of(ListProblem.Kind.TYPE_ONLY), kinds());
    }

    @Test
    void aRootListMatchedByTypeAloneIsReportedOnceAHandlerIsAddedAfterIt() {
        ConsoleHandler rootC = new ConsoleHandler();
        delayed.addHandler(rootC);
        console("ROOTC");
        config.put("quarkus.log.handlers", "ROOTC");
        resolver.resolve(all(rootC));
        assertTrue(resolver.problems().isEmpty(), "Quarkus's own defaults come before the list");

        delayed.addHandler(new ConsoleHandler());
        resolver.resolve(all(rootC));

        assertEquals(List.of(ListProblem.Kind.TYPE_ONLY), kinds());
    }

    @Test
    void doctorReportsASkippedListAsAWarningAndATypeOnlyMatchAsInfo() {
        console("SIDE");
        file("AUDIT", "/logs/audit.log");
        config.put("quarkus.log.category.\"a\".handlers", "SIDE");
        config.put("quarkus.log.category.\"b\".handlers", "AUDIT");
        ConsoleHandler side = new ConsoleHandler();
        PathFileHandler other = new PathFileHandler("/logs/other.log");
        category("a", side, new ConsoleHandler());
        category("b", other);
        resolver.resolve(all(side, other));

        List<DoctorFinding> findings = QuarkusEngine.handlerNameFindings(resolver);

        assertEquals(2, findings.size(), findings.toString());
        for (DoctorFinding finding : findings) {
            assertEquals(QuarkusEngine.HANDLER_NAMES_CHECK, finding.check());
            Severity expected = finding.subject().contains("\"b\"") ? Severity.WARNING : Severity.INFO;
            assertEquals(expected, finding.severity(), finding.toString());
        }
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

    @Test
    void theConfigurationIsReadOnceOnceItWasReadable() {
        FileHandler alpha = new FileHandler();
        file("ALPHA");
        config.put("quarkus.log.category.\"a\".handlers", "ALPHA");
        category("a", alpha);
        int[] reads = {0};
        BooleanSupplier isReady = () -> true;
        QuarkusHandlerNameResolver counting = new QuarkusHandlerNameResolver(
                () -> { reads[0]++; return Optional.of(config); }, isReady, loggers::get);

        counting.resolve(all(alpha));
        counting.resolve(all(alpha));

        assertEquals(1, reads[0]);
    }

    @Test
    void aTypeNameKeptWhileUnreadableYieldsToALaterConfiguredNameThatEqualsIt() {
        FileHandler audit = new FileHandler();
        file("file");
        config.put("quarkus.log.category.\"a\".handlers", "file");
        category("a", audit);
        readable = false;
        assertEquals("file-2", resolver.resolve(all(audit)).get(audit)); // defaultFile took "file"

        readable = true;
        Map<Handler, String> names = resolver.resolve(all(audit));

        assertEquals("file", names.get(audit));
        assertEquals("file-2", names.get(defaultFile)); // no two handlers share a name
    }
}
