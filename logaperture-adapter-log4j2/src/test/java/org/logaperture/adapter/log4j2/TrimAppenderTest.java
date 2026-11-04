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
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.TrimDecision;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * doc/specs/log4j2-adapter.md A4, "Scope" 5: the slot wrapper. Real Log4j configurations (a real
 * {@code ConsoleAppender} with a {@code PatternLayout}, real {@code AppenderRef}s), the console captured,
 * and a fake {@link RuleGate} whose {@code trimChoice} is the only thing asked.
 */
class TrimAppenderTest {

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private LoggerContext context;
    private Log4j2LoggingAdapter adapter;
    private org.apache.logging.log4j.Logger log;
    private final AtomicInteger trimChoices = new AtomicInteger();
    private volatile TrimDecision decision = new TrimDecision("rule-1", 1, false);

    private final RuleGate gate = new RuleGate() {
        @Override
        public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
            throw new AssertionError("the render stage never evaluates: the gate counted already");
        }

        @Override
        public GateVerdict evaluateOnce(RuleCandidateEvent event) {
            throw new AssertionError("the render stage never evaluates: the gate counted already");
        }

        @Override
        public boolean appliesTo(String loggerName) {
            return true;
        }

        @Override
        public TrimDecision trimChoice(RuleCandidateEvent event) {
            trimChoices.incrementAndGet();
            return event.formattedMessageSupplier().get().contains("TRIMME") ? decision : null;
        }
    };

    /** The rooted configuration a file would give: Console + PatternLayout, optionally behind an Async appender. */
    /** When set, the Console appender carries its own filter that denies everything below FATAL. */
    private boolean consoleDeniesBelowFatal;

    private BuiltConfiguration configuration(String pattern, Level referenceLevel, boolean async,
            String layoutPlugin) {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        AppenderComponentBuilder console = builder.newAppender("Con", "Console").addAttribute("target", "SYSTEM_OUT");
        console.add(layoutPlugin == null ? builder.newLayout("PatternLayout").addAttribute("pattern", pattern)
                : builder.newLayout(layoutPlugin));
        if (consoleDeniesBelowFatal) {
            console.add(builder.newFilter("ThresholdFilter", org.apache.logging.log4j.core.Filter.Result.NEUTRAL,
                    org.apache.logging.log4j.core.Filter.Result.DENY).addAttribute("level", "FATAL"));
        }
        builder.add(console);
        String reference = "Con";
        if (async) {
            builder.add(builder.newAppender("Async", "Async").addComponent(builder.newAppenderRef("Con")));
            reference = "Async";
        }
        RootLoggerComponentBuilder root = builder.newRootLogger(Level.ERROR);
        root.add(referenceLevel == null ? builder.newAppenderRef(reference)
                : builder.newAppenderRef(reference).addAttribute("level", referenceLevel.name()));
        builder.add(root);
        return builder.build();
    }

    private void start(String pattern, Level referenceLevel, boolean async, String layoutPlugin) {
        context = Configurator.initialize(configuration(pattern, referenceLevel, async, layoutPlugin));
        adapter = new Log4j2LoggingAdapter(context, "2.20.0");
        log = context.getLogger("app.svc");
    }

    @BeforeEach
    void captureTheConsole() {
        originalOut = System.out;
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8)); // before the ConsoleAppender is built
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.detach();
        }
        if (context != null) {
            context.stop();
        }
        System.setOut(originalOut);
    }

    /** Whether this Log4j renders a trimmed exception correctly; the tests that need it assume so (A4, A11). */
    private boolean trimWorksHere() {
        return TrimProbe.problem(context.getConfiguration()) == null;
    }

    private String written() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (console.size() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return console.toString(StandardCharsets.UTF_8);
    }

    private static Throwable deepThrowable() {
        return new RuntimeException("outer failure", new IllegalStateException("root cause"));
    }

    @Test
    void aMatchingEvent_isTrimmed_underPercentEx_withNoSyntheticClassName() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        log.error("TRIMME", deepThrowable());
        String text = written();
        assertTrue(text.contains("java.lang.RuntimeException: outer failure [stack trace trimmed: "), text);
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: root cause [stack trace trimmed: "),
                "the cause is trimmed too:\n" + text);
        assertFalse(text.contains("Synthetic"), text);
    }

    @Test
    void aMatchingEvent_isTrimmed_underPercentXEx_withNoSyntheticClassName() throws Exception {
        start("%m|%xEx%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        log.error("TRIMME", deepThrowable());
        String text = written();
        assertTrue(text.contains("java.lang.RuntimeException: outer failure [stack trace trimmed: "), text);
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: root cause [stack trace trimmed: "), text);
        assertFalse(text.contains("Synthetic"), "the extended converter reads the proxy: " + text);
        assertFalse(text.contains(": null"), "no tail left by the proxy's null message: " + text);
    }

    @Test
    void theFramesKeptAreTheDecisions_andCollapseDropsTheCauses() throws Exception {
        decision = new TrimDecision("rule-1", 2, true);
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        log.error("TRIMME", deepThrowable());
        String text = written();
        assertEquals(2, text.lines().filter(line -> line.trim().startsWith("at ")).count(), text);
        assertFalse(text.contains("Caused by"), text);
    }

    @Test
    void anEventWithNoThrowable_orNoMatchingRule_isWrittenAsItWas() throws Exception {
        start("%m|%ex%n", null, false, null);
        adapter.installTrimRendering(gate);
        log.error("TRIMME but no throwable");
        log.error("not matching", deepThrowable());
        String text = written();
        assertTrue(text.startsWith("TRIMME but no throwable|"), text);
        assertTrue(text.contains("not matching|") && text.contains("java.lang.RuntimeException: outer failure"), text);
        assertFalse(text.contains("stack trace trimmed"), text);
        assertTrue(text.contains("\tat "), "the whole trace: " + text);
    }

    @Test
    void theRenderStageNeverCounts_itOnlyAsksForTheChoice() {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        log.error("TRIMME", deepThrowable()); // an evaluate or evaluateOnce would hit the AssertionError
        log.error("TRIMME", deepThrowable());
        assertEquals(2, trimChoices.get());
    }

    @Test
    void theOriginalThrowableIsNeverMutated() {
        start("%m|%ex%n", null, false, null);
        adapter.installTrimRendering(gate);
        Throwable thrown = deepThrowable();
        int before = thrown.getStackTrace().length;
        log.error("TRIMME", thrown);
        assertEquals(before, thrown.getStackTrace().length);
        assertEquals("root cause", thrown.getCause().getMessage());
    }

    @Test
    void anAppenderWhoseLayoutIsNotAPatternLayout_isLeftAlone() {
        start(null, null, false, "HtmlLayout");
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        assertFalse(context.getConfiguration().getRootLogger().getAppenders().get("Con") instanceof TrimAppender,
                "only text through a PatternLayout is trimmed (trim-rule.md 'Text formatters only')");
    }

    @Test
    void anAsyncAppender_isWrappedWhenEverythingBehindItWritesText_andTrimsBeforeTheQueue() throws Exception {
        start("%m|%ex%n", null, true, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        assertInstanceOf(TrimAppender.class, context.getConfiguration().getRootLogger().getAppenders().get("Async"));
        log.error("TRIMME", deepThrowable());
        String text = written();
        assertTrue(text.contains("[stack trace trimmed: "), "trimmed on the calling thread, before the queue:\n" + text);
        assertFalse(text.contains("Synthetic"), text);
    }

    @Test
    void theReferencesLevelIsKept() throws Exception {
        start("%m|%ex%n", Level.FATAL, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        assertInstanceOf(TrimAppender.class, context.getConfiguration().getRootLogger().getAppenders().get("Con"));
        log.error("TRIMME below the reference's FATAL", deepThrowable());
        Thread.sleep(100);
        assertEquals(0, console.size(), "the AppenderRef level still filters: " + console);
    }

    @Test
    void aSlotWithNoAppenderRef_isLeftAlone_itsLevelAndFilterCannotBeKept() {
        start("%m|%ex%n", null, false, null);
        Appender bare = new AbstractAppender("bare", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
            }
        };
        bare.start();
        context.getConfiguration().getRootLogger().addAppender(bare, null, null); // in code: no reference
        adapter.installTrimRendering(gate);
        assertFalse(context.getConfiguration().getRootLogger().getAppenders().get("bare") instanceof TrimAppender);
    }

    @Test
    void installingAgain_wrapsOnce() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        adapter.installTrimRendering(gate);
        adapter.installTrimRendering(gate);
        TrimAppender wrapper = assertInstanceOf(TrimAppender.class,
                context.getConfiguration().getRootLogger().getAppenders().get("Con"));
        assertFalse(wrapper.delegate() instanceof TrimAppender, "never a wrapper of a wrapper");
        log.error("TRIMME", deepThrowable());
        assertEquals(1, trimChoices.get());
        assertEquals(1, written().lines().filter(line -> line.startsWith("TRIMME|")).count());
    }

    @Test
    void aSwapDropsTheWrappers_andInstallingAgainBringsThemBack() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        BuiltConfiguration second = configuration("%m|%ex%n", null, false, null);
        context.setConfiguration(second);
        assertFalse(second.getRootLogger().getAppenders().get("Con") instanceof TrimAppender);
        adapter.installTrimRendering(gate); // core's reset hook calls this
        assertInstanceOf(TrimAppender.class, second.getRootLogger().getAppenders().get("Con"));
        log = context.getLogger("app.svc");
        log.error("TRIMME", deepThrowable());
        assertTrue(written().contains("[stack trace trimmed: "));
    }

    @Test
    void detach_putsTheOriginalAppendersBackInTheirSlots() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        Appender original = context.getConfiguration().getAppender("Con");
        adapter.installTrimRendering(gate);
        assertInstanceOf(TrimAppender.class, context.getConfiguration().getRootLogger().getAppenders().get("Con"));
        adapter.detach();
        assertEquals(original, context.getConfiguration().getRootLogger().getAppenders().get("Con"),
                "the slot holds the appender it held before");
        log.error("TRIMME", deepThrowable());
        String text = written();
        assertTrue(text.startsWith("TRIMME|"), "still written: " + text);
        assertFalse(text.contains("stack trace trimmed"), text);
    }

    @Test
    void anAppendersOwnFilter_keepsDenying_throughTheWrapper() throws Exception {
        consoleDeniesBelowFatal = true;
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        log.error("denied before the wrapper", deepThrowable());
        Thread.sleep(100);
        assertEquals(0, console.size(), "the appender's own ThresholdFilter denies an ERROR: " + console);
        adapter.installTrimRendering(gate);
        assertInstanceOf(TrimAppender.class, context.getConfiguration().getRootLogger().getAppenders().get("Con"));
        log.error("TRIMME denied after the wrapper too", deepThrowable());
        Thread.sleep(100);
        assertEquals(0, console.size(), "wrapping must not lose the appender's filter: " + console);
    }

    @Test
    void aFilterAddedToTheOriginalAppenderAfterWrapping_isFollowed() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        log.error("written before the filter exists", deepThrowable());
        Thread.sleep(100);
        int written = console.size();
        assertTrue(written > 0);

        // another plugin adds a filter to the appender itself; the wrapper answers for the delegate each time
        ((org.apache.logging.log4j.core.filter.Filterable) context.getConfiguration().getAppender("Con"))
                .addFilter(org.apache.logging.log4j.core.filter.ThresholdFilter.createFilter(Level.FATAL,
                        org.apache.logging.log4j.core.Filter.Result.NEUTRAL, org.apache.logging.log4j.core.Filter.Result.DENY));
        log.error("denied by the filter added later", deepThrowable());
        Thread.sleep(100);
        assertEquals(written, console.size(), "the later filter denies it: " + console);
    }

    @Test
    void wrappersLeftByAnAdapterThatWentAway_areTakenOverByTheNextOne() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        TrimAppender.Shared dead = new TrimAppender.Shared(); // its adapter let go: no gate
        TrimSlots.wrapAll(context.getConfiguration(), dead, new HandlerLevels());
        TrimAppender left = assertInstanceOf(TrimAppender.class,
                context.getConfiguration().getRootLogger().getAppenders().get("Con"));
        assertEquals(dead, left.shared());

        adapter.installTrimRendering(gate); // must not skip the slot as 'already wrapped'

        assertEquals(left, context.getConfiguration().getRootLogger().getAppenders().get("Con"), "the wrapper is kept");
        assertTrue(left.shared() != dead, "and now answers to the new adapter");
        log.error("TRIMME", deepThrowable());
        assertTrue(written().contains("[stack trace trimmed: "));
    }

    @Test
    void aMultiLineExceptionMessage_rendersTheSameUnderBothConverters() throws Exception {
        start("%m|%xEx%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        log.error("TRIMME", new RuntimeException("first line\nsecond line"));
        String text = written();
        assertTrue(text.contains("java.lang.RuntimeException: first line\nsecond line [stack trace trimmed: "), text);
        assertFalse(text.contains(": null"), "no tail after a multi-line name: " + text);
    }

    @Test
    void aFailedSwap_putsTheOriginalBack_andCarriesOn() {
        start("%m|%ex%n", null, false, null);
        Configuration config = context.getConfiguration();
        Appender original = config.getAppender("Con");
        java.util.List<AppenderRef> references = java.util.List.of(AppenderRef.createAppenderRef("Con", null, null));
        LoggerConfig failing = new LoggerConfig("failing", references, null, Level.INFO, true,
                Property.EMPTY_ARRAY, config, false) {
            @Override
            public void addAppender(Appender appender, Level level, org.apache.logging.log4j.core.Filter filter) {
                if (appender instanceof TrimAppender) {
                    throw new IllegalStateException("simulated failure to add the wrapper");
                }
                super.addAppender(appender, level, filter);
            }
        };
        failing.addAppender(original, null, null);
        config.addLogger("failing", failing);

        int wrapped = TrimSlots.wrapAll(config, new TrimAppender.Shared(), new HandlerLevels());

        assertEquals(original, failing.getAppenders().get("Con"), "the logger did not lose its appender");
        assertTrue(wrapped >= 0);
        assertInstanceOf(TrimAppender.class, config.getRootLogger().getAppenders().get("Con"),
                "the other slots are still wrapped");
    }

    @Test
    void theProxyIsBuiltOnlyForAnAppenderWhoseLayoutCanReadIt() {
        assumeTrue(true);
        start("%m|%ex%n", null, false, null);
        Appender plain = context.getConfiguration().getAppender("Con");
        assertFalse(new TrimAppender(plain, new TrimAppender.Shared(),
                TrimSlots.rendersFromProxy(plain, context.getConfiguration())).buildsProxy(), "%ex reads the throwable");
        for (String pattern : new String[] {"%m|%xEx%n", "%m %rEx", "%d %xwEx{short}", "%-5p %xThrowable", "%m %rootException",
                "%level %logger %msg%n" /* no throwable converter: PatternLayout adds one, which reads the proxy */}) {
            context.stop();
            start(pattern, null, false, null);
            assertTrue(TrimSlots.rendersFromProxy(context.getConfiguration().getAppender("Con"),
                    context.getConfiguration()), pattern + " reads the proxy");
        }
    }

    @Test
    void anAppenderWithoutAnExtendedConverter_stillTrimsThroughPercentEx_withoutBuildingTheProxy() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        TrimAppender wrapper = assertInstanceOf(TrimAppender.class,
                context.getConfiguration().getRootLogger().getAppenders().get("Con"));
        assertFalse(wrapper.buildsProxy());
        log.error("TRIMME", deepThrowable());
        assertTrue(written().contains("[stack trace trimmed: "));
    }

    @Test
    void anAsyncAppender_buildsTheProxyIfAnythingBehindItCanRead() {
        start("%m|%xEx%n", null, true, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        adapter.installTrimRendering(gate);
        assertTrue(assertInstanceOf(TrimAppender.class,
                context.getConfiguration().getRootLogger().getAppenders().get("Async")).buildsProxy());
    }

    @Test
    void theWrapperAnswersForTheAppenderItStandsFor() {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        Appender original = context.getConfiguration().getAppender("Con");
        org.apache.logging.log4j.core.ErrorHandler custom = new org.apache.logging.log4j.core.ErrorHandler() {
            @Override
            public void error(String msg) {
            }

            @Override
            public void error(String msg, Throwable t) {
            }

            @Override
            public void error(String msg, LogEvent event, Throwable t) {
            }
        };
        original.stop(); // older Log4j refuses a new handler on a started appender
        original.setHandler(custom);
        original.start();
        adapter.installTrimRendering(gate);
        Appender slot = context.getConfiguration().getRootLogger().getAppenders().get("Con");
        assertEquals(custom, slot.getHandler(), "AppenderControl asks the slot for the error handler");
        assertTrue(slot.isStarted());
        original.stop();
        assertFalse(slot.isStarted(), "a wrapper in front of a stopped appender is not started");
    }

    @Test
    void theProbeRunsOncePerAdapter() {
        start("%m|%ex%n", null, false, null);
        AtomicInteger probes = new AtomicInteger();
        adapter.trimProbe = configuration -> {
            probes.incrementAndGet();
            return null;
        };
        adapter.installTrimRendering(gate);
        adapter.installTrimRendering(gate);
        adapter.installTrimRendering(gate);
        assertEquals(1, probes.get(), "a reset-hook call must not probe again");
    }

    @Test
    void aGateThatThrows_failsOpen_andWritesTheEventUntrimmed() throws Exception {
        RuleGate broken = new RuleGate() {
            @Override
            public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
                return GateVerdict.allow();
            }

            @Override
            public TrimDecision trimChoice(RuleCandidateEvent event) {
                throw new IllegalStateException("bug");
            }
        };
        start("%m|%ex%n", null, false, null);
        adapter.installTrimRendering(broken);
        log.error("TRIMME", deepThrowable());
        String text = written();
        assertTrue(text.startsWith("TRIMME|"), text);
        assertFalse(text.contains("stack trace trimmed"), text);
    }

    @Test
    void theProbePassesOnThisLog4j_andTrimIsOn() {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        assertNull(TrimProbe.problem(context.getConfiguration()));
        adapter.installTrimRendering(gate);
        assertEquals(java.util.Optional.empty(), adapter.trimDisabledReason());
    }

    @Test
    void aWrapperKeepsTheOriginalsNameAndLayout() {
        start("%m|%ex%n", null, false, null);
        assumeTrue(trimWorksHere(), "trim is off on this Log4j");
        Configuration config = context.getConfiguration();
        Appender original = config.getAppender("Con");
        adapter.installTrimRendering(gate);
        Appender slot = config.getRootLogger().getAppenders().get("Con");
        assertEquals("Con", slot.getName());
        assertEquals(original.getLayout(), slot.getLayout());
        assertEquals(original, config.getAppender("Con"), "config.getAppenders() still lists the original");
    }

    /** 2.25.0 to 2.25.3 render a trimmed exception from the throwable's class with no hook (A4, A11). */
    @Test
    void whereLog4jCannotRenderATrim_nothingIsWrapped_andTheReasonIsKept() throws Exception {
        start("%m|%ex%n", null, false, null);
        assumeFalse(trimWorksHere(), "this Log4j renders a trim correctly");
        adapter.installTrimRendering(gate);
        assertFalse(context.getConfiguration().getRootLogger().getAppenders().get("Con") instanceof TrimAppender);
        assertTrue(adapter.trimDisabledReason().isPresent());
        log.error("TRIMME", deepThrowable());
        assertEquals(0, trimChoices.get(), "no wrapper, so the choice is never asked");
        assertFalse(written().contains("stack trace trimmed"));
    }
}
