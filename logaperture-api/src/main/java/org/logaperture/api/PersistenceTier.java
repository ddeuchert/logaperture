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
 * The three durability tiers an override can be set at — see
 * doc/logaperture-spec.md §6.1 and doc/specs/persistence.md. Named at the
 * point of use, per §6.2's phone test ("{@code logctl debug com.acme for
 * 30m}") and §6.1's "the choice is conscious" — replaces the implicit
 * "non-null {@code expiresIn} means {@code --for}" convention {@link
 * SetLevelOptions}'s Javadoc originally reserved, which had no way to
 * express {@code --sticky}.
 */
public enum PersistenceTier {
    /** Lives until the JVM stops. No timer, no restart survival. */
    SESSION,
    /** Auto-reverts on a timer; survives restart within the window. */
    FOR,
    /** Survives restart until explicitly revoked. */
    STICKY
}
