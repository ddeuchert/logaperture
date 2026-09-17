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
    void handlersTakesNoArguments() {
        Parser.parse(new String[] {"handlers"});                       // OK
        Parser.parse(new String[] {"handlers", "--json"});             // OK
        assertUsage(() -> Parser.parse(new String[] {"handlers", "CONSOLE"}));
    }

    @Test
    void levelNamedVerbsNoLongerExist() {
        // doc/specs/set-command-surface.md Decision #3 -- debug/trace/info/warn/error
        // are retired entirely, no alias kept; each names 'set logger' as the fix.
        assertUsage(() -> Parser.parse(new String[] {"debug"}));
        assertUsage(() -> Parser.parse(new String[] {"debug", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"trace", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"info", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"warn", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"error", "com.acme"}));
    }

    @Test
    void bareSetOfALoggerNameNoLongerExists() {
        // The bare "set <target> <level>" spelling is retired; "com.acme" isn't
        // one of the two recognized nouns (doc/specs/set-command-surface.md).
        assertUsage(() -> Parser.parse(new String[] {"set", "com.acme", "DEBUG"}));
        assertUsage(() -> Parser.parse(new String[] {"set"}));
    }

    @Test
    void unknownLevelForSetLoggerIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"set", "logger", "com.acme", "LOUD"}));
    }

    @Test
    void levelIsCaseInsensitive() {
        // Parses without throwing — the resolved Command is opaque, so "no exception" is the assertion.
        Parser.parse(new String[] {"set", "logger", "com.acme", "debug"});
    }

    @Test
    void pidMustBeANumber() {
        assertUsage(() -> Parser.parse(new String[] {"--pid", "abc", "status"}));
        assertEquals(1234L, Parser.parse(new String[] {"--pid", "1234", "status"}).pid());
    }

    @Test
    void reasonAndYesRejectedForNonMutatingCommands() {
        assertUsage(() -> Parser.parse(new String[] {"levels", "--reason", "x"}));
        assertUsage(() -> Parser.parse(new String[] {"status", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "com.acme", "--reason", "x"}));
    }

    @Test
    void yesIsAcceptedOnSetLoggerButNotSetHandler() {
        // doc/specs/pattern-level-targeting.md "Confirmation and CLI
        // behavior" -- Parser does no pattern detection of its own (same as
        // a plain logger name, the target string passes through untouched);
        // --yes just needs to not be rejected as out of place here.
        Parser.parse(new String[] {"set", "logger", "org.apache.*", "DEBUG", "--yes"});
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "--yes"}));
    }

    @Test
    void includeStickyOnlyAppliesToReset() {
        assertUsage(() -> Parser.parse(new String[] {"levels", "--include-sticky"}));
        Parser.parse(new String[] {"reset", "logger", "com.acme", "--include-sticky"}); // fine
    }

    // --- reset (doc/specs/reset-command-surface.md) -------------------------------------------

    @Test
    void resetAllNoLongerExists() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "--all"}));
    }

    @Test
    void bareResetOfALoggerNameNoLongerExists() {
        // The bare "reset <logger>" spelling is retired; "logger" isn't
        // recognized as a noun on its own, and "com.acme" isn't one of the
        // four recognized nouns either -- both are usage errors naming the
        // replacement (doc/specs/reset-command-surface.md).
        assertUsage(() -> Parser.parse(new String[] {"reset", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"reset"}));
    }

    @Test
    void resetLoggerNeedsExactlyOneTarget() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "logger"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "logger", "a", "b"}));
        Parser.parse(new String[] {"reset", "logger", "com.acme"}); // fine
        Parser.parse(new String[] {"reset", "logger", "com.acme", "--include-sticky"}); // fine
    }

    @Test
    void resetLoggersTakesNoArguments() {
        Parser.parse(new String[] {"reset", "loggers"}); // fine
        Parser.parse(new String[] {"reset", "loggers", "--include-sticky"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"reset", "loggers", "com.acme"}));
    }

    @Test
    void resetHandlerNeedsExactlyOneName() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "handler"}));
        assertUsage(() -> Parser.parse(new String[] {"reset", "handler", "CONSOLE", "FILE"}));
        Parser.parse(new String[] {"reset", "handler", "CONSOLE"}); // fine
        Parser.parse(new String[] {"reset", "handler", "CONSOLE", "--include-sticky"}); // fine
    }

    @Test
    void resetHandlersTakesNoArguments() {
        Parser.parse(new String[] {"reset", "handlers"}); // fine
        Parser.parse(new String[] {"reset", "handlers", "--include-sticky"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"reset", "handlers", "CONSOLE"}));
    }

    @Test
    void resetUnknownNounIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"reset", "everything"}));
    }

    @Test
    void levelsTakesAtMostOneFilter() {
        Parser.parse(new String[] {"levels"});
        Parser.parse(new String[] {"levels", "com.acme"});
        assertUsage(() -> Parser.parse(new String[] {"levels", "a", "b"}));
    }

    @Test
    void doctorTakesNoArguments() {
        Parser.parse(new String[] {"doctor"});
        Parser.parse(new String[] {"doctor", "--json"});
        assertUsage(() -> Parser.parse(new String[] {"doctor", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"doctor", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"doctor", "--reason", "x"}));
    }

    @Test
    void topTakesNoArguments() {
        Parser.parse(new String[] {"top"});
        Parser.parse(new String[] {"top", "--json"});
        Parser.parse(new String[] {"top", "--limit", "5"});
        assertUsage(() -> Parser.parse(new String[] {"top", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"top", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"top", "--reason", "x"}));
    }

    @Test
    void limitAppliesOnlyToTop() {
        assertUsage(() -> Parser.parse(new String[] {"doctor", "--limit", "5"}));
        assertUsage(() -> Parser.parse(new String[] {"status", "--limit", "5"}));
    }

    @Test
    void envTakesNoArguments() {
        Parser.parse(new String[] {"env"});
        Parser.parse(new String[] {"env", "--json"});
        assertUsage(() -> Parser.parse(new String[] {"env", "com.acme"}));
        assertUsage(() -> Parser.parse(new String[] {"env", "--yes"}));
        assertUsage(() -> Parser.parse(new String[] {"env", "--reason", "x"}));
        assertUsage(() -> Parser.parse(new String[] {"env", "--limit", "5"}));
    }

    @Test
    void limitNeedsANumericValue() {
        assertUsage(() -> Parser.parse(new String[] {"top", "--limit", "soon"}));
        assertUsage(() -> Parser.parse(new String[] {"top", "--limit"}));
    }

    @Test
    void debugFlagIsCarried() {
        assertTrue(Parser.parse(new String[] {"--debug", "status"}).debug());
    }

    // --- bare 'handler' verb, fully retired (doc/specs/set-command-surface.md) ---------------

    @Test
    void bareHandlerVerbNoLongerExists() {
        assertUsage(() -> Parser.parse(new String[] {"handler"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "TRACE"}));
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "AUTO"}));
    }

    @Test
    void bareHandlerResetNamesResetHandlerAsTheFix() {
        // doc/specs/reset-command-surface.md -- retired in favor of
        // "reset handler <name>", no alias kept (pre-1.0).
        assertUsage(() -> Parser.parse(new String[] {"handler", "CONSOLE", "reset"}));
    }

    // --- set handler (doc/specs/set-command-surface.md, doc/specs/handler-floor-control.md) ----

    @Test
    void setHandlerNeedsANameAndALevel() {
        assertUsage(() -> Parser.parse(new String[] {"set", "handler"}));
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE"}));
    }

    @Test
    void setHandlerParsesWithBareTierAndWithATierToken() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE"}); // bare -- defaults to for 4h
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "session"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "for", "30m"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "sticky"});
    }

    @Test
    void setHandlerAcceptsReasonButNotYes() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "--reason", "INC-1"}); // fine
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE", "TRACE", "--yes"}));
    }

    @Test
    void unknownLevelForSetHandlerIsAUsageError() {
        assertUsage(() -> Parser.parse(new String[] {"set", "handler", "CONSOLE", "LOUD"}));
    }

    // --- set handler AUTO (doc/specs/handler-floor-control.md "AUTO handler level", issue #20) ----

    @Test
    void setHandlerAutoParsesWithBareTierAndWithATierToken() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO"}); // bare -- defaults to for 4h
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "session"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "for", "30m"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "sticky"});
    }

    @Test
    void setHandlerAutoIsCaseInsensitive() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "auto"});
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "Auto"});
    }

    @Test
    void setHandlerAutoAcceptsReason() {
        Parser.parse(new String[] {"set", "handler", "CONSOLE", "AUTO", "--reason", "INC-1"}); // fine
    }

    private static void assertUsage(Executable call) {
        CliError error = assertThrows(CliError.class, call);
        assertSame(CliError.class, error.getClass());
        assertEquals(CliError.USAGE, error.exitCode());
    }
}
