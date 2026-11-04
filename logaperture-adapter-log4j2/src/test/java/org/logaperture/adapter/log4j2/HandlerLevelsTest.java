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
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.TrimDecision;
import org.logaperture.core.spi.UnknownHandlerException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * doc/specs/log4j2-adapter.md A5, Slice 6: handler levels. Real Log4j configurations and the console
 * captured. The file: {@code Con} is referenced from the root (no level) and from {@code app} (WARN);
 * {@code Iso} from {@code iso}, which is not additive; {@code Behind} only from an {@code <Async>}.
 */
class HandlerLevelsTest {

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private LoggerContext context;
    private Log4j2LoggingAdapter adapter;

    private static BuiltConfiguration file() {
        ConfigurationBuilder<BuiltConfiguration> b = ConfigurationBuilderFactory.newConfigurationBuilder();
        for (String name : List.of("Con", "Iso", "Behind")) {
            b.add(b.newAppender(name, "Console").addAttribute("target", "SYSTEM_OUT")
                    .add(b.newLayout("PatternLayout").addAttribute("pattern", name + ":%m|%ex%n")));
        }
        b.add(b.newAppender("Async", "Async").addComponent(b.newAppenderRef("Behind")));
        b.add(b.newRootLogger(Level.TRACE).add(b.newAppenderRef("Con")).add(b.newAppenderRef("Async")));
        b.add(b.newLogger("app", Level.TRACE).addAttribute("additivity", true)
                .add(b.newAppenderRef("Con").addAttribute("level", "WARN")));
        b.add(b.newLogger("iso", Level.TRACE).addAttribute("additivity", false).add(b.newAppenderRef("Iso")));
        return b.build();
    }

    @BeforeEach
    void start() {
        originalOut = System.out;
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        context = Configurator.initialize(file());
        adapter = new Log4j2LoggingAdapter(context, "2.20.0");
    }

    @AfterEach
    void tearDown() {
        adapter.detach();
        context.stop();
        System.setOut(originalOut);
    }

    private static HandlerRef ref(String name) {
        return new HandlerRef(name);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private String written() throws InterruptedException {
        Thread.sleep(150);
        return console.toString(StandardCharsets.UTF_8);
    }

    @Test
    void handlersAreTheReferencedAppenders_byName_andAllHandlersIsKnown() {
        assertTrue(adapter.hasHandlerLevels());
        assertEquals(List.of(ref("Con"), ref("Async"), ref("Iso")), adapter.realHandlers());
        assertEquals(List.of(ref("Con"), ref("Async"), ref("Iso"), HandlerRef.ALL_HANDLERS), adapter.knownHandlers());
        assertFalse(adapter.realHandlers().contains(ref("Behind")), "behind <Async> the <Async> is the handler (S6-1)");
    }

    @Test
    void anUnsetHandlerReportsALL_aReferencedOneTheMostRestrictive() {
        assertEquals(Optional.of(org.logaperture.api.Level.WARN), adapter.handlerLevel(ref("Con")));
        assertEquals(Optional.of(org.logaperture.api.Level.ALL), adapter.handlerLevel(ref("Iso")),
                "nothing is filtered; core captures a baseline only from a level that is present");
    }

    @Test
    void setting_changesEveryReference_andReturnsThePrevious() throws Exception {
        Optional<org.logaperture.api.Level> previous = adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR);
        assertEquals(Optional.of(org.logaperture.api.Level.WARN), previous);
        assertEquals(Optional.of(org.logaperture.api.Level.ERROR), adapter.handlerLevel(ref("Con")));

        context.getLogger("other").warn("root path warn");
        context.getLogger("app").warn("app path warn");
        context.getLogger("app").error("app path error");
        String text = written();
        assertFalse(text.contains("Con:app path warn"), "raised on the app reference too: " + text);
        assertFalse(text.contains("Con:root path warn"), "and on the root's: " + text);
        assertTrue(text.contains("Con:app path error"), text);
    }

