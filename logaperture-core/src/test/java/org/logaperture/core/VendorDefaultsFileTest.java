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
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.SampleFullPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "The file": format, schema, and all-or-nothing validation. */
class VendorDefaultsFileTest {

    private static final Path PATH = Path.of("/opt/app/conf/vendor-defaults.yaml");

    /** The spec's own example, verbatim apart from the comment lines added to exercise stripping. */
    private static final String SPEC_EXAMPLE = """
            # vendor defaults for Acme 4.2
            schemaVersion: 1

            loggers:
              - name: org.hibernate.SQL
                level: WARN      # quieter than the framework default
              - name: com.acme.support
                level: DEBUG

            handlers:
              - name: FILE
                level: INFO
              - name: CONSOLE
                level: AUTO

            defaultHandlers: [CONSOLE]

            rules:
              - id: healthcheck-noise
                action: drop
                logger: com.acme.health
                messageContains: "ping ok"
                below: WARN
                reason: "load balancer health probes"
              - id: autoupdate-trace
                action: trim
                logger: com.destiny.AutoUpdateHelper
                throwable: java.net.ConnectException
                messageContains: "Failed to connect"
                below: FATAL
                frames: 0
            """;

    @Test
    void specExample_loadsEverySection() {
        VendorDefaults defaults = VendorDefaultsFile.parse(SPEC_EXAMPLE, PATH, false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status(), defaults.errors().toString());
        assertEquals(Optional.of(PATH), defaults.path());
        assertEquals(List.of("org.hibernate.SQL", "com.acme.support"), List.copyOf(defaults.loggers().keySet()));
        assertEquals(Optional.of(Level.WARN), defaults.loggerLevel("org.hibernate.SQL"));
        assertEquals(Optional.of(Level.DEBUG), defaults.loggerLevel("com.acme.support"));

        VendorDefaults.HandlerDefault file = defaults.handlers().get(new HandlerRef("FILE"));
        assertEquals(Level.INFO, file.level());
        assertEquals(HandlerLevelMode.FIXED, file.mode());
        VendorDefaults.HandlerDefault console = defaults.handlers().get(new HandlerRef("CONSOLE"));
        assertNull(console.level());
        assertEquals(HandlerLevelMode.AUTO, console.mode());

        assertEquals(Optional.of(List.of(new HandlerRef("CONSOLE"))), defaults.defaultHandlers());

        assertEquals(2, defaults.rules().size());
        VendorDefaults.RuleDefault drop = defaults.rules().get(0);
        assertEquals("vendor:healthcheck-noise", drop.id());
        assertEquals("drop", drop.action());
        assertEquals("com.acme.health", drop.loggerName());
        assertEquals("ping ok", drop.matchers().messageContains());
        assertEquals(Level.INFO, drop.matchers().levelAtMost(), "below WARN spares WARN and above");
        assertEquals("load balancer health probes", drop.reason());
        assertEquals(SampleFullPolicy.defaults(), drop.sampleFull());

        VendorDefaults.RuleDefault trim = defaults.rules().get(1);
        assertEquals("vendor:autoupdate-trace", trim.id());
        assertEquals("trim", trim.action());
        assertEquals("java.net.ConnectException", trim.matchers().throwableType());
        assertEquals(Level.ERROR, trim.matchers().levelAtMost(), "below FATAL resolves to ERROR, as on the CLI");
        assertEquals(0, trim.frames());
        assertFalse(trim.collapseCauses());

        assertEquals("2 loggers, 2 handlers, default handlers, 2 rules", defaults.summary());
    }

    @Test
    void emptyFileWithOnlyTheVersion_loadsWithNoSettings() {
        VendorDefaults defaults = VendorDefaultsFile.parse("schemaVersion: 1\n", PATH, false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status());
        assertTrue(defaults.isEmpty());
        assertEquals(Optional.empty(), defaults.defaultHandlers());
        assertEquals("loaded, no settings", defaults.summary());
    }

    @Test
    void ruleDefaults_matchTheAddRuleDefaults() {
        VendorDefaults defaults = VendorDefaultsFile.parse("""
                schemaVersion: 1
                rules:
                  - id: a
                    action: drop
                    logger: com.acme
                    messageContainsIgnoreCase: Timeout
                    sampleFull: false
                  - id: b
                    action: trim
                    logger: com.acme
                    frames: 3
                    collapseCauses: true
                    anyCause: true
                  - id: c
                    action: drop
                    logger: com.acme
                    throwable: java.io.IOException
                    sampleFull: 10m
                """, PATH, false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status(), defaults.errors().toString());
        VendorDefaults.RuleDefault a = defaults.rules().get(0);
        assertEquals(Level.WARN, a.matchers().levelAtMost(), "default below ERROR");
        assertEquals("Timeout", a.matchers().messageContains());
        assertTrue(a.matchers().messageIgnoreCase());
        assertEquals(SampleFullPolicy.disabled(), a.sampleFull());
        VendorDefaults.RuleDefault b = defaults.rules().get(1);
        assertEquals(3, b.frames());
        assertTrue(b.collapseCauses());
        assertTrue(b.matchers().anyCause());
        assertEquals(SampleFullPolicy.every(Duration.ofMinutes(10)), defaults.rules().get(2).sampleFull());
    }

