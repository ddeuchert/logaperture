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

import com.sun.tools.attach.VirtualMachine;

/**
 * Best-effort teardown of the attach / JMX resources a {@code logctl}
 * invocation opens. Failure here is never actionable — the command has
 * already run (or already failed for a reason worth reporting) — so these
 * swallow everything.
 */
final class Quietly {

    private Quietly() {
    }

    static void detach(VirtualMachine vm) {
        if (vm == null) {
            return;
        }
        try {
            vm.detach();
        } catch (Exception ignored) {
            // nothing useful to do on a failed detach
        }
    }

    static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // nothing useful to do on a failed close
        }
    }
}
