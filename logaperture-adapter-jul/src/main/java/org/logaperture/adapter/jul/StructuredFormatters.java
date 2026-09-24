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

import java.util.logging.Formatter;

/**
 * Detects a structured (JSON/XML) {@link Formatter} by class name --
 * doc/specs/trim-rule.md "Text formatters only". Trim applies to text
 * formatters only in this slice; a structured formatter is left entirely
 * untouched rather than risk the spike's observed defect (a synthetic
 * throwable's class reported as {@code exceptionType}, the real message
 * dropped -- doc/spikes/rule-pipeline.md result #3). This adapter has no
 * compile-time dependency on the concrete JBoss LogManager formatter
 * classes ({@link JulLoggingAdapter}'s class doc), so this matches by
 * simple class name rather than {@code instanceof} or exact FQN --
 * deliberately broad (any {@code *JsonFormatter}/{@code *XmlFormatter}),
 * the same tolerant-by-name convention {@link ExtLogRecordCopier} already
 * uses for {@code ExtLogRecord} itself.
 */
final class StructuredFormatters {

    private StructuredFormatters() {
    }

    static boolean isStructured(Formatter formatter) {
        if (formatter == null) {
            return false;
        }
        String simpleName = formatter.getClass().getSimpleName();
        return simpleName.endsWith("JsonFormatter") || simpleName.endsWith("XmlFormatter");
    }
}
