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

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one {@code <duration>} grammar (doc/specs/cli-transport.md): an integer and one unit suffix,
 * no spaces, no punctuation -- {@code 30m}, {@code 2h}, {@code 90s}, {@code 1d}. Shared by {@code
 * logctl}, the vendor defaults file and {@code -Dlogaperture.drop.summaryInterval}, so the three
 * can't drift apart; each caller words its own error from {@link Invalid#problem()}. {@link
 * RuleExpression#duration(long)} is the formatting counterpart.
 */
public final class DurationSyntax {

    private static final Pattern SYNTAX = Pattern.compile("(\\d+)([smhd])");

    /** Why a token isn't a usable duration. */
    public enum Problem {
        /** Not {@code <n><unit>} at all. */
        SYNTAX,
        /** Well-formed, but too large for a {@link Duration}. */
        OUT_OF_RANGE,
        /** Well-formed, but zero -- never a meaningful duration here. */
        ZERO
    }

    /** Thrown by {@link #parse} for a token that isn't a positive duration. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient Problem problem;

        Invalid(Problem problem, String token) {
            super(problem + ": '" + token + "'");
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    private DurationSyntax() {
    }

    /**
     * @throws Invalid if {@code token} isn't a positive {@code <n>s}, {@code <n>m}, {@code <n>h} or
     *                 {@code <n>d}
     */
    public static Duration parse(String token) {
        Matcher m = SYNTAX.matcher(token);
        if (!m.matches()) {
            throw new Invalid(Problem.SYNTAX, token);
        }
        long value;
        try {
            value = Long.parseLong(m.group(1));
        } catch (NumberFormatException overflow) {
            throw new Invalid(Problem.OUT_OF_RANGE, token);
        }
        if (value == 0) {
            throw new Invalid(Problem.ZERO, token);
        }
        try {
            return switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                case "h" -> Duration.ofHours(value);
                case "d" -> Duration.ofDays(value);
                default -> throw new AssertionError("unit regex admitted an unexpected suffix");
            };
        } catch (ArithmeticException overflow) {
            throw new Invalid(Problem.OUT_OF_RANGE, token);
        }
    }
}
