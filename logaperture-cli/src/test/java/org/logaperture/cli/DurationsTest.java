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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationsTest {

    @Test
    void parsesEachUnit() {
        assertEquals(Duration.ofSeconds(90), Durations.parse("90s"));
        assertEquals(Duration.ofMinutes(30), Durations.parse("30m"));
        assertEquals(Duration.ofHours(2), Durations.parse("2h"));
        assertEquals(Duration.ofDays(1), Durations.parse("1d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0m", "-5m", "30", "30x", "m", "", " 30m", "1.5h", "1h30m"})
    void rejectsAnythingOutsideTheGrammar(String bad) {
        CliError error = assertThrows(CliError.class, () -> Durations.parse(bad));
        assertEquals(CliError.USAGE, error.exitCode());
    }
}