    @Test
    void blockListOfDefaultHandlers_andQuotedScalars_parse() {
        VendorDefaults defaults = VendorDefaultsFile.parse("""
                schemaVersion: 1
                defaultHandlers:
                  - CONSOLE
                  - "FILE"
                loggers:
                - name: 'com.acme'
                  level: info
                  reason: "it's \\"noisy\\" # not a comment"
                """, PATH, false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status(), defaults.errors().toString());
        assertEquals(Optional.of(List.of(new HandlerRef("CONSOLE"), new HandlerRef("FILE"))),
                defaults.defaultHandlers());
        assertEquals(Optional.of(Level.INFO), defaults.loggerLevel("com.acme"), "levels are case-insensitive");
        assertEquals("it's \"noisy\" # not a comment", defaults.loggers().get("com.acme").reason());
    }

    @Test
    void anApostropheInsideAPlainScalar_doesNotSwallowATrailingComment() {
        VendorDefaults defaults = VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: com.acme
                    level: INFO
                    reason: vendor's choice  # see ticket 42
                rules:
                  - id: a
                    action: drop
                    logger: com.acme
                    messageContains: can't connect  # the pool's retry noise
                defaultHandlers: [CONSOLE, it's]
                """, PATH, false);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status(), defaults.errors().toString());
        assertEquals("vendor's choice", defaults.loggers().get("com.acme").reason());
        assertEquals("can't connect", defaults.rules().get(0).matchers().messageContains());
        assertEquals(Optional.of(List.of(new HandlerRef("CONSOLE"), new HandlerRef("it's"))),
                defaults.defaultHandlers());
    }

    @Test
    void everyErrorIsReported_eachWithItsLine() {
        VendorDefaults defaults = VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: com.acme
                    levle: DEBUG
                  - name: com.acme.*
                    level: INFO
                  - name: org.foo
                    level: LOUD
                handlers:
                  - name: FILE
                    level: INFO
                  - name: FILE
                    level: WARN
                colours: blue
                """, PATH, false);

        assertEquals(VendorDefaults.Status.REJECTED, defaults.status());
        assertTrue(defaults.isEmpty(), "a rejected file applies nothing at all");
        List<String> errors = defaults.errors();
        assertContains(errors, "line 4: unknown field 'levle'");
        assertContains(errors, "line 3: 'level' is missing");
        assertContains(errors, "line 5: 'com.acme.*' is a pattern");
        assertContains(errors, "line 8: unknown level 'LOUD'");
        assertContains(errors, "line 12: handler 'FILE' is listed twice");
        assertContains(errors, "line 14: unknown key 'colours'");
        assertEquals(6, errors.size(), errors.toString());
    }

    @Test
    void schemaVersion_isRequiredAndMustBeOne() {
        assertContains(VendorDefaultsFile.parse("loggers:\n", PATH, false).errors(), "line 1: schemaVersion is missing");
        assertContains(VendorDefaultsFile.parse("schemaVersion: 2\n", PATH, false).errors(),
                "line 1: schemaVersion must be 1");
    }

    @Test
    void recipes_isRejectedWithItsOwnMessage() {
        VendorDefaults defaults = VendorDefaultsFile.parse("schemaVersion: 1\nrecipes:\n  - name: x\n", PATH, false);

        assertEquals(VendorDefaults.Status.REJECTED, defaults.status());
        assertContains(defaults.errors(), "line 2: 'recipes' is not supported yet");
    }

