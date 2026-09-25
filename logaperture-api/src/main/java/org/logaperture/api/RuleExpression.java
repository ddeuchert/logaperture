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
package org.logaperture.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A rule's definition as the {@code add rule drop|trim} options that recreate it -- the {@code
 * EXPRESSION} column of {@code logctl list rules --verbose}, the {@code expression} JSON field
 * (doc/specs/list-rules-verbose.md), and the previous/new value of an {@code alter rule} audit
 * record (doc/specs/alter-rule.md "Audit"). Fixed option order, every value explicit, shell-safe
 * quoting, so the same rule always renders the same text. Lives in {@code api} so the agent and
 * {@code logctl} render identically.
 */
public final class RuleExpression {

    /** Values made only of these characters are left unquoted -- nothing a POSIX shell would expand or split. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._\\-:/,+@%]+");

    private RuleExpression() {
    }

    /** A {@link Drop} or {@link Trim}; any other rule type renders only its matchers. */
    public static String of(LogRule rule) {
        SampleFullPolicy sampleFull = rule instanceof Drop drop ? drop.sampleFull() : null;
        Integer frames = rule instanceof Trim trim ? trim.frames() : null;
        Boolean collapseCauses = rule instanceof Trim trim ? trim.collapseCauses() : null;
        return of(rule.matchers(), sampleFull, frames, collapseCauses);
    }

    /**
     * @param sampleFull     a drop's sampling, {@code null} for any other action
     * @param frames         a trim's frames, {@code null} for any other action
     * @param collapseCauses a trim's cause collapsing, {@code null} for any other action
     */
    public static String of(CompiledMatchers matchers, SampleFullPolicy sampleFull, Integer frames,
            Boolean collapseCauses) {
        List<String> parts = new ArrayList<>();
        if (matchers.messageContains() != null) {
            parts.add((matchers.messageIgnoreCase() ? "--message-contains-ignore-case " : "--message-contains ")
                    + quote(matchers.messageContains()));
        }
        if (matchers.throwableType() != null) {
            parts.add("--throwable " + quote(matchers.throwableType()));
        }
        if (matchers.throwableMessageContains() != null) {
            parts.add("--throwable-message-contains " + quote(matchers.throwableMessageContains()));
        }
        if (matchers.anyCause()) {
            parts.add("--any-cause");
        }
        if (matchers.levelAtMost() != null) {
            parts.add("--below " + belowFor(matchers.levelAtMost()));
        }
        if (sampleFull != null) {
            parts.add(sampleFull.enabled() ? "--sample-full " + duration(sampleFull.every().toMillis())
                    : "--no-sample-full");
        }
        if (frames != null) {
            parts.add("--frames " + frames);
        }
        if (Boolean.TRUE.equals(collapseCauses)) {
            parts.add("--collapse-causes");
        }
        return String.join(" ", parts);
    }

    /**
     * The {@code --below} bound for a stored "at most" level -- the inverse of {@code logctl}'s
     * {@code --below} parsing: the next level up, spelled {@code FATAL} above {@code ERROR}.
     */
    public static String belowFor(Level levelAtMost) {
        Level next = Level.values()[levelAtMost.ordinal() + 1];
        return next == Level.OFF ? "FATAL" : next.name();
    }

    /** Milliseconds in the largest whole unit {@code logctl} accepts ({@code d}/{@code h}/{@code m}/{@code s}). */
    public static String duration(long millis) {
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

    public static String quote(String value) {
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
