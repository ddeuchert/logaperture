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
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Severity;
import org.logaperture.core.GateVerdict;
import org.logaperture.core.RuleCandidateEvent;
import org.logaperture.core.RuleGate;
import org.logaperture.core.TrimDecision;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/log4j2-adapter.md Slice 7 (A9, A12): what the adapter tells {@code doctor}. */
class HandlerDiagnosticsTest {

    @TempDir
    Path dir;

    private LoggerContext context;
    private Log4j2LoggingAdapter adapter;

    private BuiltConfiguration file() {
        return file(dir, false);
    }

    private BuiltConfiguration file(Path baseDir, boolean mapped) {
        return buildConfig(baseDir, mapped);
    }

    /** @param mapped when {@code true}, also adds a {@code MemoryMappedFile} appender —
     *  on Windows its {@code MappedByteBuffer} keeps the file locked until GC,
     *  so only the test that needs it turns it on. */
    private BuiltConfiguration buildConfig(Path baseDir, boolean mapped) {
        ConfigurationBuilder<BuiltConfiguration> b = ConfigurationBuilderFactory.newConfigurationBuilder();
        var layout = b.newLayout("PatternLayout").addAttribute("pattern", "%m%n");
        b.add(b.newAppender("Con", "Console").add(layout));
        b.add(b.newAppender("Plain", "File").addAttribute("fileName", baseDir.resolve("plain.log").toString()).add(layout));
        b.add(b.newAppender("Random", "RandomAccessFile")
                .addAttribute("fileName", baseDir.resolve("random.log").toString()).addAttribute("immediateFlush", false).add(layout));
        if (mapped) {
            b.add(b.newAppender("Mapped", "MemoryMappedFile").addAttribute("fileName", baseDir.resolve("mapped.log").toString())
                    .add(layout));
        }
        b.add(b.newAppender("Rolling", "RollingFile").addAttribute("fileName", baseDir.resolve("rolling.log").toString())
                .addAttribute("filePattern", baseDir.resolve("rolling-%i.log").toString()).add(layout)
                .addComponent(b.newComponent("Policies").addComponent(
                        b.newComponent("SizeBasedTriggeringPolicy").addAttribute("size", "1MB"))));
        b.add(b.newAppender("AsyncPlain", "Async").addComponent(b.newAppenderRef("Plain")));
        b.add(b.newAppender("AsyncTwo", "Async").addComponent(b.newAppenderRef("Plain")).addComponent(b.newAppenderRef("Con")));
        b.add(b.newAppender("AsyncRolling", "Async").addComponent(b.newAppenderRef("Rolling")));
        var rootLogger = b.newRootLogger(Level.INFO).add(b.newAppenderRef("Con")).add(b.newAppenderRef("Plain"))
                .add(b.newAppenderRef("Random")).add(b.newAppenderRef("Rolling")).add(b.newAppenderRef("AsyncPlain"))
                .add(b.newAppenderRef("AsyncTwo")).add(b.newAppenderRef("AsyncRolling"));
        if (mapped) {
            rootLogger.add(b.newAppenderRef("Mapped"));
        }
        b.add(rootLogger);
        return b.build();
    }

    @BeforeEach
    void start() {
        context = Configurator.initialize(file());
        adapter = new Log4j2LoggingAdapter(context, "2.20.0");
    }

    @AfterEach
    void tearDown() {
        adapter.detach();
        context.stop();
    }

    private HandlerDiagnostics of(String name) {
        return adapter.handlerDiagnostics(new HandlerRef(name));
    }

    @Test
    void aConsoleIsAConsole_withNothingToFlag() {
        HandlerDiagnostics facts = of("Con");
        assertTrue(facts.isConsole());
        assertFalse(facts.isPersistent());
        assertNull(facts.autoFlush(), "a console has no file to flush");
    }

    @Test
    void aPlainFileHasAKnownPath_noCaps_andItsFlush() {
        HandlerDiagnostics facts = of("Plain");
        assertEquals(dir.resolve("plain.log"), facts.targetPath());
        assertTrue(facts.hasKnownFile());
        assertNull(facts.maxFileSizeBytes());
        assertNull(facts.backupCount());
        assertEquals(Boolean.TRUE, facts.autoFlush(), "Log4j's default");
        assertEquals(Boolean.FALSE, of("Random").autoFlush());
        assertEquals(dir.resolve("random.log"), of("Random").targetPath());
    }

