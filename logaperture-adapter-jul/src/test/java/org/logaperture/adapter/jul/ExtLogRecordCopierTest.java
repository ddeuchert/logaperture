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

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@code org.jboss.logmanager.ExtLogRecord} copy succeeding is
 * exercised only by {@code WildFlyContainerIT} against a real server --
 * this module carries no compile-time or test-time JBoss LogManager
 * dependency (doc/specs/trim-rule.md "Open decisions for sign-off" #5).
 * This test covers the fail-open path every plain {@link LogRecord} takes.
 */
class ExtLogRecordCopierTest {

    @Test
    void copy_plainJulLogRecord_isEmpty() {
        LogRecord record = new LogRecord(Level.INFO, "hello");

        Optional<LogRecord> copy = ExtLogRecordCopier.copy(record);

        assertTrue(copy.isEmpty());
    }
}
