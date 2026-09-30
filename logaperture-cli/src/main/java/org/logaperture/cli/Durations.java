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

import org.logaperture.api.DurationSyntax;

import java.time.Duration;

/**
 * The {@code <duration>} grammar from doc/specs/cli-transport.md ({@link DurationSyntax}), as
 * {@code logctl} usage errors. Zero and bare integers are usage errors, matching {@code
 * SetLevelOptions}'s "FOR requires a positive expiresIn" validation on the far side.
 */
final class Durations {

    private Durations() {
    }

    static Duration parse(String token) {
        try {
            return DurationSyntax.parse(token);
        } catch (DurationSyntax.Invalid e) {
            throw new CliError(CliError.USAGE, switch (e.problem()) {
                case SYNTAX -> "Unparseable duration '" + token + "' — expected <n>s, <n>m, <n>h or <n>d, e.g. 30m.";
                case OUT_OF_RANGE -> "Duration '" + token + "' is out of range.";
                case ZERO -> "A duration must be greater than zero.";
            });
        }
    }
}
