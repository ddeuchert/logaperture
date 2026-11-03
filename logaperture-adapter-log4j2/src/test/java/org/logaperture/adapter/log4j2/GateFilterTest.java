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

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.StormObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/log4j2-adapter.md A3, "Scope" 4: the gate. Real Log4j, a recording appender, a fake
 * {@link RuleGate} and {@link StormObserver}.
 */
class GateFilterTest {

    private LoggerContext context;
    private Log4j2LoggingAdapter adapter;
    private org.apache.logging.log4j.Logger log;
    private final List<String> written = new CopyOnWriteArrayList<>();
    private final List<Throwable> writtenThrown = new CopyOnWriteArrayList<>();
    private final List<RuleCandidateEvent> seen = new CopyOnWriteArrayList<>();
    private final List<String> formatted = new CopyOnWriteArrayList<>();
    private final AtomicInteger appliesCalls = new AtomicInteger();
    private volatile String denyContaining = "DROPME";

    private final RuleGate gate = new RuleGate() {
        @Override
        public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
            throw new AssertionError("the Log4j gate has no sibling filter: it must call evaluateOnce");
        }

        @Override
        public GateVerdict evaluateOnce(RuleCandidateEvent event) {
            seen.add(event);
            return event.formattedMessageSupplier().get().contains(denyContaining)
                    ? GateVerdict.deny("rule-1") : GateVerdict.allow();
        }

