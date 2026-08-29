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
package org.logaperture.adapter.jul;

import org.logaperture.api.Level;

/**
 * One handler on a logger's path to the root whose own level floor is
 * stricter than a level being applied — the second, independent gate the
 * M0 spike flagged (doc/specs/wildfly-support.md, "Handler-level
 * thresholds"). A {@code CONSOLE} handler pinned at {@code INFO} is the
 * canonical case: it silently discards anything below {@code INFO} no
 * matter what the logger permits through.
 *
 * @param handlerName a readable identifier for the handler (its simple
 *                    class name — JUL {@code Handler}s have no name of
 *                    their own; WildFly's subsystem names like "CONSOLE"
 *                    are a layer above JBoss LogManager)
 * @param floor       the handler's own level, as the nearest LogAperture level
 */
public record HandlerFloor(String handlerName, Level floor) {
}
