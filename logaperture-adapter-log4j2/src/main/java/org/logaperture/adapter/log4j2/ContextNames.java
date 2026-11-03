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

/**
 * The name a Log4j 2 context gets in {@code logctl list loggers} (doc/specs/log4j2-adapter.md A7).
 * Stable across a redeploy (TC3): a web application's context path when Log4j has one
 * ({@code log4j-web} names the context for it, {@code /appA}), else {@code log4j2} for the first
 * context in a Log4j copy, {@code log4j2-2} for the next. Never the class loader's identity hash.
 */
final class ContextNames {

    static final String DEFAULT = "log4j2";

    private ContextNames() {
    }

    /**
     * @param log4jName the name Log4j gave the context, possibly {@code null}
     * @param ordinal   1 for the first context found in this copy of Log4j
     */
    static String of(String log4jName, int ordinal) {
        if (log4jName != null && log4jName.startsWith("/") && log4jName.length() > 1) {
            return log4jName;
        }
        return ordinal <= 1 ? DEFAULT : DEFAULT + "-" + ordinal;
    }
}
