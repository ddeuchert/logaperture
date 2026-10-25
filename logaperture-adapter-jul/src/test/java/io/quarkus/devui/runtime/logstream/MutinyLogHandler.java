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
package io.quarkus.devui.runtime.logstream;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Test stand-in with the real class's name and its one relevant behaviour: like Quarkus's Dev UI
 * log stream handler, it requires its formatter to be of its own type and fails otherwise
 * (doc/specs/quarkus-extension.md #5).
 */
public class MutinyLogHandler extends Handler {

    /** The type the real handler casts to ({@code JsonFormatter}). */
    public static final class OwnFormatter extends SimpleFormatter {
    }

    public MutinyLogHandler() {
        setFormatter(new OwnFormatter());
    }

    @Override
    public void publish(LogRecord record) {
        Formatter own = (OwnFormatter) getFormatter(); // ClassCastException if wrapped
        own.format(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
