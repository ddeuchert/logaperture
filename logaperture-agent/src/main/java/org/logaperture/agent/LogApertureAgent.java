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
package org.logaperture.agent;

import java.lang.instrument.Instrumentation;

/**
 * The javaagent entry point. {@code premain} ({@code -javaagent:}) is the
 * primary entry point every guarantee in this project is validated against
 * (doc/logaperture-spec.md §2, §4.1) — it runs before the target
 * application's {@code main()}, so its hooks are the reliable,
 * class-load-time kind. {@code agentmain} (dynamic attach) is present and
 * delegates to the same bootstrap, but is secondary for this slice: it is
 * not covered by {@code LevelControlEndToEndIT}.
 */
public final class LogApertureAgent {

    private LogApertureAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        AgentBootstrap.start(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        AgentBootstrap.start(inst);
    }
}
