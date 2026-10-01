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

import java.util.Arrays;

/**
 * doc/specs/storm-detection.md Decision #3, "Message normalization": the
 * transform that makes {@code "failed for order 4821"} and {@code "failed for
 * order 9134"} one fingerprint. Runs on every event that reaches a handler,
 * so it is one hand-written pass over the message rather than regular
 * expressions (issue #129: four {@code replaceAll} passes cost microseconds
 * per event).
 *
 * <p>The rules, applied to maximal runs of word characters (letters, digits,
 * {@code _}):
 * <ul>
 *   <li>A UUID ({@code 8-4-4-4-12} hex, as five word runs joined by single
 *       {@code -}) becomes {@code <uuid>}.</li>
 *   <li>A word that is {@code 0x}/{@code 0X} followed by hex digits, or is
 *       6+ hex digits including at least one letter {@code a-f}, becomes
 *       {@code <hex>}. The letter requirement keeps a long decimal id a
 *       number: {@code order 482156} and {@code order 4821} must fingerprint
 *       together.</li>
 *   <li>Otherwise each run of ASCII digits inside the word becomes
 *       {@code <n>}, wherever it sits ({@code user42} is {@code user<n>}).</li>
 * </ul>
 * Runs of ASCII whitespace collapse to one space, the result is trimmed of
 * leading and trailing characters up to {@code U+0020} (as {@link
 * String#trim()}), then cut to {@value #MAX_LENGTH} characters.
 *
 * <p>A hex or UUID match only ever covers whole word runs: that is what the
 * previous regular expressions' {@code \b} anchors reduced to, and why a
 * single left-to-right pass reproduces them exactly. One deliberate choice
 * where the old implementation depended on the JDK: a non-ASCII letter or
 * digit counts as a word character (JDK 17's {@code \b}; JDK 19+ made it
 * ASCII-only), so {@code édeadbeef} stays one word and is not hex.
 */
final class StormMessageNormalizer {

    static final int MAX_LENGTH = 500;

    private static final int UUID_LENGTH = 36;
    private static final char[] UUID = "<uuid>".toCharArray();
    private static final char[] HEX = "<hex>".toCharArray();
    private static final char[] DIGITS = "<n>".toCharArray();

