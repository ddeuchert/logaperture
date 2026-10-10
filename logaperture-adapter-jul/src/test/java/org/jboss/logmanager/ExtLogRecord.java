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
package org.jboss.logmanager;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * A stand-in for JBoss LogManager's record, under its real class name, for the adapter's
 * by-name reflective paths -- this module has no test-time JBoss LogManager dependency. Like the
 * real one's printf-style records, its message is a {@code %s} template that JUL's {@code
 * Formatter.formatMessage} leaves alone, and only {@link #getFormattedMessage()} fills in.
 */
public class ExtLogRecord extends LogRecord {

    public ExtLogRecord(Level level, String template, Object... parameters) {
        super(level, template);
        setParameters(parameters);
    }

    public String getFormattedMessage() {
        return String.format(getMessage(), getParameters());
    }
}