    @Test
    void settingAndLoweringAgain_reachesTheLowerLevel() throws Exception {
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR);
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.DEBUG);
        assertEquals(Optional.of(org.logaperture.api.Level.DEBUG), adapter.handlerLevel(ref("Con")));
        context.getLogger("other").debug("now visible");
        assertTrue(written().contains("Con:now visible"));
    }

    @Test
    void nullGivesEachReferenceItsOwnFileLevel_notOneFlattenedValue() throws Exception {
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR);
        Optional<org.logaperture.api.Level> previous = adapter.setHandlerLevel(ref("Con"), null);
        assertEquals(Optional.of(org.logaperture.api.Level.ERROR), previous);

        // an "app" event reaches Con twice: through the app reference (WARN in the file) and the root's (none)
        context.getLogger("app").warn("warned");
        context.getLogger("app").info("informed");
        String text = written();
        assertEquals(2, count(text, "Con:warned"), text);
        assertEquals(1, count(text, "Con:informed"), "only the root reference passes INFO: " + text);
    }

    @Test
    void anUnknownHandler_isRefused() {
        assertThrows(UnknownHandlerException.class, () -> adapter.setHandlerLevel(ref("Nope"), org.logaperture.api.Level.WARN));
        assertThrows(UnknownHandlerException.class, () -> adapter.setHandlerLevel(ref("Behind"), org.logaperture.api.Level.WARN));
        assertEquals(Optional.empty(), adapter.handlerLevel(ref("Nope")));
    }

    @Test
    void thePathStopsWhereAdditivityDoes() {
        assertEquals(List.of(ref("Iso")), adapter.handlersOnPath("iso.sub"));
        assertEquals(List.of(ref("Con"), ref("Async")), adapter.handlersOnPath("app.sub"));
        assertEquals(List.of(ref("Con"), ref("Async")), adapter.handlersOnRoot());
    }

    @Test
    void floorsAreTheHandlersOnThePathStricterThanTheTarget() {
        assertEquals(List.of(new HandlerFloor(ref("Con"), org.logaperture.api.Level.WARN)),
                adapter.handlerFloorsBelow("app.sub", org.logaperture.api.Level.DEBUG));
        assertEquals(List.of(), adapter.handlerFloorsBelow("other", org.logaperture.api.Level.DEBUG), "no level on that path");
        assertEquals(List.of(), adapter.handlerFloorsBelow("app.sub", org.logaperture.api.Level.WARN), "not stricter than WARN");
        assertEquals(List.of(), adapter.handlerFloorsBelow("app.sub", null));
        adapter.setHandlerLevel(ref("Iso"), org.logaperture.api.Level.ERROR);
        assertEquals(List.of(new HandlerFloor(ref("Iso"), org.logaperture.api.Level.ERROR)),
                adapter.handlerFloorsBelow("iso", org.logaperture.api.Level.INFO));
    }

    @Test
    void aReconfiguration_startsFromTheFilesLevels() {
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR);
        context.setConfiguration(file()); // a swap: new LoggerConfigs, the old table goes with them
        assertEquals(Optional.of(org.logaperture.api.Level.WARN), adapter.handlerLevel(ref("Con")),
                "what a swap does to a handler level; core's reset hook re-applies the override");
    }

    // ---- the interaction with trim (S6-3) ----

    private final RuleGate gate = new RuleGate() {
        @Override
        public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
            throw new AssertionError("never evaluates");
        }

        @Override
        public GateVerdict evaluateOnce(RuleCandidateEvent event) {
            throw new AssertionError("never evaluates");
        }

        @Override
        public boolean appliesTo(String loggerName) {
            return true;
        }

        @Override
        public TrimDecision trimChoice(RuleCandidateEvent event) {
            return null;
        }
    };

    @Test
    void aHandlerLevelSetOnAWrappedSlot_sticksThroughDetach() throws Exception {
        assumeTrue(TrimProbe.problem(context.getConfiguration()) == null, "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        assertInstanceOf(TrimAppender.class, context.getConfiguration().getRootLogger().getAppenders().get("Con"));

        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR);
        assertInstanceOf(TrimAppender.class, context.getConfiguration().getRootLogger().getAppenders().get("Con"),
                "the wrapper is swapped, not the original behind it");
        adapter.installTrimRendering(gate); // idempotent re-install reads the level from the table
        adapter.detach(); // unwraps: must not put the file's level back

        assertEquals(Optional.of(org.logaperture.api.Level.ERROR), adapter.handlerLevel(ref("Con")));
        context.getLogger("other").warn("must stay hidden");
        assertFalse(written().contains("Con:must stay hidden"));
    }

    @Test
    void wrappingAfterTheLevelWasSet_keepsTheSetLevel() throws Exception {
        assumeTrue(TrimProbe.problem(context.getConfiguration()) == null, "trim is off on this Log4j");
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR);
        adapter.installTrimRendering(gate);
        context.getLogger("other").warn("must stay hidden");
        context.getLogger("other").error("shown");
        String text = written();
        assertFalse(text.contains("Con:must stay hidden"), text);
        assertTrue(text.contains("Con:shown"), text);
    }

    @Test
    void aReferenceAlreadyAtTheLevel_isNotSwapped() {
        org.apache.logging.log4j.core.Appender before = context.getConfiguration().getRootLogger().getAppenders().get("Con");
        var order = new java.util.ArrayList<>(context.getConfiguration().getRootLogger().getAppenders().keySet());
        adapter.setHandlerLevel(ref("Iso"), org.logaperture.api.Level.ALL); // no level in the file: already ALL
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.WARN); // the app reference is WARN, the root's has none
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.WARN); // now both are: nothing to do
        assertEquals(order, new java.util.ArrayList<>(context.getConfiguration().getRootLogger().getAppenders().keySet()));
        assertTrue(before == context.getConfiguration().getRootLogger().getAppenders().get("Con"));
    }

    @Test
    void aFailedSwap_leavesNothingChanged() {
        var config = context.getConfiguration();
        var refs = new org.apache.logging.log4j.core.config.LoggerConfig("x", java.util.List.of(
                org.apache.logging.log4j.core.config.AppenderRef.createAppenderRef("Con", null, null)), null,
                Level.TRACE, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY, config, false) {
            @Override
            public void addAppender(org.apache.logging.log4j.core.Appender appender, Level level,
                    org.apache.logging.log4j.core.Filter filter) {
                if (level == Level.ERROR) {
                    throw new IllegalStateException("simulated");
                }
                super.addAppender(appender, level, filter);
            }
        };
        refs.addAppender(config.getAppender("Con"), null, null);
        config.addLogger("x", refs);

        assertThrows(IllegalStateException.class, () -> adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.ERROR));
        assertTrue(refs.getAppenders().containsKey("Con"), "the failed slot got its appender back");
        assertTrue(config.getRootLogger().getAppenders().containsKey("Con"));
        // all or nothing: the references that had changed went back
        assertEquals(Optional.of(org.logaperture.api.Level.WARN), adapter.handlerLevel(ref("Con")));
    }

    /** The limit of spec Slice 6 "As built": core resets with one baseline value, the most restrictive. */
    @Test
    void resetToTheBaseline_flattensAnAppenderAtTwoLevelsToTheMostRestrictive() throws Exception {
        org.logaperture.api.Level baseline = adapter.handlerLevel(ref("Con")).orElseThrow(); // what core captures
        assertEquals(org.logaperture.api.Level.WARN, baseline);
        adapter.setHandlerLevel(ref("Con"), org.logaperture.api.Level.DEBUG);
        adapter.setHandlerLevel(ref("Con"), baseline); // what core's reset does
        context.getLogger("other").info("root path");
        assertFalse(written().contains("Con:root path"),
                "the root reference had no level in the file and now has WARN: the documented limit");
    }
}
