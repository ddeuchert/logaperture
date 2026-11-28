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
package org.logaperture.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.logaperture.bridge.DiagnosticLevel;
import org.logaperture.adapter.logback.LogbackAdapterFactory;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.container.none.NoneContainer;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.LibraryRecipeScanner;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.startup.EngineStartup;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/wait-for-log-manager.md: what is held, what runs, and what the JVM says about itself. */
class LogManagerWaitTest {

    private final ByteArrayOutputStream said = new ByteArrayOutputStream();
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void captureDiagnostics() {
        Diagnostics.configure(new PrintStream(said, true, StandardCharsets.UTF_8), DiagnosticLevel.WARN);
    }

    @AfterEach
    void restore() {
        Diagnostics.resetToDefault();
        System.clearProperty(LogManagerWait.WAITING_PROPERTY);
    }

    @Test
    void withoutTheArgument_publishingRunsAtOnce_andNothingIsMarked() {
        LogManagerWait publication = LogManagerWait.create(false, "none", false);

        publication.accept(() -> ran.add("published"));
        publication.watch(null); // nothing to look for

        assertEquals(List.of("published"), ran);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
        assertEquals("", said());
    }

    @Test
    void onWildFlyAndQuarkus_theArgumentIsIgnoredWithAWarning() {
        for (String container : List.of("wildfly", "quarkus")) {
            LogManagerWait.create(true, container, false).accept(() -> ran.add(container));

            assertTrue(said().contains("ignoring --wait-for-log-manager") && said().contains(container), said());
        }
        assertEquals(List.of("wildfly", "quarkus"), ran);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
    }

    @Test
    void givenToAnAttach_theArgumentIsIgnoredWithAWarning() {
        LogManagerWait.create(true, "none", true).accept(() -> ran.add("published"));

        assertEquals(List.of("published"), ran);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
        assertTrue(said().contains("it is for a -javaagent start, not an attach"), said());
    }

    @Test
    void onAPlainJvmStartedWithTheAgent_theWaitIsOn() {
        LogManagerWait.create(true, "none", false).accept(() -> ran.add("published"));

        assertEquals(List.of(), ran);
        assertNotNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
    }

    @Test
    void whatIsHandedInDuringTheWaitRunsWhenItEnds_inOrder_whileStillMarkedAsWaiting() {
        LogManagerWait wait = new LogManagerWait();
        wait.begin();
        List<String> markedWhileRunning = new ArrayList<>();
        Runnable first = () -> {
            ran.add("first");
            markedWhileRunning.add(System.getProperty(LogManagerWait.WAITING_PROPERTY));
        };
        wait.accept(first);
        wait.accept(() -> ran.add("second"));
        assertEquals(List.of(), ran);
        assertNotNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));

        wait.end();

        assertEquals(List.of("first", "second"), ran);
        assertEquals(List.of("java.util.logging"), markedWhileRunning);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
    }

    @Test
    void afterTheWait_publishingRunsAtOnce() {
        LogManagerWait wait = new LogManagerWait();
        wait.begin();
        wait.end(); // JUL in use before any context was ready

        wait.accept(() -> ran.add("published"));

        assertEquals(List.of("published"), ran);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
    }

    @Test
    void aWaitStillOnWhenTheReminderIsDueIsReportedOnce() throws InterruptedException {
        LogManagerWait wait = new LogManagerWait();

        wait.remindAfter(1).join();

        assertTrue(said().contains("still waiting for java.util.logging"), said());
    }

    @Test
    void aWaitThatHasEndedIsNotReported() throws InterruptedException {
        LogManagerWait wait = new LogManagerWait();
        wait.end();

        wait.remindAfter(1).join();

        assertEquals("", said());
    }

    /** A held step for a framework that couldn't be bound asks about contexts when it runs, not when it was held. */
    @Test
    void aFailedBindHeldByTheWait_doesNotSayThereIsNoContext_whenOneWasInstalledMeanwhile() {
        LogManagerWait wait = new LogManagerWait();
        wait.begin();
        Consumer<AggregateLevelControl> unbound = EngineStartup.onSurfaceNeeded("none", EngineStartup.Delivery.AGENT,
                CapabilityPolicy.allowAll(), VendorDefaults.none(), LibraryRecipeScanner.none(), wait);
        try (NoneContainer container = new NoneContainer(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            unbound.accept(container.operations());
            container.installContext(
                    ContextHandle.of(ContextHandle.SYSTEM, "none", LogbackAdapterFactory.forCurrentContext()));

            wait.end();

            assertTrue(said().contains("active (JVM)"), said());
            assertFalse(said().contains("no logging context yet"), said());
        } finally {
            EngineStartup.withdrawControlSurface();
        }
    }

    private String said() {
        return said.toString(StandardCharsets.UTF_8);
    }
}
