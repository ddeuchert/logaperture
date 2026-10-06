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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Issue #129: the single-pass normalizer must produce exactly what the four
 * regular expressions it replaced produced, since the normalized message is
 * user-visible ({@code logctl storms}, {@code --json}). The regex version is
 * kept here as the oracle.
 */
class StormMessageNormalizerTest {

    /** The pre-#129 implementation, verbatim. */
    private static final class RegexOracle {

        private static final Pattern UUID_PATTERN = Pattern.compile(
                "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
        private static final Pattern HEX_RUN_PATTERN = Pattern.compile(
                "\\b0[xX][0-9a-fA-F]+\\b|\\b(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b");
        private static final Pattern DIGIT_RUN_PATTERN = Pattern.compile("\\d+");
        private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

        static String normalize(String message) {
            if (message == null) {
                return "";
            }
            String result = UUID_PATTERN.matcher(message).replaceAll("<uuid>");
            result = HEX_RUN_PATTERN.matcher(result).replaceAll("<hex>");
            result = DIGIT_RUN_PATTERN.matcher(result).replaceAll("<n>");
            result = WHITESPACE_PATTERN.matcher(result).replaceAll(" ").trim();
            if (result.length() > 500) {
                result = result.substring(0, 500);
            }
            return result;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "failed for order 4821",
            "Processed order 48213 for customer 7f3a9c21 in 12 ms",
            "id 123e4567-e89b-12d3-a456-426614174000 missing",
            "ID 123E4567-E89B-12D3-A456-426614174000",
            "123e4567-e89b-12d3-a456-4266141740001",   // last segment 13 long: not a UUID
            "x-123e4567-e89b-12d3-a456-426614174000-y", // UUID between dashes
            "aaaa-123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174000_tail", // word continues after: not a UUID
            "checksum deadbeef mismatch",
            "deadbeef_suffix and prefix_deadbeef",
            "deadbee", "deadbe", "deadb", "abcdef", "ABCDEF", "123456", "12345a",
            "addr 0x1a2b3c", "0X1F", "0x", "0xg1", "0x1fx", "00x1f", "0x12-0xff",
            "user42 retry3x after 250ms",
            "a1b2c3d4 is hex, a1b2c is not",
            "\ttabs\tand\n\nnewlines\r\n and  runs   of   spaces  ",
            "\u0001leading control",
            "trailing control\u0001",
            "mid\u0001control",
            "\u000b vertical tab \f form feed",
            "<hex> literal placeholders <n> <uuid>",
            "path /var/lib/app/7f3a9c21e0/data.bin",
            "json {\"id\":482156,\"trace\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}",
    })
    void matchesRegexOracle(String message) {
        assertEquals(RegexOracle.normalize(message), StormMessageNormalizer.normalize(message));
        assertHashMatchesText(message);
    }

    @Test
    void matchesRegexOracle_onRandomAsciiMessages() {
        // Weighted toward the characters the rules care about: hex digits, x, dashes,
        // underscores, whitespace and controls, placeholder brackets.
        String alphabet = "0123456789abcdefABCDEFxXghz_-- \t\n\r\u000b\f\u0001<>.:/";
        Random random = new Random(129);
        for (int round = 0; round < 200_000; round++) {
            int length = random.nextInt(round % 100 == 0 ? 1_200 : 60);
            StringBuilder sb = new StringBuilder(length + 40);
            for (int k = 0; k < length; k++) {
                if (random.nextInt(40) == 0) {
                    sb.append(randomUuid(random));
                } else {
                    sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }
            String message = sb.toString();
            assertEquals(RegexOracle.normalize(message), StormMessageNormalizer.normalize(message),
                    () -> "input: " + escape(message));
            assertHashMatchesText(message);
        }
    }

    /**
     * Issue #147: the storm filter keys on {@code hash}, computed without
     * building the text, so it must be exactly the hash of the text
     * {@code normalize} builds, including across a hex rollback, the trailing
     * trim and the {@value StormMessageNormalizer#MAX_LENGTH}-char cut.
     */
    @Test
    void hash_matchesTheHashOfTheText_atTheCut() {
        for (int pad = 490; pad <= 505; pad++) {
            for (String tail : new String[] {" deadbeef12 x", " 0x1f", "  \u0001 ", " 4bf92f3577b34da6a3ce929d0e0e4736",
                    " 123e4567-e89b-12d3-a456-426614174000 tail", "     b"}) {
                assertHashMatchesText("a".repeat(pad) + tail);
                assertHashMatchesText("a ".repeat(pad / 2) + tail);
            }
        }
        assertHashMatchesText("");
        assertHashMatchesText("   ");
        assertHashMatchesText("für 12 Einträge é deadbeef");
        assertEquals(StormMessageNormalizer.hashOf(""), StormMessageNormalizer.hash(null));
    }

    @Test
    void hash_separatesMessagesThatNormalizeDifferently() {
        assertEquals(StormMessageNormalizer.hash("order 4821 failed"), StormMessageNormalizer.hash("order 9 failed"));
        assertNotEquals(StormMessageNormalizer.hash("order 4821 failed"), StormMessageNormalizer.hash("order 4821 done"));
        assertNotEquals(StormMessageNormalizer.hash("ab"), StormMessageNormalizer.hash("ba"));
    }

    private static void assertHashMatchesText(String message) {
        assertEquals(StormMessageNormalizer.hashOf(StormMessageNormalizer.normalize(message)),
                StormMessageNormalizer.hash(message), () -> "input: " + escape(message));
    }

    @Test
    void capsAt500_afterCollapsing() {
        String longMessage = "  " + "word ".repeat(200) + "tail";
        String normalized = StormMessageNormalizer.normalize(longMessage);
        assertEquals(500, normalized.length());
        assertEquals(RegexOracle.normalize(longMessage), normalized);
    }

    @Test
    void cap_isNotFooledByTrailingWhitespace() {
        // 499 significant chars, then whitespace, then more: the space at index 499 is kept.
        String message = "a".repeat(499) + "     b";
        assertEquals(RegexOracle.normalize(message), StormMessageNormalizer.normalize(message));
        String exact = "a".repeat(499) + "     ";
        assertEquals(RegexOracle.normalize(exact), StormMessageNormalizer.normalize(exact));
    }

    @Test
    void nonAsciiLetter_isAWordCharacter() {
        // Chosen behavior, not the JDK-dependent regex one (see StormMessageNormalizer's doc).
        assertEquals("édeadbeef", StormMessageNormalizer.normalize("édeadbeef"));
        assertEquals("é <hex>", StormMessageNormalizer.normalize("é deadbeef"));
        assertEquals("für <n> Einträge", StormMessageNormalizer.normalize("für 12 Einträge"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", StormMessageNormalizer.normalize(null));
    }

    private static String randomUuid(Random random) {
        String hex = "0123456789abcdefABCDEF";
        StringBuilder sb = new StringBuilder(36);
        for (int k = 0; k < 36; k++) {
            sb.append(k == 8 || k == 13 || k == 18 || k == 23 ? '-' : hex.charAt(random.nextInt(hex.length())));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= ' ' && c < 127 ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }
}
