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
package org.logaperture.core;

/**
 * {@code listLoggers}' filter matching (doc/specs/level-control.md:
 * "{@code filter} is a name prefix or glob"). Deliberately a loose display
 * filter, not the dot-boundary-aware hierarchy logic in {@link
 * LoggerHierarchy} — {@code "com.ac"} matches {@code "com.acme"} here,
 * which would be wrong for {@code includeChildren} fan-out but is fine for
 * "show me what I typed the start of."
 */
final class NameFilter {

    private NameFilter() {
    }

    static boolean matches(String filter, String loggerName) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (filter.indexOf('*') < 0 && filter.indexOf('?') < 0) {
            return loggerName.startsWith(filter);
        }
        return loggerName.matches(globToRegex(filter));
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '\\', '^', '$', '|', '(', ')', '[', ']', '{', '}', '+' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.toString();
    }
}
