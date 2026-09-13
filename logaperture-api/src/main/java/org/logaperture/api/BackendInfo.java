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
package org.logaperture.api;

/**
 * Best-effort name/version of the logging backend an adapter fronts — e.g.
 * {@code ("JBoss LogManager", "3.1.1.Final")} — for {@code logctl env}
 * (doc/specs/environment-report.md "Adapter / container SPI"). Never a
 * level; {@link LoggingAdapter#backendInfo()}'s own home. Both fields are
 * {@code null} where an adapter can't cheaply resolve the concept at all,
 * same "empty unless overridden" discipline as {@link HandlerDiagnostics}.
 *
 * @param name    the backend's human-readable name, or {@code null} if
 *                undetected
 * @param version the backend's version, or {@code null} if not resolvable
 *                (including when {@code name} itself is known but no
 *                version could be found)
 */
public record BackendInfo(String name, String version) {

    /** Every field {@code null} — the default for an adapter this concept doesn't apply to at all. */
    public static final BackendInfo EMPTY = new BackendInfo(null, null);
}
