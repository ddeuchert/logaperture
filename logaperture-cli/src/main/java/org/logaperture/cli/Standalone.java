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
 * A command that needs no connection to an agent: {@code attach} runs before there is one, and
 * {@link Main} calls it instead of opening a {@link ControlPlane}.
 */
interface Standalone extends Command {

    /**
     * @param explicitPid a {@code --pid} value, or {@code null}
     * @param jvmQuestion asks which JVM when none was named, or {@code null} when nothing may be asked
     * @param connector   where the command gets its {@link JvmAttacher}, and a control plane to look at
     *                    the result
     * @return the process exit code; a failure is raised as a {@link CliError}
     */
    int run(Long explicitPid, PrintStream out, PrintStream err, Prompter jvmQuestion, Connector connector);

    @Override
    default int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        throw new IllegalStateException("a standalone command has no control plane to run against");
    }
}
