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
 * One handler on a logger's path to the root whose own level is stricter
 * than a level being applied — the second, independent gate the M0 spike
 * flagged (doc/specs/wildfly-support.md, "Handler-level thresholds"). A
 * {@code CONSOLE} handler pinned at {@code INFO} is the canonical case: it
 * silently discards anything below {@code INFO} no matter what the logger
 * permits through.
 *
 * <p>Framework-independent (lives in {@code api}, not an adapter module) so
 * both {@code core} (to build the {@code logctl set} warning — doc/specs/
 * handler-floor-control.md "Warning on level commands") and the control
 * surfaces (to render it) can depend on it without depending on any one
 * adapter.
 *
 * @param handlerRef this handler's stable identity — also the {@code <name>}
 *                    argument to {@code logctl handler <name> <level>}
 * @param currentLevel the handler's own level right now
 */
public record HandlerFloor(HandlerRef handlerRef, Level currentLevel) {

    public HandlerFloor {
        if (handlerRef == null) {
            throw new IllegalArgumentException("handlerRef must not be null");
        }
        if (currentLevel == null) {
            throw new IllegalArgumentException("currentLevel must not be null");
        }
    }
}
