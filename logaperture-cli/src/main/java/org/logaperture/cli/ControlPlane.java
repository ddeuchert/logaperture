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

/**
 * An open, disposable handle to one JVM's level-control surface. The real
 * implementation is {@link AgentConnection} (attach + local JMX); tests
 * substitute a fake so exit-code and rendering behaviour can be exercised
 * without a second JVM.
 */
interface ControlPlane extends AutoCloseable {

    LevelControlMXBean mbean();

    /** Overridden to drop the checked exception — teardown here is best-effort. */
    @Override
    void close();
}
