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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What {@code logctl attach} does to other JVMs, as a seam: the real one is the JDK's Attach API
 * ({@link VmAttacher}); a test passes a fake through {@link Connector#attacher()}.
 */
interface JvmAttacher {

    JvmAttacher REAL = new VmAttacher();

    /**
     * One JVM this user can attach to.
     *
     * @param version  the {@code logaperture.version} marker, or {@code null}: the control surface is not up
     * @param delivery the {@code logaperture.delivery} claim ({@code agent} or {@code extension}), or
     *                 {@code null}: nothing has claimed the JVM
     */
    record Target(long pid, String displayName, String version, String delivery, Instant startedAt,
            String directory) {

        /** Whether LogAperture is, or is about to be, running in it. */
        boolean hasLogAperture() {
            return version != null || delivery != null;
        }
    }

    /**
     * What a load came to.
     *
     * @param version     the marker once the agent had installed within the wait, else {@code null}
     * @param javaVersion the target's Java feature version, 0 if it did not say
     */
    record Loaded(String version, int javaVersion) {
    }

    /** Every JVM this user can attach to, other than this one, in no particular order. */
    List<Target> targets();

    /** @throws CliError exit 3 for no such process, exit 5 for one that cannot be attached to */
    Target inspect(long pid);

    /**
     * Loads {@code agentJar} with {@code agentArgs} (may be {@code null}) and waits up to {@code wait} for
     * the marker to appear.
     *
     * @throws CliError with the cause and the way out (doc/specs/logctl-attach.md #4)
     */
    Loaded load(long pid, Path agentJar, String agentArgs, Duration wait);
}
