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
 * A fully parsed command line. {@code help} and {@code version} short-
 * circuit before any JVM is contacted, so {@code command} is {@code null}
 * for those; every other invocation carries a resolved {@link Command}.
 *
 * @param pid an explicit {@code --pid} target, or {@code null} to discover one
 * @param debug the hidden {@code --debug} flag — print stack traces for the CLI's own development
 */
record Invocation(boolean help, boolean version, boolean debug, Long pid, Command command) {

    static Invocation forHelp() {
        return new Invocation(true, false, false, null, null);
    }

    static Invocation forVersion() {
        return new Invocation(false, true, false, null, null);
    }
}
