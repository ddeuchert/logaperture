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
import org.logaperture.control.jmx.RuleData;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/list-rules-verbose.md -- {@code logctl list rules --verbose} and the rule expression. */
class RuleExpressionTest {

    private static RuleData drop(String messageContains, boolean ignoreCase, String throwable, String throwableMessage,
            boolean anyCause, String levelAtMost, Boolean sampleFull, Long everyMillis) {
        return new RuleData("r1", "org.myorg.MyClass", "drop", levelAtMost, messageContains, ignoreCase, throwable,
                throwableMessage, anyCause, "why", "STICKY", null, "2026-09-25T12:00:00Z", "system", 0, null, null,
                null, false, sampleFull, everyMillis, false);
    }

    private static RuleData trim(String levelAtMost, int frames, boolean collapseCauses) {
        return new RuleData("r2", "com.destiny.AutoUpdateHelper", "trim", levelAtMost, null, false,
                "java.net.ConnectException", null, false, null, "SESSION", null, "2026-09-25T12:00:00Z", "system", 0,
                frames, collapseCauses, "vendor-defaults", false, null, null, false);
    }

    @Test
    void theIssueExample() {
        RuleData rule = drop("Can't connect", false, "java.net.ConnectionException", null, false, "INFO", true,
                300_000L);

        assertEquals("--message-contains \"Can't connect\" --throwable java.net.ConnectionException --below WARN "
                + "--sample-full 5m", RuleExpression.of(rule));
    }

    @Test
    void everyOption_inItsFixedOrder() {
        assertEquals("--message-contains-ignore-case timeout --throwable java.io.IOException "
                        + "--throwable-message-contains reset --any-cause --below ERROR --no-sample-full",
                RuleExpression.of(drop("timeout", true, "java.io.IOException", "reset", true, "WARN", false, 300_000L)));
        assertEquals("--throwable java.net.ConnectException --below FATAL --frames 2 --collapse-causes",
                RuleExpression.of(trim("ERROR", 2, true)));
        assertEquals("--throwable java.net.ConnectException --below INFO --frames 0",
                RuleExpression.of(trim("DEBUG", 0, false)), "defaults are explicit (V2)");
    }

    @Test
    void quoting_isShellSafe() {
        assertEquals("plain.Value-1:/x", RuleExpression.quote("plain.Value-1:/x"));
        assertEquals("\"two words\"", RuleExpression.quote("two words"));
        assertEquals("\"Outer\\$Inner\"", RuleExpression.quote("Outer$Inner"));
        assertEquals("\"say \\\"hi\\\" \\\\ \\`x\\`\"", RuleExpression.quote("say \"hi\" \\ `x`"));
        assertEquals("\"a*\"", RuleExpression.quote("a*"));
    }

    @Test
    void durations_useTheLargestWholeUnit() {
        assertEquals("30s", RuleExpression.duration(30_000));
        assertEquals("90s", RuleExpression.duration(90_000));
        assertEquals("5m", RuleExpression.duration(300_000));
        assertEquals("2h", RuleExpression.duration(7_200_000));
        assertEquals("1d", RuleExpression.duration(86_400_000));
    }

    /** "Round trip": the expression, fed back to {@code add rule}, reproduces the same definition. */
    @Test
    void theExpression_parsesBackToTheSameRule() {
        RuleData rule = drop("it's \"quoted\" $HOME", true, "a.B$C", "x y", true, "INFO", true, 120_000L);
        List<String> argv = new ArrayList<>(List.of("add", "rule", "drop", rule.getLoggerName()));
        argv.addAll(shellSplit(RuleExpression.of(rule)));
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.addRuleDropResult = rule;

        Parser.parse(argv.toArray(String[]::new)).command()
                .run(mbean, new PrintStream(new ByteArrayOutputStream()), InputStream.nullInputStream(), false);

        Object[] call = mbean.addRuleDropCalls.get(0);
        assertArrayEquals(new Object[] {"org.myorg.MyClass", "it's \"quoted\" $HOME", true, "a.B$C", "x y", true,
                "INFO", true, 120_000L}, java.util.Arrays.copyOf(call, 9));
    }

    @Test
    void theColumn_appearsOnlyWithVerbose() {
        FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
        mbean.rules = new ArrayList<>(List.of(drop("Can't connect", false, null, null, false, "INFO", true,
                300_000L)));

        String plain = run(mbean, "list", "rules");
        String verbose = run(mbean, "list", "rules", "--verbose");

        assertFalse(plain.contains("EXPRESSION"), plain);
        assertTrue(verbose.lines().findFirst().orElseThrow().matches("ID\\s+LOGGER\\s+EXPRESSION\\s+ACTION.*"),
                verbose);
        assertTrue(verbose.contains("--message-contains \"Can't connect\" --below WARN --sample-full 5m"), verbose);
    }

    @Test
    void json_alwaysCarriesTheExpression() {
        String json = Json.rule(drop("x", false, null, null, false, "INFO", true, 300_000L));

        assertTrue(json.contains("\"sampleFullEnabled\":true,\"sampleFullEveryMillis\":300000,"
                + "\"expression\":\"--message-contains x --below WARN --sample-full 5m\""), json);
    }

    @Test
    void verbose_isAUsageErrorElsewhere() {
        for (String[] argv : List.of(new String[] {"list", "loggers", "--verbose"},
                new String[] {"status", "--verbose"})) {
            CliError error = assertThrows(CliError.class, () -> Parser.parse(argv));
            assertEquals(CliError.USAGE, error.exitCode());
        }
    }

    private static String run(FakeLevelControlMXBean mbean, String... argv) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Parser.parse(argv).command().run(mbean, new PrintStream(captured, true, StandardCharsets.UTF_8),
                InputStream.nullInputStream(), false);
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** A POSIX-shell word splitter for what {@link RuleExpression} emits: bare words and double-quoted strings. */
    private static List<String> shellSplit(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = null;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < line.length() && "\"\\$`".indexOf(line.charAt(i + 1)) >= 0) {
                    word.append(line.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    word.append(c);
                }
            } else if (c == ' ') {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
            } else {
                if (word == null) {
                    word = new StringBuilder();
                }
                if (c == '"') {
                    quoted = true;
                } else {
                    word.append(c);
                }
            }
        }
        if (word != null) {
            words.add(word.toString());
        }
        return words;
    }
}
