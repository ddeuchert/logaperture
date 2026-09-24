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

import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.logging.LogRecord;

/**
 * Reflective access to JBoss LogManager's {@code
 * org.jboss.logmanager.ExtLogRecord} copy constructor -- doc/spikes/
 * rule-pipeline.md "Design consequences" #5. This adapter has no
 * compile-time dependency on JBoss LogManager ({@link JulLoggingAdapter}'s
 * class doc), so the one place trim needs a copy that doesn't mutate the
 * shared record (doc/specs/trim-rule.md "Evaluation") reaches it this way
 * instead. {@code ExtLogRecord.copyAll()} is a {@code void} in-place
 * snapshot, not a copy -- the real copy is the type's own copy constructor,
 * {@code new ExtLogRecord(ExtLogRecord)}.
 */
final class ExtLogRecordCopier {

    private static final String EXT_LOG_RECORD_CLASS_NAME = "org.jboss.logmanager.ExtLogRecord";

    private ExtLogRecordCopier() {
    }

    /**
     * @return a private copy of {@code record}, safe to mutate (e.g. {@code
     *         setThrown}) without affecting the shared record or any
     *         sibling handler's own formatting of it -- empty if {@code
     *         record} isn't a real {@code ExtLogRecord}, or the copy
     *         constructor isn't reachable. The caller is expected to fail
     *         open on an empty result (doc/spikes/rule-pipeline.md "Design
     *         consequences" #5: format the record untouched).
     */
    static Optional<LogRecord> copy(LogRecord record) {
        Class<?> actualClass = record.getClass();
        if (!EXT_LOG_RECORD_CLASS_NAME.equals(actualClass.getName())) {
            return Optional.empty();
        }
        try {
            Constructor<?> copyConstructor = actualClass.getDeclaredConstructor(actualClass);
            copyConstructor.setAccessible(true);
            return Optional.of((LogRecord) copyConstructor.newInstance(record));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
