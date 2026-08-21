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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentBootstrapTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("logaperture.disabled");
    }

    @Test
    void disabledKillSwitch_returnsWithoutTouchingInstrumentation() {
        System.setProperty("logaperture.disabled", "true");

        // Passing null Instrumentation would NPE the moment any code past
        // the kill-switch check tried to use it -- this proves the early
        // return happens before anything else runs.
        assertDoesNotThrow(() -> AgentBootstrap.start(null));
    }
}
