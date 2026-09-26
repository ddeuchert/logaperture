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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One question at a time on the terminal -- guided {@code add rule} (doc/specs/guided-add-rule.md)
 * and picking the JVM (doc/specs/pick-jvm.md). Each answer is read straight from the input stream,
 * one byte at a time up to the newline, with no read-ahead buffer: discovery's question and the
 * command's own then each have their own {@code Prompter} over the same stdin without one swallowing
 * the other's input (pick-jvm.md J9). End of input at any question raises {@link Cancelled}.
 */
final class Prompter {

    /** End of input: the operator gave up (Ctrl-D). The caller prints {@code Not applied.}. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    private final InputStream in;
    private final PrintStream out;

    /** @param out where the questions are written: stdout for a command's own, stderr for discovery's */
    Prompter(InputStream in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    PrintStream out() {
        return out;
    }

    /** Prints {@code question}, then reads one answer after a {@code "> "} marker; trimmed, never {@code null}. */
    String ask(String indent, String question) {
        out.println(indent + question);
        out.print(indent + "> ");
        out.flush();
        String line = readLine();
        if (line == null) {
            out.println();
            throw new Cancelled();
        }
        return line.trim();
    }

    /** One line without its terminator, or {@code null} at end of input before any byte of it. */
    private String readLine() {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try {
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                line.write(b);
            }
            if (b == -1 && line.size() == 0) {
                return null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String text = line.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** A {@code [y/N]} or {@code [Y/n]} question; Enter takes {@code defaultYes}, anything else is asked again. */
    boolean askYesNo(String indent, String question, boolean defaultYes) {
        String choices = defaultYes ? " [Y/n]" : " [y/N]";
        while (true) {
            String answer = ask(indent, question + choices).toLowerCase(Locale.ROOT);
            switch (answer) {
                case "" -> {
                    return defaultYes;
                }
                case "y", "yes" -> {
                    return true;
                }
                case "n", "no" -> {
                    return false;
                }
                default -> out.println(indent + "Answer y or n.");
            }
        }
    }
}
