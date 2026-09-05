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

import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.PersistenceTier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code quote}/{@code unquote} must escape {@code \n}/{@code \r}, not just
 * backslash and double-quote -- a code-review finding against this PR: an
 * unescaped newline in a free-text {@code reason} used to split one logical
 * record across physical lines and corrupt every record after it in this
 * line-oriented format. Also covers the {@code handlerOverrides:} section
 * (doc/specs/handler-floor-control.md "Data model") and version-1 files
 * that predate it.
 */
class StateFileFormatTest {

    @Test
    void roundTrips_aReasonContainingNewlinesAndCarriageReturns() {
        LevelOverride withMultilineReason = new LevelOverride(
                "com.acme.Worker", Level.DEBUG, false, "line one\nline two\r\nline three",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);

        String content = StateFileFormat.write(List.of(withMultilineReason), List.of());
        StateFileFormat.Parsed parsed = StateFileFormat.parse(content);

        assertEquals(1, parsed.overrides().size());
        assertEquals(withMultilineReason, parsed.overrides().get(0));
    }

    @Test
    void roundTrips_aSecondRecordAfterAMultilineReason() {
        LevelOverride first = new LevelOverride(
                "com.acme.First", Level.DEBUG, false, "has a\nnewline",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);
        LevelOverride second = new LevelOverride(
                "com.acme.Second", Level.WARN, false, "plain reason",
                Instant.parse("2026-08-21T04:00:00Z"), "jmx", PersistenceTier.STICKY, null);

        StateFileFormat.Parsed parsed =
                StateFileFormat.parse(StateFileFormat.write(List.of(first, second), List.of()));

        // The bug this guards against: a raw embedded newline used to shift
        // every subsequent line, corrupting (or losing) records after it.
        assertEquals(2, parsed.overrides().size());
        assertEquals(first, parsed.overrides().get(0));
        assertEquals(second, parsed.overrides().get(1));
    }

    @Test
    void roundTrips_backslashesAndQuotesInReason() {
        LevelOverride override = new LevelOverride(
                "com.acme.Worker", Level.DEBUG, false, "a \"quoted\" path C:\\logs",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);

        StateFileFormat.Parsed parsed = StateFileFormat.parse(StateFileFormat.write(List.of(override), List.of()));

        assertEquals(override, parsed.overrides().get(0));
    }

    @Test
    void roundTrips_handlerOverridesAlongsideLoggerOverrides() {
        LevelOverride logger = new LevelOverride(
                "com.acme.Worker", Level.DEBUG, false, "why",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);
        HandlerLevelOverride handler = new HandlerLevelOverride(
                new HandlerRef("CONSOLE"), Level.TRACE, "why not",
                Instant.parse("2026-08-21T03:15:00Z"), "jmx", PersistenceTier.FOR,
                Instant.parse("2026-08-21T03:45:00Z"));

        StateFileFormat.Parsed parsed =
                StateFileFormat.parse(StateFileFormat.write(List.of(logger), List.of(handler)));

        assertEquals(List.of(logger), parsed.overrides());
        assertEquals(List.of(handler), parsed.handlerOverrides());
    }

    @Test
    void parse_aVersion1FileWithNoHandlerSection_yieldsAnEmptyHandlerList() {
        String v1 = "schemaVersion: 1\n"
                + "overrides:\n"
                + "  - loggerName: \"com.acme.Worker\"\n"
                + "    level: DEBUG\n"
                + "    includeChildren: false\n"
                + "    reason: null\n"
                + "    appliedAt: 2026-08-21T03:14:02Z\n"
                + "    source: \"jmx\"\n"
                + "    tier: STICKY\n"
                + "    expiresAt: null\n";

        StateFileFormat.Parsed parsed = StateFileFormat.parse(v1);

        assertEquals(1, parsed.overrides().size());
        assertEquals(List.of(), parsed.handlerOverrides());
    }
}
