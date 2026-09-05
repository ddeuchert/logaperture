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
import org.junit.jupiter.api.function.Executable;
import org.logaperture.cli.Parser.TierChoice;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    // --- tier resolution -----------------------------------------------------

    @Test
    void bareTierDefaultsToFourHourFor() {
        TierChoice choice = Parser.resolveTier(List.of());
        assertEquals("FOR", choice.tierName());
        assertEquals(Duration.ofHours(4).toSeconds(), choice.forSeconds());
    }

    @Test
    void sessionAndStickyKeywords() {
        assertEquals("SESSION", Parser.resolveTier(List.of("session")).tierName());
        assertEquals(0L, Parser.resolveTier(List.of("session")).forSeconds());
        assertEquals("STICKY", Parser.resolveTier(List.of("sticky")).tierName());
    }

    @Test
    void forWithDuration() {
        TierChoice choice = Parser.resolveTier(List.of("for", "30m"));
        assertEquals("FOR", choice.tierName());
        assertEquals(1800L, choice.forSeconds());
    }

    @Test
    void malformedTierTokensAreUsageErrors() {
        assertUsage(() -> Parser.resolveTier(List.of("for")));
        assertUsage(() -> Parser.resolveTier(List.of("session", "30m")));
        assertUsage(() -> Parser.resolveTier(List.of("sticky", "5m")));
        assertUsage(() -> Parser.resolveTier(List.of("forever")));
        assertUsage(() -> Parser.resolveTier(List.of("for", "30m", "extra")));
    }

    // --- command grammar ---------------------------------------------------

    @Test
    void helpAndVersionShortCircuit() {
        assertTrue(Parser.parse(new String[] {"--help"}).help());
        assertTrue(Parser.parse(new String[] {"-h"}).help());
        assertTrue(Parser.parse(new String[] {"--version"}).version());
        assertNull(Parser.parse(new String[] {"--help"}).command());
    }

    @Test
    void noArgumentsIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {}));
    }

    @Test
    void unknownCommandAndUnknownFlagAreUsageErrors() {
        assertUsage(() -> Parser.parse(new String[] {"wibble"}));
        assertUsage(() -> Parser.parse(new String[] {"levels", "--nope"}));
    }

    @Test
    void levelNamedFormNeedsALogger() {
        assertUsage(() -> Parser.parse(new String[] {"debug"}));
    }

    @Test
    void unknownLevelForSetIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"set", "com.acme", "LOUD"}));
    }

    @Test
    void levelIsCaseInsensitive() {
        // Parses without throwing — the resolved Command is opaque, so "no exception" is the assertion.
        Parser.parse(new String[] {"set", "com.acme", "debug"});
        Parser.parse(new String[] {"debug", "com.acme"});
    }

    @Test
    void pidMustBeANumber() {
        assertUsage(() -> Parser.parse(new String[] {"--pid", "abc", "status"}));
        assertEquals(1234L, Parser.parse(new String[] {"--pid", "1234", "status"}).pid());
    }

    @Test
    void reasonAndIncludeChildrenRejectedForNonMutatingCommands() {
        assertUsage(() -> Parser.parse(new String[] {"levels", "--reason", "x"}));
        assertUsage(() -> Parser.parse(new String[] {"status", "--include-children"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "com.acme", "--reason", "x"}));
    }

    @Test
    void allOnlyAppliesToReset() {
        assertUsage(() -> Parser.parse(new String[] {"levels", "--all"}));
        Parser.parse(new String[] {"reset", "--all"}); // fine
    }

    @Test
    void resetNeedsExactlyOneLoggerOrAll() {
        assertUsage(() -> Parser.parse(new String[] {"reset"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "a", "b"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "--all", "com.acme"}));
    }

    @Test
    void levelsTakesAtMostOneFilter() {
        Parser.parse(new String[] {"levels"});
        Parser.parse(new String[] {"levels", "com.acme"});
        assertUsage(() -> Parser.parse(new String[] {"levels", "a", "b"}));
    }

    @Test
    void debugFlagIsCarried() {
        assertTrue(Parser.parse(new String[] {"--debug", "status"}).debug());
    }

    // --- handler command (doc/specs/handler-floor-control.md) --------------------------------------

    @Test
    void handlerNeedsANameAndALevel() {
        assertUsage(() -> Parser.parse(new String[] {"handler"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE"}));
    }

    @Test
    void handlerParsesWithBareTierAndWithATierToken() {
        Parser.parse(new String[] {"handler", "CONSOLE", "TRACE"}); // bare -- defaults to for 4h, same as levels
        Parser.parse(new String[] {"handler", "CONSOLE", "TRACE", "session"});
        Parser.parse(new String[] {"handler", "CONSOLE", "TRACE", "for", "30m"});
        Parser.parse(new String[] {"handler", "CONSOLE", "TRACE", "sticky"});
    }

    @Test
    void handlerResetTakesNoFurtherArguments() {
        Parser.parse(new String[] {"handler", "CONSOLE", "reset"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "reset", "extra"}));
    }

    @Test
    void handlerAcceptsReasonButNotIncludeChildren() {
        Parser.parse(new String[] {"handler", "CONSOLE", "TRACE", "--reason", "INC-1"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "TRACE", "--include-children"}));
    }

    @Test
    void handlerResetRejectsReason_sameAsResetLoggerDoes() {
        // "handler <name> reset" is a revert, not a set -- a reason attached
        // to it would be silently dropped, same as "reset <logger> --reason".
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "reset", "--reason", "INC-1"}));
    }

    @Test
    void unknownLevelForHandlerIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "LOUD"}));
    }

    private static void assertUsage(Executable call) {
        CliError error = assertThrows(CliError.class, call);
        assertSame(CliError.class, error.getClass());
        assertEquals(CliError.USAGE, error.exitCode());
    }
}
