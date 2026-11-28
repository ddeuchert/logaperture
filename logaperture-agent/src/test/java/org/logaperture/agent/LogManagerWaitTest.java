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
import org.logaperture.bridge.Diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Consumer<Runnable> publication = LogManagerWait.publication(false, "none", null);

        publication.accept(() -> ran.add("published"));

        assertEquals(List.of("published"), ran);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
        assertEquals("", said());
    }

    @Test
    void onWildFlyAndQuarkus_theArgumentIsIgnoredWithAWarning() {
        for (String container : List.of("wildfly", "quarkus")) {
            LogManagerWait.publication(true, container, null).accept(() -> ran.add(container));

            assertTrue(said().contains("ignoring --wait-for-log-manager") && said().contains(container), said());
        }
        assertEquals(List.of("wildfly", "quarkus"), ran);
        assertNull(System.getProperty(LogManagerWait.WAITING_PROPERTY));
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

    private String said() {
        return said.toString(StandardCharsets.UTF_8);
    }
}