    @Test
    void autoOnALogger_isRejected() {
        assertContains(VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: com.acme
                    level: AUTO
                """, PATH, false).errors(), "line 4: AUTO applies to handlers only");
    }

    @Test
    void ruleErrors_areCollected() {
        List<String> errors = VendorDefaultsFile.parse("""
                schemaVersion: 1
                rules:
                  - id: Bad_Id
                    action: drop
                    logger: com.acme
                  - id: ok
                    action: squash
                    logger: com.acme
                  - id: dup
                    action: trim
                    logger: com.acme
                    sampleFull: 5m
                  - id: dup
                    action: trim
                    logger: '*.Worker'
                    below: ALL
                    frames: -1
                """, PATH, false).errors();

        assertContains(errors, "line 3: id 'Bad_Id' must be 1-40 characters");
        assertContains(errors, "line 3: a drop rule needs at least one of messageContains");
        assertContains(errors, "line 7: action must be 'drop' or 'trim'");
        assertContains(errors, "line 12: unknown field 'sampleFull' in a trim rule entry");
        assertContains(errors, "line 13: rule id 'dup' is used twice");
        assertContains(errors, "line 15: '*.Worker' is a pattern");
        assertContains(errors, "line 16: 'below: ALL' leaves nothing to match");
        assertContains(errors, "line 17: frames must be 0 or more");
    }

    @Test
    void ruleOnAProtectedCategory_rejectsTheWholeFile() {
        ProtectedCategories security = name -> name.startsWith("com.acme.security");
        VendorDefaults defaults = VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: com.acme
                    level: WARN
                rules:
                  - id: hide-auth
                    action: drop
                    logger: com.acme.security.Auth
                    messageContains: "login"
                """, PATH, false, security);

        assertEquals(VendorDefaults.Status.REJECTED, defaults.status());
        assertContains(defaults.errors(), "line 8: 'com.acme.security.Auth' is a protected category");
        assertTrue(defaults.loggers().isEmpty(), "the valid logger entry doesn't apply either");
    }

    @Test
    void syntaxError_stopsWithOneError() {
        VendorDefaults tabs = VendorDefaultsFile.parse("schemaVersion: 1\nloggers:\n\t- name: x\n", PATH, false);
        assertEquals(List.of("line 3: tabs are not allowed for indentation -- use spaces"), tabs.errors());

        VendorDefaults blockScalar = VendorDefaultsFile.parse("schemaVersion: 1\nx: |\n  text\n", PATH, false);
        assertContains(blockScalar.errors(), "line 2: block scalars");

        VendorDefaults badIndent = VendorDefaultsFile.parse("schemaVersion: 1\n  loggers:\n", PATH, false);
        assertContains(badIndent.errors(), "line 2: unexpected indentation");

        VendorDefaults unterminated = VendorDefaultsFile.parse("schemaVersion: 1\nx: \"open\n", PATH, false);
        assertContains(unterminated.errors(), "line 2: unterminated quoted string");
    }

    @Test
    void groupNames_areRejectedAsHandlers() {
        List<String> errors = VendorDefaultsFile.parse("""
                schemaVersion: 1
                handlers:
                  - name: ALL_HANDLERS
                    level: WARN
                defaultHandlers: [CONSOLE, DEFAULT_HANDLERS]
                """, PATH, false).errors();

        assertContains(errors, "line 3: ALL_HANDLERS is a group, not a handler");
        assertContains(errors, "line 5: DEFAULT_HANDLERS is a group, not a handler");
    }

    @Test
    void emptyDefaultHandlers_isRejected() {
        assertContains(VendorDefaultsFile.parse("schemaVersion: 1\ndefaultHandlers: []\n", PATH, false).errors(),
                "line 2: 'defaultHandlers' must name at least one handler");
    }

    @Test
    void writableFlag_isCarriedThrough() {
        assertTrue(VendorDefaultsFile.parse("schemaVersion: 1\n", PATH, true).writable());
        assertFalse(VendorDefaultsFile.parse("schemaVersion: 1\n", PATH, false).writable());
    }

    @Test
    void load_missingFile_isARejection(@TempDir Path dir) {
        Path missing = dir.resolve("nope.yaml");
        VendorDefaults defaults = VendorDefaultsFile.load(missing);

        assertEquals(VendorDefaults.Status.REJECTED, defaults.status());
        assertEquals(List.of("file not found: " + missing), defaults.errors());
        assertEquals("REJECTED (1 error), see logctl doctor", defaults.summary());
    }

    @Test
    void load_readsTheFile_andReportsItWritableWhenItIs(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vendor-defaults.yaml");
        Files.writeString(file, SPEC_EXAMPLE, StandardCharsets.UTF_8);

        VendorDefaults defaults = VendorDefaultsFile.load(file);

        assertEquals(VendorDefaults.Status.LOADED, defaults.status(), defaults.errors().toString());
        assertTrue(defaults.writable(), "a temp file the test just wrote is writable by this account");
    }

    @Test
    void none_isNotConfigured() {
        VendorDefaults none = VendorDefaults.none();

        assertEquals(VendorDefaults.Status.NOT_CONFIGURED, none.status());
        assertTrue(none.isEmpty());
        assertEquals(Optional.empty(), none.path());
        assertNull(none.summary());
    }

    private static void assertContains(List<String> errors, String expectedPrefix) {
        assertTrue(errors.stream().anyMatch(e -> e.startsWith(expectedPrefix)),
                "expected an error starting with \"" + expectedPrefix + "\" in " + errors);
    }
}