        @Override
        public boolean appliesTo(String loggerName) {
            appliesCalls.incrementAndGet();
            return loggerName.startsWith("app");
        }
    };

    private static BuiltConfiguration configuration() {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.add(builder.newLogger("app", org.apache.logging.log4j.Level.INFO));
        builder.add(builder.newRootLogger(org.apache.logging.log4j.Level.ERROR));
        return builder.build();
    }

    @BeforeEach
    void setUp() {
        context = Configurator.initialize(configuration());
        Appender recorder = new AbstractAppender("rec", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                written.add(event.getMessage().getFormattedMessage());
                writtenThrown.add(event.getThrown() == null ? new Throwable("none") : event.getThrown());
            }
        };
        recorder.start();
        context.getConfiguration().getRootLogger().addAppender(recorder, null, null);
        context.updateLoggers();
        adapter = new Log4j2LoggingAdapter(context, "2.20.0");
        adapter.installRulePipeline(gate);
        log = context.getLogger("app.svc");
    }

    @AfterEach
    void tearDown() {
        adapter.detach();
        context.stop();
    }

    @Test
    void aDeniedCall_isNotWritten_andTheRestAre() {
        log.info("DROPME one");
        log.info("kept one");
        assertEquals(List.of("kept one"), written);
    }

    @Test
    void everyOverload_reachesTheGate_withItsPatternAndThrowable() {
        Exception boom = new RuntimeException("boom");
        log.info("plain");
        log.info("one {}", 1);
        log.info("two {} {}", 1, 2);
        log.info("three {} {} {}", 1, 2, 3);
        log.info("four {} {} {} {}", 1, 2, 3, 4);
        log.info("five {} {} {} {} {}", 1, 2, 3, 4, 5);
        log.info("six {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6);
        log.info("seven {} {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6, 7);
        log.info("eight {} {} {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6, 7, 8);
        log.info("nine {} {} {} {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6, 7, 8, 9);
        log.info("ten {} {} {} {} {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        log.info("eleven {} {} {} {} {} {} {} {} {} {} {}", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        log.info("with throwable", boom);
        log.info("trailing {}", "p", boom);
        log.info((org.apache.logging.log4j.message.Message) new SimpleMessage("a message"), boom);
        log.info(new ParameterizedMessage("param {}", "p", boom));
        log.atInfo().withThrowable(boom).log("fluent");
        assertEquals(17, seen.size(), "one evaluation per call: " + seen.size());
        assertEquals(17, written.size());
        assertEquals("plain", written.get(0));
        assertEquals("eleven 1 2 3 4 5 6 7 8 9 10 11", written.get(11));
        assertEquals(boom, seen.get(12).thrown());
        assertEquals(boom, seen.get(13).thrown(), "a trailing throwable not consumed by a placeholder");
        assertEquals(boom, seen.get(14).thrown());
        assertEquals(boom, seen.get(15).thrown());
        assertEquals(boom, seen.get(16).thrown());
        assertNull(seen.get(1).thrown());
        assertEquals(Level.INFO, seen.get(0).level());
        assertEquals("app.svc", seen.get(0).loggerName());
    }

    @Test
    void aTrailingThrowableThatAPlaceholderConsumes_isNotTheThrowable() {
        Exception boom = new RuntimeException("boom");
        log.info("value {}", (Object) boom); // Java would pick (String, Throwable) without the cast
        assertNull(seen.get(0).thrown());
    }

    @Test
    void aCallTheLoggersLevelWouldNotWrite_neverReachesTheGate() {
        log.debug("DROPME disabled");
        log.trace("disabled {}", 1);
        assertEquals(0, seen.size());
        assertEquals(0, appliesCalls.get(), "not even asked whether a rule applies: the level compare comes first (G2)");
    }

    @Test
    void anIsEnabledGuard_isNotAnEvent() {
        assertTrue(log.isInfoEnabled());
        assertFalse(log.isDebugEnabled());
        assertEquals(0, seen.size());
    }

    @Test
    void aLoggerNoRuleCanTouch_isNotEvaluated() {
        org.apache.logging.log4j.Logger other = context.getLogger("elsewhere");
        other.error("DROPME elsewhere");
        assertEquals(0, seen.size());
        assertEquals(List.of("DROPME elsewhere"), written, "R3: no rule applies, so nothing is built or denied");
    }

    @Test
    void aSupplier_isReadAsItsText_notAsALambdaIdentity() {
        log.info(() -> "DROPME supplied");
        log.info((org.apache.logging.log4j.util.MessageSupplier) () -> new SimpleMessage("DROPME message supplied"));
        log.info(() -> "kept supplied");
        assertEquals(List.of("kept supplied"), written, "both drops matched on their text");
        assertEquals("kept supplied", seen.get(2).formattedMessageSupplier().get());
    }

    @Test
    void aSupplierIsEvaluatedAtTheGateOnlyWhenARuleReadsIt() {
        AtomicInteger evaluations = new AtomicInteger();
        RuleGate readsNothing = new RuleGate() {
            @Override
            public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
                return GateVerdict.allow();
            }

            @Override
            public GateVerdict evaluateOnce(RuleCandidateEvent event) {
                return GateVerdict.allow();
            }
        };
        adapter.installRulePipeline(readsNothing);
        log.info(() -> {
            evaluations.incrementAndGet();
            return "once";
        });
        assertEquals(1, evaluations.get(), "only Log4j's own evaluation, when it wrote the call");
    }

    /**
     * Log4j itself answers this differently by version: 2.20.0 makes no throwable of {@code escaped \{} only}
     * with one {@code Throwable} argument, 2.25.5 makes the argument the throwable. The gate asks Log4j's own
     * {@code ParameterizedMessage}, so it follows the running version; what must hold is that it agrees
     * with the event Log4j builds.
     */
    @Test
    void anEscapedPlaceholder_isDecidedAsTheRunningLog4jDecidesIt() {
        Exception boom = new RuntimeException("boom");
        log.error("escaped \\{} only", (Object) boom);
        Throwable inTheEvent = writtenThrown.get(0);
        Throwable atTheGate = seen.get(0).thrown();
        if (atTheGate == null) {
            assertEquals("none", inTheEvent.getMessage(), "the gate saw no throwable, so the event has none");
        } else {
            assertEquals(boom, inTheEvent);
        }
    }

    @Test
    void theMessageIsFormattedOnlyWhenARuleReadsIt() {
        int[] formats = {0};
        Object noisy = new Object() {
            @Override
            public String toString() {
                formats[0]++;
                return "noisy";
            }
        };
        RuleGate pattern = new RuleGate() {
            @Override
            public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
                return GateVerdict.allow();
            }

            @Override
            public GateVerdict evaluateOnce(RuleCandidateEvent event) {
                return GateVerdict.allow(); // reads nothing
            }
        };
        adapter.installRulePipeline(pattern);
        log.info("value {}", noisy);
        assertEquals(1, formats[0], "only the appender formatted it");
    }

    // --- storm detection ------------------------------------------------------------------------------

    private static final class Observed {
        final String logger;
        final Level level;
        final String throwableClass;
        final String rawMessage;

        Observed(String logger, Level level, String throwableClass, String rawMessage) {
            this.logger = logger;
            this.level = level;
            this.throwableClass = throwableClass;
            this.rawMessage = rawMessage;
        }
    }

    private final List<Observed> observed = new ArrayList<>();
    private volatile boolean stormActive = true;

    private final StormObserver observer = new StormObserver() {
        @Override
        public void observe(org.logaperture.core.StormObservation observation) {
            throw new AssertionError("the per-event path is observe(fields...)");
        }

        @Override
        public boolean isActive() {
            return stormActive;
        }

        @Override
        public <S> void observe(String loggerName, Level level, String throwableClassName, String rawMessage,
                java.time.Instant timestamp, S source, Details<S> details) {
            observed.add(new Observed(loggerName, level, throwableClassName, rawMessage));
            assertNotNull(details.firstOccurrence(source));
        }
    };

    @Test
    void stormDetection_seesTheRawPattern_theLevel_andTheThrowableClass() {
        adapter.installStormDetection(observer);
        log.info("user {} logged in", "bob");
        log.info("failed", new IllegalStateException("x"));
        assertEquals(2, observed.size());
        assertEquals("user {} logged in", observed.get(0).rawMessage, "the pattern, not the formatted line");
        assertEquals(Level.INFO, observed.get(0).level);
        assertEquals("app.svc", observed.get(0).logger);
        assertNull(observed.get(0).throwableClass);
        assertEquals("java.lang.IllegalStateException", observed.get(1).throwableClass);
    }

    @Test
    void aDeniedCall_neverReachesStormDetection() {
        adapter.installStormDetection(observer);
        log.info("DROPME x");
        log.info("kept");
        assertEquals(1, observed.size());
        assertEquals("kept", observed.get(0).rawMessage);
    }

    @Test
    void stormDetectionThatIsOff_isNotAsked_andLevelDisabledCallsAreNotObserved() {
        adapter.installStormDetection(observer);
        stormActive = false;
        log.info("kept");
        stormActive = true;
        log.debug("disabled");
        assertEquals(0, observed.size());
    }

    @Test
    void stormDetectionAlone_worksWithoutARulePipeline() {
        adapter.installRulePipeline(null);
        adapter.installStormDetection(observer);
        log.info("kept");
        assertEquals(1, observed.size());
        assertEquals(List.of("kept"), written);
    }

    // --- lifecycle ------------------------------------------------------------------------------------

    @Test
    void installingAgain_isIdempotent_oneFilterOneEvaluation() {
        adapter.installRulePipeline(gate);
        adapter.installRulePipeline(gate);
        adapter.installStormDetection(observer);
        adapter.installStormDetection(observer);
        log.info("once");
        assertEquals(1, seen.size());
        assertEquals(1, observed.size());
    }

    @Test
    void aConfigurationSwapDropsTheGate_andInstallingAgainBringsItBack() {
        context.setConfiguration(configuration());
        log.info("DROPME unguarded"); // between the swap and the re-install, events pass (spec "Scope" 3)
        assertEquals(0, seen.size());

        adapter.installRulePipeline(gate); // core's reset hook calls this
        log.info("DROPME guarded");
        assertEquals(1, seen.size());
    }

    @Test
    void detach_afterASwapAndAnInstall_leavesNoFilterOnTheLiveConfiguration() {
        context.setConfiguration(configuration());
        adapter.installRulePipeline(gate);
        adapter.detach();
        assertNull(context.getConfiguration().getFilter(), "removed from the configuration it was last put on");
    }

    @Test
    void afterDetach_theGateIsInert() {
        adapter.detach();
        log.info("DROPME after");
        assertEquals(0, seen.size());
    }

    @Test
    void aGateThatThrows_failsOpen() {
        RuleGate broken = new RuleGate() {
            @Override
            public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) {
                throw new IllegalStateException("bug");
            }

            @Override
            public GateVerdict evaluateOnce(RuleCandidateEvent event) {
                throw new IllegalStateException("bug");
            }
        };
        adapter.installRulePipeline(broken);
        log.info("DROPME still written");
        assertEquals(List.of("DROPME still written"), written);
    }
}
