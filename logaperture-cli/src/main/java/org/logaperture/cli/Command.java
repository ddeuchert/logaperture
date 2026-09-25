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

import org.logaperture.control.jmx.LevelControlMXBean;

import java.io.InputStream;
import java.io.PrintStream;

/**
 * One resolved sub-command, already bound to its arguments by {@link
 * Parser}. Every command is a client of the same {@link
 * LevelControlMXBean} surface — no command is a privileged path
 * (doc/logaperture-spec.md §8.1).
 */
@FunctionalInterface
interface Command {

    /**
     * @param in          only read by a pattern-targeted {@code setLogger}'s
     *                    confirmation prompt (doc/specs/
     *                    pattern-level-targeting.md "Confirmation and CLI
     *                    behavior"); every other command ignores it
     * @param interactive whether a real controlling terminal is attached
     *                    ({@code System.console() != null}, resolved once
     *                    by {@link Main} so this stays a plain parameter
     *                    instead of a static call — a command with nothing
     *                    to confirm ignores this too
     * @return the process exit code (almost always {@link CliError#OK}; a
     *     failure is raised as a {@link CliError} instead)
     */
    int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive);

    /**
     * The same, with {@code err} for a note that must not mix into {@code out} -- e.g. {@code
     * export vendor-defaults}, whose stdout is the file itself. Every other command writes only to
     * {@code out} and ignores {@code err}; {@link Main} always calls this form.
     */
    default int run(LevelControlMXBean mbean, PrintStream out, PrintStream err, InputStream in, boolean interactive) {
        return run(mbean, out, in, interactive);
    }
}
