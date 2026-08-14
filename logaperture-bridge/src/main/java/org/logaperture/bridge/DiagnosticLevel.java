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
package org.logaperture.bridge;

/**
 * Severity levels for the agent's own self-diagnostics (doc/logaperture-spec.md
 * §4.5) — deliberately separate from {@code org.logaperture.api.Level},
 * which describes the target application's logging, not the agent's own.
 *
 * <p>Declared most-severe-first, so ordinal order is severity order: a
 * threshold of {@code INFO} means "print ERROR, WARN, and INFO; suppress
 * DEBUG."
 */
public enum DiagnosticLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG
}
