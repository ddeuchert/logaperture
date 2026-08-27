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
 * Resolves a target JVM and opens a {@link ControlPlane} to it. The one
 * real implementation runs discovery then attaches; {@link Main#run} takes
 * this as a parameter so a test can pass a fake instead of a live JVM.
 */
@FunctionalInterface
interface Connector {

    /** The production path: {@link Discovery} then {@link AgentConnection#open}. */
    Connector REAL = explicitPid -> AgentConnection.open(Discovery.resolveTargetPid(explicitPid));

    /**
     * @param explicitPid a {@code --pid} value, or {@code null} to discover one
     * @throws CliError with the matching exit code when no single target can be reached
     */
    ControlPlane connect(Long explicitPid);
}
