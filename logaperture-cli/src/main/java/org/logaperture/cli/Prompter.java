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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One question at a time on the terminal, for guided {@code add rule} (doc/specs/guided-add-rule.md).
 * A single reader serves every question -- a fresh {@link BufferedReader} per question would swallow
 * input it had buffered for the next one. End of input at any question raises {@link Cancelled}.
 */
final class Prompter {

    /** End of input: the operator gave up (Ctrl-D). The caller prints {@code Not applied.}. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    private final BufferedReader reader;
    private final PrintStream out;

    Prompter(InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
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
        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (line == null) {
            out.println();
            throw new Cancelled();
        }
        return line.trim();
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
