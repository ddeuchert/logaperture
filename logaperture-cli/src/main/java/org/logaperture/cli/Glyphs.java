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

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * The few non-ASCII characters {@code logctl} prints ({@code —} for "none", {@code →},
 * {@code …}, {@code –}), and their ASCII stand-ins for a console that can't show them
 * (issue #190). On Windows, {@code System.out} encodes with the console's code page (cp437 or
 * cp850 by default), which has none of these, and the encoder writes {@code ?} instead: a
 * {@code —} meaning "no value" reads as "unknown".
 *
 * <p>Decided once, at startup, from the stream's own charset. A console that can encode all
 * of them is left alone.
 */
final class Glyphs {

    /** Each character, and what replaces it. {@code →} and {@code …} widen, so tables swap before measuring. */
    private static final String[][] STAND_INS = {
        {"—", "-"},
        {"–", "-"},
        {"→", "->"},
        {"…", "..."},
    };

    private static volatile boolean ascii;

    private Glyphs() {
    }

    /** Whether output is being written with ASCII stand-ins. */
    static boolean ascii() {
        return ascii;
    }

    /** Test seam, and set by {@link #forStdout}. */
    static void useAscii(boolean on) {
        ascii = on;
    }

    /** {@code text} as it will be printed: with stand-ins when output is ASCII. */
    static String forOutput(String text) {
        return ascii ? plain(text) : text;
    }

    /** {@code text} with every one of these characters replaced by its stand-in. */
    static String plain(String text) {
        String out = text;
        for (String[] standIn : STAND_INS) {
            if (out.contains(standIn[0])) {
                out = out.replace(standIn[0], standIn[1]);
            }
        }
        return out;
    }

    /** Whether {@code charset} can encode every one of these characters. */
    static boolean canEncode(Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        for (String[] standIn : STAND_INS) {
            if (!encoder.canEncode(standIn[0])) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code System.out}, wrapped to print stand-ins if its charset can't encode these
     * characters; also switches {@link #ascii()} on in that case, for tables.
     */
    static PrintStream forStdout(PrintStream stdout) {
        Charset charset = charsetOf(stdout, "stdout.encoding");
        useAscii(!canEncode(charset));
        return forStream(stdout, charset);
    }

    /** {@code System.err}, wrapped likewise. */
    static PrintStream forStderr(PrintStream stderr) {
        return forStream(stderr, charsetOf(stderr, "stderr.encoding"));
    }

    /** {@code stream} itself if {@code charset} can encode these characters, else a translating wrapper. */
    static PrintStream forStream(PrintStream stream, Charset charset) {
        return canEncode(charset) ? stream : new Translating(stream, charset);
    }

    /**
     * The charset {@code stream} encodes with: {@code PrintStream.charset()} where the JDK has it
     * (18+; the build targets 17, so it is looked up reflectively), else the JDK's property for
     * that stream, else the default charset.
     */
    static Charset charsetOf(PrintStream stream, String property) {
        try {
            return (Charset) PrintStream.class.getMethod("charset").invoke(stream);
        } catch (ReflectiveOperationException | RuntimeException beforeJdk18) {
            for (String name : new String[] {property, "sun." + property}) {
                String value = System.getProperty(name);
                if (value != null && Charset.isSupported(value)) {
                    return Charset.forName(value);
                }
            }
            return Charset.defaultCharset();
        }
    }

    /**
     * Writes through to {@code target} in {@code target}'s charset, swapping these characters
     * first. Every text path ends in one of the {@code print} overloads here: a subclass's
     * {@code println(x)} is {@code print(x)} then a newline, and {@code printf}/{@code append}
     * go through {@code print(String)}.
     */
    private static final class Translating extends PrintStream {

        Translating(PrintStream target, Charset charset) {
            super(target, true, charset);
        }

        @Override
        public void print(String s) {
            super.print(plain(String.valueOf(s)));
        }

        @Override
        public void print(char c) {
            super.print(plain(String.valueOf(c)));
        }

        @Override
        public void print(char[] s) {
            super.print(plain(new String(s)));
        }

        @Override
        public void print(Object obj) {
            print(String.valueOf(obj));
        }
    }
}