    private static final byte WORD = 1;
    private static final byte DIGIT = 2;
    private static final byte HEX_LETTER = 4;
    /** Character classes for ASCII: word characters are {@code [A-Za-z0-9_]}. */
    private static final byte[] KIND = new byte[128];

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            KIND[c] = WORD;
            KIND[Character.toUpperCase(c)] = WORD;
        }
        for (char c = 'a'; c <= 'f'; c++) {
            KIND[c] = WORD | HEX_LETTER;
            KIND[Character.toUpperCase(c)] = WORD | HEX_LETTER;
        }
        for (char c = '0'; c <= '9'; c++) {
            KIND[c] = WORD | DIGIT;
        }
        KIND['_'] = WORD;
    }

    private StormMessageNormalizer() {
    }

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        int n = message.length();
        // Placeholders are at most 6 chars for a 1-char minimum source (a lone digit becomes
        // "<n>"), so output can outgrow input; the cap bounds it anyway.
        Output out = new Output(Math.min(n, MAX_LENGTH) + 8);
        int i = 0;
        while (i < n) {
            if (out.length >= MAX_LENGTH && out.lastSignificant >= MAX_LENGTH - 1) {
                // The first MAX_LENGTH chars are final: trailing trim can't reach back past a
                // significant char at or beyond the cut, so nothing later can change the result.
                break;
            }
            char c = message.charAt(i);
            if (isWhitespace(c)) {
                if (out.length > 0) {
                    out.append(' ');
                }
                do {
                    i++;
                } while (i < n && isWhitespace(message.charAt(i)));
                continue;
            }
            if (!isWordChar(c)) {
                if (c > ' ') {
                    out.appendSignificant(c);
                } else if (out.length > 0) {
                    out.append(c); // a control char mid-message is kept, as String.trim() keeps it
                }
                i++;
                continue;
            }
            // c starts a word run: the previous char, if any, was not a word char.
            if (isUuidAt(message, i)) {
                out.appendPlaceholder(UUID);
                i += UUID_LENGTH;
                continue;
            }
            i = appendWord(out, message, i);
        }
        return out.result();
    }

    /**
     * Copies the word run starting at {@code start}, digit runs collapsed, in
     * the same scan that decides whether the whole word is hex; if it is, the
     * copy is rolled back and replaced with {@code <hex>}. Returns the index
     * just past the word.
     */
    private static int appendWord(Output out, String s, int start) {
        int n = s.length();
        int mark = out.length;
        boolean headHex = true;   // chars start and start+1 are hex digits
        boolean tailHex = true;   // every char from start+2 is a hex digit
        boolean hexLetter = false;
        boolean inDigits = false;
        int k = start;
        while (k < n) {
            char c = s.charAt(k);
            int kind = c < 128 ? KIND[c] : (Character.isLetterOrDigit(c) ? WORD : 0);
            if ((kind & WORD) == 0) {
                break;
            }
            if ((kind & DIGIT) != 0) {
                if (!inDigits) {
                    out.appendPlaceholder(DIGITS);
                    inDigits = true;
                }
            } else {
                inDigits = false;
                out.appendSignificant(c);
                if ((kind & HEX_LETTER) != 0) {
                    hexLetter = true;
                } else if (k - start < 2) {
                    headHex = false;
                } else {
                    tailHex = false;
                }
            }
            k++;
        }
        int length = k - start;
        boolean hex;
        if (length >= 3 && s.charAt(start) == '0' && (s.charAt(start + 1) | 0x20) == 'x') {
            hex = tailHex;
        } else {
            hex = length >= 6 && headHex && tailHex && hexLetter;
        }
        if (hex) {
            out.length = mark;
            out.appendPlaceholder(HEX);
        }
        return k;
    }

    /**
     * A bare {@code char[]} rather than a {@link StringBuilder}: this runs on
     * every event, and {@code StringBuilder.append(char)} re-checks capacity
     * and string encoding on every call.
     */
    private static final class Output {

        char[] chars;
        int length;
        int lastSignificant = -1; // index of the last char above U+0020

        Output(int capacity) {
            chars = new char[capacity];
        }

        void append(char c) {
            if (length == chars.length) {
                chars = Arrays.copyOf(chars, chars.length * 2);
            }
            chars[length++] = c;
        }

        void appendSignificant(char c) {
            append(c);
            lastSignificant = length - 1;
        }

        void appendPlaceholder(char[] placeholder) {
            if (length + placeholder.length > chars.length) {
                chars = Arrays.copyOf(chars, Math.max(chars.length * 2, length + placeholder.length));
            }
            System.arraycopy(placeholder, 0, chars, length, placeholder.length);
            length += placeholder.length;
            lastSignificant = length - 1;
        }

        String result() {
            int end = lastSignificant + 1; // trailing trim
            return new String(chars, 0, Math.min(end, MAX_LENGTH));
        }
    }

    /** {@code 8-4-4-4-12} hex starting at a word start, ending at a word end. */
    private static boolean isUuidAt(String s, int start) {
        int end = start + UUID_LENGTH;
        if (end > s.length() || (end < s.length() && isWordChar(s.charAt(end)))) {
            return false;
        }
        for (int k = 0; k < UUID_LENGTH; k++) {
            char c = s.charAt(start + k);
            boolean dash = k == 8 || k == 13 || k == 18 || k == 23;
            if (dash ? c != '-' : !isHexDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /** Java regex {@code \s}: space, tab, newline, vertical tab, form feed, carriage return. */
    private static boolean isWhitespace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    private static boolean isWordChar(char c) {
        return c < 128 ? (KIND[c] & WORD) != 0 : Character.isLetterOrDigit(c);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
