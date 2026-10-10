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

/**
 * {@link ExtLogRecord} subclasses that override {@code getFormattedMessage()} in classes the
 * adapter's package can't access, as an application's or a framework's own record type might.
 */
public final class ExtLogRecordSubclasses {

    private ExtLogRecordSubclasses() {
    }

    /** A record whose formatted message is prefixed with {@code "overridden "}. */
    public static ExtLogRecord overridingFormat(Level level, String template, Object... parameters) {
        return new Overriding(level, template, parameters);
    }

    /** A record whose {@code getFormattedMessage()} throws, after setting {@code called[0]}. */
    public static ExtLogRecord failingFormat(boolean[] called, Level level, String template, Object... parameters) {
        return new Failing(called, level, template, parameters);
    }

    private static final class Overriding extends ExtLogRecord {
        Overriding(Level level, String template, Object... parameters) {
            super(level, template, parameters);
        }

        @Override
        public String getFormattedMessage() {
            return "overridden " + super.getFormattedMessage();
        }
    }

    private static final class Failing extends ExtLogRecord {
        private final boolean[] called;

        Failing(boolean[] called, Level level, String template, Object... parameters) {
            super(level, template, parameters);
            this.called = called;
        }

        @Override
        public String getFormattedMessage() {
            called[0] = true;
            throw new IllegalStateException("simulated");
        }
    }
}
