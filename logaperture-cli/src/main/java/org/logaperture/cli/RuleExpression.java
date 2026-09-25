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
package org.logaperture.cli;

import org.logaperture.api.Level;
import org.logaperture.control.jmx.RuleData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A rule's definition as the {@code add rule drop|trim} options that recreate it -- the {@code
 * EXPRESSION} column of {@code logctl list rules --verbose} and the {@code expression} JSON field
 * (doc/specs/list-rules-verbose.md). Fixed option order, every value explicit, shell-safe quoting,
 * so the same rule always renders the same text.
 */
final class RuleExpression {

    /** Values made only of these characters are left unquoted -- nothing a POSIX shell would expand or split. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._\\-:/,+@%]+");

    private RuleExpression() {
    }

    static String of(RuleData rule) {
        List<String> parts = new ArrayList<>();
        if (rule.getMessageContains() != null) {
            parts.add((rule.isMessageIgnoreCase() ? "--message-contains-ignore-case " : "--message-contains ")
                    + quote(rule.getMessageContains()));
        }
        if (rule.getThrowableType() != null) {
            parts.add("--throwable " + quote(rule.getThrowableType()));
        }
        if (rule.getThrowableMessageContains() != null) {
            parts.add("--throwable-message-contains " + quote(rule.getThrowableMessageContains()));
        }
        if (rule.isAnyCause()) {
            parts.add("--any-cause");
        }
        if (rule.getLevelAtMost() != null) {
            parts.add("--below " + belowFor(rule.getLevelAtMost()));
        }
        if (Boolean.TRUE.equals(rule.getSampleFullEnabled()) && rule.getSampleFullEveryMillis() != null) {
            parts.add("--sample-full " + duration(rule.getSampleFullEveryMillis()));
        } else if (Boolean.FALSE.equals(rule.getSampleFullEnabled())) {
            parts.add("--no-sample-full");
        }
        if (rule.getFrames() != null) {
            parts.add("--frames " + rule.getFrames());
        }
        if (Boolean.TRUE.equals(rule.getCollapseCauses())) {
            parts.add("--collapse-causes");
        }
        return String.join(" ", parts);
    }

    /**
     * The {@code --below} bound for a stored "at most" level -- the inverse of {@code Parser}'s
     * {@code parseBelowLevel}: the next level up, spelled {@code FATAL} above {@code ERROR}.
     */
    static String belowFor(String levelAtMost) {
        Level next = Level.values()[Level.valueOf(levelAtMost).ordinal() + 1];
        return next == Level.OFF ? "FATAL" : next.name();
    }

    /** Milliseconds in the largest whole unit {@code Durations.parse} accepts ({@code d}/{@code h}/{@code m}/{@code s}). */
    static String duration(long millis) {
        long seconds = millis / 1000;
        if (seconds * 1000 != millis || seconds == 0) {
            return seconds + 1 + "s"; // not a whole second: round up -- no sub-second unit exists
        }
        if (seconds % 86_400 == 0) {
            return seconds / 86_400 + "d";
        }
        if (seconds % 3_600 == 0) {
            return seconds / 3_600 + "h";
        }
        if (seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }

    static String quote(String value) {
        if (SAFE.matcher(value).matches()) {
            return value;
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\' || c == '$' || c == '`') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
