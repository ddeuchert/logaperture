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

import java.util.List;

/**
 * Thrown by {@code setLevel} when {@code target} is a leading-star pattern
 * (doc/specs/pattern-selection-semantics.md) and {@code options.confirmed()}
 * is {@code false} — never mutates anything. (A trailing-star target never
 * reaches this at all — {@code setLevel} rejects that shape outright,
 * before confirmation is even evaluated.) Crosses the JMX boundary the same way
 * {@link CapabilityDeniedException} already does (unwrapped back to itself
 * by {@code JMX.newMXBeanProxy} for a typed caller, per that spec's Decision
 * #2a) rather than as a "successful" return value a careless caller could
 * mistake for an applied mutation.
 *
 * <p>{@code logctl} never actually triggers this in normal operation — it
 * resolves its own preview via {@code listLoggers} and always passes {@code
 * confirmed = true} by the time it calls {@code setLevel} for real (a user
 * "y" or {@code --yes}). This exception is the safety net for every other
 * caller, JMX-direct included, per top-level §8.1's "no surface is a
 * privileged path" — and for {@code logctl} itself, a defensive
 * belt-and-suspenders for a race between its preview and its apply call.
 */
public final class ConfirmationRequiredException extends RuntimeException {

    private final String pattern;
    private final List<String> matches;

    public ConfirmationRequiredException(String pattern, List<String> matches) {
        super(message(pattern, matches));
        this.pattern = pattern;
        this.matches = List.copyOf(matches);
    }

    private static String message(String pattern, List<String> matches) {
        return matches.size() + " match(es) for '" + pattern + "': " + matches
                + ". Re-invoke with confirmed=true to apply.";
    }

    /** The pattern {@code setLevel} was called with. */
    public String pattern() {
        return pattern;
    }

    /** Every currently-known logger name {@code pattern} resolved to. */
    public List<String> matches() {
        return matches;
    }
}
