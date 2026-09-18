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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code quote}/{@code unquote} must escape {@code \n}/{@code \r}, not just
 * backslash and double-quote -- a code-review finding against this PR: an
 * unescaped newline in a free-text {@code reason} used to split one logical
 * record across physical lines and corrupt every record after it in this
 * line-oriented format. Also covers the {@code handlerOverrides:} section
 * (doc/specs/handler-floor-control.md "Data model") and older-schema files,
 * including a legacy {@code patternRules:} section (doc/specs/
 * pattern-selection-semantics.md "Persistence — state file schema").
 */
class StateFileFormatTest {

    @Test
    void roundTrips_aReasonContainingNewlinesAndCarriageReturns() {
        LevelOverride withMultilineReason = new LevelOverride(
                "com.acme.Worker", Level.DEBUG, "line one\nline two\r\nline three",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);

        String content = StateFileFormat.write(List.of(withMultilineReason), List.of(), List.of());
        StateFileFormat.Parsed parsed = StateFileFormat.parse(content);

        assertEquals(1, parsed.overrides().size());
        assertEquals(withMultilineReason, parsed.overrides().get(0));
    }

    @Test
    void roundTrips_aSecondRecordAfterAMultilineReason() {
        LevelOverride first = new LevelOverride(
                "com.acme.First", Level.DEBUG, "has a\nnewline",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);
        LevelOverride second = new LevelOverride(
                "com.acme.Second", Level.WARN, "plain reason",
                Instant.parse("2026-08-21T04:00:00Z"), "jmx", PersistenceTier.STICKY, null);

        StateFileFormat.Parsed parsed =
                StateFileFormat.parse(StateFileFormat.write(List.of(first, second), List.of(), List.of()));

        // The bug this guards against: a raw embedded newline used to shift
        // every subsequent line, corrupting (or losing) records after it.
        assertEquals(2, parsed.overrides().size());
        assertEquals(first, parsed.overrides().get(0));
        assertEquals(second, parsed.overrides().get(1));
    }

    @Test
    void roundTrips_backslashesAndQuotesInReason() {
        LevelOverride override = new LevelOverride(
                "com.acme.Worker", Level.DEBUG, "a \"quoted\" path C:\\logs",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);

        StateFileFormat.Parsed parsed = StateFileFormat.parse(StateFileFormat.write(List.of(override), List.of(), List.of()));

        assertEquals(override, parsed.overrides().get(0));
    }

    @Test
    void roundTrips_handlerOverridesAlongsideLoggerOverrides() {
        LevelOverride logger = new LevelOverride(
                "com.acme.Worker", Level.DEBUG, "why",
                Instant.parse("2026-08-21T03:14:02Z"), "jmx", PersistenceTier.STICKY, null);
        HandlerLevelOverride handler = HandlerLevelOverride.fixed(
                new HandlerRef("CONSOLE"), Level.TRACE, "why not",
                Instant.parse("2026-08-21T03:15:00Z"), "jmx", PersistenceTier.FOR,
                Instant.parse("2026-08-21T03:45:00Z"));

        StateFileFormat.Parsed parsed =
                StateFileFormat.parse(StateFileFormat.write(List.of(logger), List.of(handler), List.of()));

        assertEquals(List.of(logger), parsed.overrides());
        assertEquals(List.of(handler), parsed.handlerOverrides());
    }

    @Test
    void roundTrips_aSchema5FileWithNoPatternRulesKeyAtAll() {
        String content = StateFileFormat.write(List.of(), List.of(), List.of());

        assertEquals(-1, content.indexOf("patternRules"));
        StateFileFormat.Parsed parsed = StateFileFormat.parse(content);
        assertEquals(List.of(), parsed.overrides());
        assertEquals(List.of(), parsed.handlerOverrides());
    }

    @Test
    void parse_aSchema4FileWithAPatternRulesSection_dropsItEntirely() {
        String v4 = "schemaVersion: 4\n"
                + "overrides:\n"
                + "  - loggerName: \"com.acme.Worker\"\n"
                + "    level: DEBUG\n"
                + "    originPattern: null\n"
                + "    reason: null\n"
                + "    appliedAt: 2026-08-21T03:14:02Z\n"
                + "    source: \"jmx\"\n"
                + "    tier: STICKY\n"
                + "    expiresAt: null\n"
                + "handlerOverrides: []\n"
                + "patternRules:\n"
                + "  - pattern: \"*.deployment.scanner\"\n"
                + "    level: ERROR\n"
                + "    reason: null\n"
                + "    appliedAt: 2026-08-21T03:14:02Z\n"
                + "    source: \"jmx\"\n"
                + "    tier: STICKY\n"
                + "    expiresAt: null\n";

        StateFileFormat.Parsed parsed = StateFileFormat.parse(v4);

        assertEquals(1, parsed.overrides().size());
        assertEquals("com.acme.Worker", parsed.overrides().get(0).loggerName());
        assertEquals(List.of(), parsed.handlerOverrides());
    }

    @Test
    void parse_anOverrideWithALegacyOriginPatternField_ignoresIt() {
        String v4 = "schemaVersion: 4\n"
                + "overrides:\n"
                + "  - loggerName: \"org.jboss.as.clustering.infinispan\"\n"
                + "    level: ERROR\n"
                + "    originPattern: \"*.infinispan\"\n"
                + "    reason: \"noisy on redeploy\"\n"
                + "    appliedAt: 2026-08-21T03:14:02Z\n"
                + "    source: \"jmx\"\n"
                + "    tier: STICKY\n"
                + "    expiresAt: null\n";

        StateFileFormat.Parsed parsed = StateFileFormat.parse(v4);

        assertEquals(1, parsed.overrides().size());
        assertEquals("org.jboss.as.clustering.infinispan", parsed.overrides().get(0).loggerName());
        assertEquals("noisy on redeploy", parsed.overrides().get(0).reason());
    }

    @Test
    void parse_aVersion1FileWithNoHandlerOrPatternRuleSection_yieldsEmptyLists() {
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
        // The legacy "includeChildren:" field is read and ignored, never
        // rejected -- same convention as "originPattern:" above.
        assertNull(parsed.overrides().get(0).reason());
        assertEquals(List.of(), parsed.handlerOverrides());
    }

    @Test
    void parse_aVersion2HandlerRecordWithNoModeLine_defaultsToFixed() {
        String v2 = "schemaVersion: 2\n"
                + "overrides: []\n"
                + "handlerOverrides:\n"
                + "  - handlerRef: \"CONSOLE\"\n"
                + "    level: TRACE\n"
                + "    reason: null\n"
                + "    appliedAt: 2026-08-21T03:15:00Z\n"
                + "    source: \"jmx\"\n"
                + "    tier: STICKY\n"
                + "    expiresAt: null\n";

        StateFileFormat.Parsed parsed = StateFileFormat.parse(v2);

        assertEquals(1, parsed.handlerOverrides().size());
        assertEquals(org.logaperture.api.HandlerLevelMode.FIXED, parsed.handlerOverrides().get(0).mode());
    }

    @Test
    void parse_aVersion3FileWithNoPatternRulesSection_stillParses() {
        String v3 = "schemaVersion: 3\n"
                + "overrides: []\n"
                + "handlerOverrides: []\n";

        StateFileFormat.Parsed parsed = StateFileFormat.parse(v3);

        assertEquals(List.of(), parsed.overrides());
        assertEquals(List.of(), parsed.handlerOverrides());
    }
}
