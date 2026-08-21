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
package org.logaperture.adapter.logback;

/**
 * Thrown when the Logback adapter cannot install — e.g. SLF4J is bound to
 * something other than a Logback {@code LoggerContext} (a NOP binding, or
 * a different framework entirely). Per doc/specs/level-control.md's
 * "Failure handling": the caller must log a diagnostic and do nothing
 * further, never let this propagate into the target application.
 */
public final class AdapterInstallException extends RuntimeException {

    public AdapterInstallException(String message) {
        super(message);
    }
}