    @Test
    void aRollingFile_isPersistentWithTheFileUnknown() {
        HandlerDiagnostics facts = of("Rolling");
        assertTrue(facts.isPersistent());
        assertFalse(facts.hasKnownFile(), "the file checks leave it alone (D7-1)");
        assertNull(facts.autoFlush(), "not reported for a rolling appender: it would warn on every default config");
    }

    @Test
    void anAsyncOverOneAppender_answersWithThatOne_exceptTheFlush() {
        HandlerDiagnostics facts = of("AsyncPlain");
        assertEquals(dir.resolve("plain.log"), facts.targetPath());
        assertNull(facts.autoFlush(), "an <Async> appender flushes per batch");
        assertFalse(of("AsyncRolling").hasKnownFile());
        assertTrue(of("AsyncRolling").isPersistent());
        assertEquals(HandlerDiagnostics.EMPTY, of("AsyncTwo"), "several behind it: no facts");
    }

    @Test
    void anUnknownHandler_hasNoFacts() {
        assertEquals(HandlerDiagnostics.EMPTY, of("Nope"));
    }

    @Test
    void aTrimWrapperInTheSlot_makesNoDifference() {
        var gate = new RuleGate() {
            public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) { throw new AssertionError(); }
            public GateVerdict evaluateOnce(RuleCandidateEvent event) { throw new AssertionError(); }
            public boolean appliesTo(String loggerName) { return true; }
            public TrimDecision trimChoice(RuleCandidateEvent event) { return null; }
        };
        adapter.installTrimRendering(gate);
        assertEquals(dir.resolve("plain.log"), of("Plain").targetPath());
    }

    @Test
    void trimOffOnThisLog4j_isAnInfoFinding_andOnIsNone() {
        var gate = new RuleGate() {
            public GateVerdict evaluate(Object recordIdentity, RuleCandidateEvent event) { throw new AssertionError(); }
            public GateVerdict evaluateOnce(RuleCandidateEvent event) { throw new AssertionError(); }
            public boolean appliesTo(String loggerName) { return true; }
            public TrimDecision trimChoice(RuleCandidateEvent event) { return null; }
        };
        assertEquals(List.of(), adapter.doctorFindings(), "nothing before the probe has run");
        adapter.installTrimRendering(gate);
        boolean off = TrimProbe.problem(context.getConfiguration()) != null;
        List<DoctorFinding> findings = adapter.doctorFindings();
        if (off) {
            assertEquals(1, findings.size());
            assertEquals("log4j2.trim", findings.get(0).check());
            assertEquals(Severity.INFO, findings.get(0).severity());
        } else {
            assertEquals(List.of(), findings);
        }
    }

    @Test
    void aMemoryMappedFile_isAFileWithAKnownPath_andReportsItsOwnFlush() throws Exception {
        // Replace the default (unmapped) context with one that includes a MemoryMappedFile.
        // On Windows the MappedByteBuffer keeps the file locked, so we place it in a
        // throwaway directory that we don't attempt to delete.
        adapter.detach();
        context.stop();
        Path mappedDir = java.nio.file.Files.createTempDirectory("logaperature-mapped-");
        try {
            context = Configurator.initialize(file(mappedDir, true));
            adapter = new Log4j2LoggingAdapter(context, "2.20.0");
            HandlerDiagnostics facts = of("Mapped");
            assertEquals(mappedDir.resolve("mapped.log"), facts.targetPath());
            assertTrue(facts.hasKnownFile());
            assertEquals(Boolean.FALSE, facts.autoFlush(), "memory-mapped writes are not flushed per record");
        } finally {
            adapter.detach();
            context.stop();
            // Re-create the default context for @AfterEach.
            context = Configurator.initialize(file());
            adapter = new Log4j2LoggingAdapter(context, "2.20.0");
        }
    }

    @Test
    void aFileNameThePlatformRejects_givesNoPath_notAnException() {
        assertNull(Log4j2LoggingAdapter.pathOrNull("bad\0name.log"), "an embedded NUL is rejected everywhere");
        assertEquals(dir.resolve("ok.log"), Log4j2LoggingAdapter.pathOrNull(dir.resolve("ok.log").toString()));
    }
}
