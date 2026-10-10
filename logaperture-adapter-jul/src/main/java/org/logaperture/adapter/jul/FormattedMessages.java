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

import java.lang.reflect.Method;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A record's message with its parameters filled in, as its handler would write it -- what a
 * rule's message matcher tests (doc/specs/drop-rule.md "Divergence from prior specs" #3) and
 * what a storm's sample shows (doc/specs/storm-detection.md, issue #169).
 *
 * <p>JBoss Logging's {@code debugf}/{@code infof} and message loggers produce printf-style
 * records, which JUL's {@link Formatter#formatMessage} leaves alone (it substitutes only {@code
 * {0}}). A JBoss LogManager record formats itself, and caches the result on the record, so the
 * handler that writes it later doesn't format it again: {@code ExtLogRecord.getFormattedMessage()},
 * reached by reflection since this adapter has no compile-time JBoss LogManager dependency (as in
 * {@link ExtLogRecordCopier}). The {@code Method} is looked up once per record class.
 */
final class FormattedMessages {

    private static final String EXT_LOG_RECORD_CLASS_NAME = "org.jboss.logmanager.ExtLogRecord";

    /** Stateless: JUL's own substitution, for a record that can't format itself. */
    private static final Formatter MESSAGE_FORMATTER = new SimpleFormatter();

    /**
     * {@code getFormattedMessage()} for an {@code ExtLogRecord} class, {@code null} for any other.
     * Looked up on {@code ExtLogRecord} itself, not the record's own class: invoking it still
     * dispatches to a subclass's override, and access is checked against the public declaring
     * class, so a non-public subclass that overrides it doesn't fail every call.
     */
    private static final ClassValue<Method> FORMATTED_MESSAGE_METHOD = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> recordClass) {
            Class<?> extLogRecord = extLogRecordClass(recordClass);
            if (extLogRecord == null) {
                return null;
            }
            try {
                Method method = extLogRecord.getMethod("getFormattedMessage");
                return method.getReturnType() == String.class ? method : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    };

    private FormattedMessages() {
    }

    /** Never {@code null}: {@code ""} for a record with no message. */
    static String of(LogRecord record) {
        Method formattedMessage = FORMATTED_MESSAGE_METHOD.get(record.getClass());
        if (formattedMessage != null) {
            try {
                Object formatted = formattedMessage.invoke(record);
                if (formatted != null) {
                    return (String) formatted;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall back to JUL's own substitution below
            }
        }
        String formatted = MESSAGE_FORMATTER.formatMessage(record);
        return formatted != null ? formatted : "";
    }

    /** {@code ExtLogRecord} among {@code recordClass} and its superclasses, or {@code null}. */
    private static Class<?> extLogRecordClass(Class<?> recordClass) {
        for (Class<?> c = recordClass; c != null; c = c.getSuperclass()) {
            if (EXT_LOG_RECORD_CLASS_NAME.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
}
