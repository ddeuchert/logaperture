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
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.logaperture.core.TrimDecision;
import org.logaperture.core.TrimmedThrowables;

/**
 * Whether this Log4j can render a trimmed event correctly (doc/specs/log4j2-adapter.md A4): renders one
 * through {@code %ex} and {@code %xEx} and checks the first line is the trimmed throwable's text and not a
 * synthetic class name. Log4j 2.25.0 to 2.25.3 render both from the throwable's class with no hook, so
 * they fail it, and trim is then off for that adapter (the same fail-safe as the JUL adapter's no-op on a
 * structured formatter, #83). Asking the running Log4j, not a version table, also covers a version this
 * was never tried on.
 */
final class TrimProbe {

    private TrimProbe() {
    }

    /** {@code null} if trim renders correctly; otherwise why it does not. */
    static String problem(Configuration configuration) {
        try {
            Throwable original = new IllegalStateException("probe");
            Throwable trimmed = TrimmedThrowables.build(original, new TrimDecision("probe", 1, false));
            String expected = trimmed.toString();
            LogEvent event = new Log4jLogEvent.Builder().setLevel(Level.ERROR).setLoggerName("probe")
                    .setMessage(new SimpleMessage("probe")).setThrown(trimmed)
                    .setThrownProxy(new TrimmedProxy(trimmed)).build();
            for (String pattern : new String[] {"%ex", "%xEx"}) {
                PatternLayout layout = PatternLayout.newBuilder().withPattern(pattern)
                        .withConfiguration(configuration).build();
                String rendered = layout.toSerializable(event);
                String firstLine = rendered.lines().findFirst().orElse("");
                if (!firstLine.equals(expected)) {
                    return "this Log4j renders a trimmed exception as '" + firstLine + "' with " + pattern;
                }
            }
            return null;
        } catch (RuntimeException | LinkageError e) {
            return "the trim could not be tried on this Log4j (" + e + ")";
        }
    }
}
