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
package org.logaperture.adapter.jul;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.ExtLogRecordSubclasses;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/drop-rule.md "Divergence from prior specs" #3 (issue #173). The JBoss LogManager
 * cases use the test-only stand-in {@link ExtLogRecord}, under the real class name.
 */
class FormattedMessagesTest {

    @Test
    void plainJulRecord_fillsInItsBraceParameters() {
        LogRecord record = new LogRecord(Level.INFO, "Matched default handler path {0}");
        record.setParameters(new Object[] {"/health"});

        assertEquals("Matched default handler path /health", FormattedMessages.of(record));
    }

    @Test
    void plainJulRecord_withoutParameters_isItsMessage() {
        assertEquals("path %s", FormattedMessages.of(new LogRecord(Level.INFO, "path %s")));
    }

    @Test
    void recordWithNoMessage_isEmpty_notNull() {
        assertEquals("", FormattedMessages.of(new LogRecord(Level.INFO, null)));
    }

    @Test
    void jbossLogManagerRecord_fillsInItsPrintfParameters() {
        ExtLogRecord record = new ExtLogRecord(Level.INFO, "Matched default handler path %s", "/health");

        assertEquals("Matched default handler path /health", FormattedMessages.of(record));
    }

    @Test
    void jbossLogManagerRecordSubclass_formatsItselfToo() {
        ExtLogRecord record = new ExtLogRecord(Level.INFO, "path %s", "/health") { };

        assertEquals("path /health", FormattedMessages.of(record));
    }

    /** Code review on #174: an override in a class this package can't access is still called. */
    @Test
    void nonPublicJbossLogManagerRecordSubclass_overridingTheFormat_isCalled() {
        ExtLogRecord record = ExtLogRecordSubclasses.overridingFormat(Level.INFO, "path %s", "/health");

        assertEquals("overridden path /health", FormattedMessages.of(record));
    }

    @Test
    void jbossLogManagerRecordThatFailsToFormat_fallsBackToJulsSubstitution() {
        boolean[] called = {false};
        ExtLogRecord record = ExtLogRecordSubclasses.failingFormat(called, Level.INFO, "path {0}", "/health");

        assertEquals("path /health", FormattedMessages.of(record));
        assertTrue(called[0], "the fallback follows a failed format, not a failed lookup");
    }
}
