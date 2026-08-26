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

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code <duration>} grammar from doc/specs/cli-transport.md: an
 * integer and one unit suffix, no spaces, no punctuation — {@code 30m},
 * {@code 2h}, {@code 90s}, {@code 1d}. Zero and bare integers are usage
 * errors, matching {@code SetLevelOptions}'s "FOR requires a positive
 * expiresIn" validation on the far side.
 */
final class Durations {

    private static final Pattern SYNTAX = Pattern.compile("(\\d+)([smhd])");

    private Durations() {
    }

    static Duration parse(String token) {
        Matcher m = SYNTAX.matcher(token);
        if (!m.matches()) {
            throw new CliError(CliError.USAGE,
                    "Unparseable duration '" + token + "' — expected <n>s, <n>m, <n>h or <n>d, e.g. 30m.");
        }
        long value;
        try {
            value = Long.parseLong(m.group(1));
        } catch (NumberFormatException overflow) {
            throw new CliError(CliError.USAGE, "Duration '" + token + "' is out of range.");
        }
        if (value == 0) {
            throw new CliError(CliError.USAGE, "A duration must be greater than zero.");
        }
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            case "h" -> Duration.ofHours(value);
            case "d" -> Duration.ofDays(value);
            default -> throw new AssertionError("unit regex admitted an unexpected suffix");
        };
    }
}
