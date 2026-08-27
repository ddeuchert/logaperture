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

/**
 * A failure with a user-facing message and a specific process exit code —
 * the rows of doc/specs/cli-transport.md "Output and exit codes". {@link
 * Main#run} catches this, prints {@link #getMessage()} to stderr, and
 * returns {@link #exitCode()}. Anything else that escapes is exit {@link
 * #UNEXPECTED}.
 */
final class CliError extends RuntimeException {

    static final int OK = 0;
    static final int UNEXPECTED = 1;
    static final int USAGE = 2;
    static final int NO_JVM = 3;
    static final int AMBIGUOUS = 4;
    static final int ATTACH_DENIED = 5;
    static final int REFUSED = 6;

    private final int exitCode;

    CliError(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }
}
