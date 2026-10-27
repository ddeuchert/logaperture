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
package org.logaperture.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether this JVM's agent was started by a dynamic attach ({@code agentmain}) rather than a
 * {@code -javaagent} entry ({@code premain}) -- what {@code logctl doctor}'s {@code agent.attached}
 * finding reports (doc/specs/logctl-attach.md #9). Kept as a flag in this class, not a system
 * property, so it adds nothing to the properties an operator can see or set.
 */
public final class AttachedStart {

    private static final AtomicBoolean ATTACHED = new AtomicBoolean();

    private AttachedStart() {
    }

    /** Called by the agent's {@code agentmain} path once it is past the kill switch and the duplicate-start guard. */
    public static void mark() {
        ATTACHED.set(true);
    }

    public static boolean isMarked() {
        return ATTACHED.get();
    }
}
