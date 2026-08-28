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
package org.logaperture.core.spi;

import java.util.List;
import java.util.Objects;

/**
 * Where a container's {@code -javaagent} flag goes, for diagnostics and
 * help output — see doc/specs/wildfly-support.md, "The {@code
 * ContainerIntegration} SPI". Slice 1 defines it and has the {@code none}
 * integration return {@link #NONE}; nothing renders it yet (the WildFly
 * integration, Slice 3, is the first producer of a non-empty value).
 *
 * @param summary a one-line description of how to attach the agent, or
 *                {@code ""} when there is nothing to say
 * @param steps   ordered concrete steps; empty when {@code summary} says it all
 */
public record InstallGuidance(String summary, List<String> steps) {

    /** The "nothing to add" guidance — the {@code none} container's value. */
    public static final InstallGuidance NONE = new InstallGuidance("", List.of());

    public InstallGuidance {
        Objects.requireNonNull(summary, "summary");
        steps = List.copyOf(steps);
    }
}
